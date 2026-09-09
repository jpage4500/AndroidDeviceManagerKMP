package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.util.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
// explicit: ktor's io package declares a CancellationException typealias too, and two wildcard
// imports make the name ambiguous
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.concurrent.Volatile

/**
 * a Device Manager server's `/ws/screen`: frames pushed down, input sent back up the same socket.
 *
 * What this replaces on the same route is polling `/api/screenshot` — a request, a capture and a
 * full-size PNG four times a second, with every tap a POST of its own that queues behind them. Here
 * the server keeps the clock and sends a JPEG when it has one, and a tap is a text frame on a
 * connection that is already open. See docs/ADB-TRANSPORTS.md, tier II.
 *
 * **One collector at a time.** [frames] opens the socket when it is collected and closes it when
 * that ends, and [send] needs that socket, so collecting twice would leave input going to whichever
 * of the two connected last. One mirror window is one of these.
 */
class RemoteScreenStream(
    private val client: HttpClient,
    /** `ws://host:port` — the address [RemoteHttpTransport] uses, with the websocket scheme. */
    private val wsUrl: String,
    private val serial: String,
    private val intervalMs: Long,
    /**
     * [RemoteHttpTransport]'s own auth block — the bearer header every other call on that connection
     * carries, rather than the `token` query parameter the endpoint would also take: a token in a URL
     * ends up in every log the request passes through.
     */
    private val auth: HttpRequestBuilder.() -> Unit,
) : ScreenStream {

    private val log = LoggerConfig.createLogger("RemoteScreenStream")

    // the live socket, so input can go up the connection the frames come down. null whenever it is
    // down, which is what makes a tap in that window a dropped tap rather than a queued one
    @Volatile
    private var live: WebSocketSession? = null

    override val frames: Flow<ScreenFrame> = channelFlow {
        val out = this
        var seen = 0L
        // an error *before* the first frame is the fatal kind — a bad token, an unknown serial, or a
        // server with no /ws/screen at all — and the API reports all three after the upgrade has
        // already succeeded. Once frames are flowing, an error is one capture that didn't happen
        var refusal: String? = null

        // `ws://` and not `http://`: ktor's HttpTimeout plugin exempts a websocket protocol from the
        // 20s request timeout it puts on everything else, and a mirror is meant to stay open
        client.webSocket(
            urlString = "$wsUrl$PATH_SCREEN",
            request = {
                auth()
                parameter(PARAM_SERIAL, serial)
                // JPEG rather than PNG — a full-size PNG of a 1080p screen is megabytes, and this
                // one is asking for four a second
                parameter(PARAM_COMPRESS, true)
            },
        ) {
            live = this
            try {
                log.d("frames: $serial open, asking for ${intervalMs}ms")
                // the handshake reads only serial and compress, so an interval in the URL is
                // ignored — it has to be asked for on the socket, and this is the one place it is
                send(Frame.Text(intervalJson(intervalMs)))
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> parseFrame(frame.data)?.let {
                            seen++
                            out.send(it)
                        }
                        // status and error both arrive as text; only a refusal changes anything here
                        is Frame.Text -> {
                            val error = readMessage(frame.readText())
                            if (error != null && seen == 0L) refusal = error
                        }
                        else -> Unit
                    }
                }
                log.d("frames: $serial closed after $seen frames")
            } finally {
                live = null
            }
        }

        // it went before it ever worked, and said why. that reason is worth more than an empty
        // stream, which a mirror can only draw as a device with nothing on its screen
        val fatal = refusal
        if (seen == 0L && fatal != null) throw IllegalStateException(fatal)
    }
        // the newest frame is the only one worth having: a decode that falls behind should drop what
        // it missed rather than hold the socket up behind a frame that is already out of date
        .conflate()

    override suspend fun send(event: DeviceInput.Event) {
        val session = live
        if (session == null) {
            // the event itself isn't logged: a Text carries whatever someone typed on the device
            log.d("send: $serial isn't connected, dropping a ${event::class.simpleName}")
            return
        }
        try {
            session.send(Frame.Text(inputJson(event)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the socket went between the gesture and this; reconnecting is the frame loop's job
            log.w("send: $serial ${e.message}")
        }
    }

    /**
     * one binary message: a 4-byte big-endian header length, that many bytes of JSON, then the image.
     *
     * The header's `width` and `height` are the **device's**, not the image's — they differ at any
     * quality below the top one, and input is mapped against the device's. See [ScreenFrame].
     */
    private fun parseFrame(message: ByteArray): ScreenFrame? {
        if (message.size <= HEADER_LEN_BYTES) return null
        val headerLen = message.bigEndianInt(0)
        val imageAt = HEADER_LEN_BYTES + headerLen
        if (headerLen <= 0 || imageAt >= message.size) {
            log.w("parseFrame: $serial header of $headerLen in ${message.size} bytes")
            return null
        }
        val header = jsonObjectOrNull(message.decodeToString(HEADER_LEN_BYTES, imageAt))
        if (header == null) {
            log.w("parseFrame: $serial header wouldn't parse")
            return null
        }
        return ScreenFrame(
            image = message.copyOfRange(imageAt, message.size),
            deviceWidth = header.int(FIELD_WIDTH),
            deviceHeight = header.int(FIELD_HEIGHT),
        )
    }

    /**
     * a status or an error line.
     *
     * @return what the error said, when that is what it was
     */
    private fun readMessage(text: String): String? {
        val message = jsonObjectOrNull(text) ?: return null
        // an error carries `error`, except the unauthorized one, which carries `message`
        val said = message.string(FIELD_MESSAGE) ?: message.string(TYPE_ERROR)
        if (message.string(FIELD_TYPE) != TYPE_ERROR) {
            log.d("readMessage: $serial ${message.string(FIELD_STATUS).orEmpty()} ${said.orEmpty()}")
            return null
        }
        log.w("readMessage: $serial error: $said")
        return said ?: "the server wouldn't stream this screen"
    }

    private fun intervalJson(ms: Long): String = buildJsonObject {
        put(FIELD_ACTION, ACTION_SET_INTERVAL)
        // out of range is answered with an error and ignored, which would leave the stream on the
        // server's own default rather than on what this asked for
        put(FIELD_INTERVAL, ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS))
    }.toString()

    /** an event as the server's own input message — the other half of [DeviceInput.shell]. */
    private fun inputJson(event: DeviceInput.Event): String = buildJsonObject {
        put(FIELD_ACTION, ACTION_INPUT)
        when (event) {
            is DeviceInput.Tap -> {
                put(FIELD_TYPE, INPUT_TAP)
                put("x", event.x)
                put("y", event.y)
            }

            is DeviceInput.Swipe -> {
                put(FIELD_TYPE, INPUT_SWIPE)
                put("x1", event.x1)
                put("y1", event.y1)
                put("x2", event.x2)
                put("y2", event.y2)
                put("duration", event.durationMs)
            }

            // the plain string: the server does the `%s` escaping `input text` needs, so a string
            // escaped for the shell would arrive with literal `%s` in it
            is DeviceInput.Text -> {
                put(FIELD_TYPE, INPUT_TEXT)
                put("text", event.value)
            }

            is DeviceInput.Key -> {
                put(FIELD_TYPE, INPUT_KEYEVENT)
                put("keycode", event.keyCode)
            }
        }
    }.toString()

    companion object {
        private const val PATH_SCREEN = "/ws/screen"
        private const val PARAM_SERIAL = "serial"
        private const val PARAM_COMPRESS = "compress"

        /** the int32 in front of every frame, saying how much of it is header. */
        private const val HEADER_LEN_BYTES = 4

        // the server's own bounds: outside them it answers with an error and changes nothing
        private const val MIN_INTERVAL_MS = 50L
        private const val MAX_INTERVAL_MS = 5_000L

        private const val FIELD_ACTION = "action"
        private const val FIELD_TYPE = "type"
        private const val FIELD_STATUS = "status"
        private const val FIELD_MESSAGE = "message"
        private const val FIELD_INTERVAL = "intervalMs"
        private const val FIELD_WIDTH = "width"
        private const val FIELD_HEIGHT = "height"

        private const val ACTION_INPUT = "input"
        private const val ACTION_SET_INTERVAL = "setInterval"
        private const val TYPE_ERROR = "error"
        private const val INPUT_TAP = "tap"
        private const val INPUT_SWIPE = "swipe"
        private const val INPUT_TEXT = "text"
        private const val INPUT_KEYEVENT = "keyevent"
    }
}

/** the four bytes at [index] as a big-endian int, which is how the frame header's length arrives. */
private fun ByteArray.bigEndianInt(index: Int): Int =
    (this[index].toInt() and 0xff shl 24) or
        (this[index + 1].toInt() and 0xff shl 16) or
        (this[index + 2].toInt() and 0xff shl 8) or
        (this[index + 3].toInt() and 0xff)
