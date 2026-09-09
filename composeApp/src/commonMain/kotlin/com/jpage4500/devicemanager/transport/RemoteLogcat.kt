package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.util.*
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.*

private val log = LoggerConfig.createLogger("RemoteLogcat")

/**
 * a Device Manager server's `/ws/logs`: one device's logcat, pushed as batches.
 *
 * The server holds the `logcat` stream itself, buffers 500ms of it and sends the batch as **gzipped
 * JSON in a binary frame**. Always gzipped — the plain `logs` message its own client can also parse
 * is dead code on the server side, so this implements the binary path and nothing else
 * (`AndroidDeviceManager/docs/SERVER_API.md`, "Gotchas"). Text frames carry the rest, of which one
 * is worth keeping: the pid -> process name map, which is what the App column would otherwise cost a
 * `ps` every 30 seconds to fill.
 *
 * **No server-side filter.** `/ws/logs` takes a `filter` parameter and this deliberately leaves it
 * off. The log window filters its own buffer, so a filter typed there re-reads thousands of lines it
 * already holds and clearing it brings them all back — filtering at the far end would have thrown
 * those lines away before they arrived. Bandwidth is the price, and the gzip is what makes it
 * affordable: log lines compress to roughly a tenth.
 *
 * Closing the collector closes the socket, which is what stops `logcat` on the device — the same
 * shape as `adbLogcat`, and the reason neither needs a stop message.
 *
 * [auth] is [RemoteHttpTransport]'s own — the bearer header every other call on that connection
 * carries, rather than the `token` query parameter the endpoint would also take: a token in a URL
 * ends up in every log the request passes through.
 */
internal fun remoteLogcat(
    client: HttpClient,
    wsUrl: String,
    serial: String,
    auth: HttpRequestBuilder.() -> Unit,
): Flow<LogBatch> = channelFlow {
    val out = this
    client.webSocket(
        urlString = "$wsUrl$PATH_LOGS",
        request = {
            auth()
            parameter(PARAM_SERIAL, serial)
        },
    ) {
        log.d("remoteLogcat: $serial started")
        for (frame in incoming) {
            val batch = when (frame) {
                is Frame.Binary -> parseBatch(frame.data, serial)
                is Frame.Text -> parseMessage(frame.readText(), serial)
                else -> null
            }
            if (batch != null) out.send(batch)
        }
        log.d("remoteLogcat: $serial stopped")
    }
}
    // gunzip and a JSON parse per batch, off the collector's thread: the log window collects on the
    // scope it was composed with, and a busy device is a batch of thousands of lines every 500ms
    .flowOn(ioDispatcher)

/** a batch: gzip of `["09-08 14:03:54.308 1234 1300 D MyTag: hello", …]`. */
private fun parseBatch(message: ByteArray, serial: String): LogBatch? {
    val json = gunzip(message)
    if (json == null) {
        log.w("parseBatch: $serial ${message.size} bytes wouldn't decompress")
        return null
    }
    val lines = JsonUtils.stringToList<String>(json.decodeToString())
    if (lines.isEmpty()) return null
    return LogBatch(lines.map { it.withoutNullFields() })
}

/**
 * everything that isn't a batch. Only the process map is worth passing on.
 *
 * The others are `connected`, a `status` acknowledging a control message this doesn't send, `pong`
 * for a ping it doesn't send either, and `error` — a filter that wouldn't parse, or a device that
 * has gone. An error is logged and the socket closes on its own; the window shows the log stopped,
 * which is what it shows when an adb `logcat` ends.
 */
private fun parseMessage(text: String, serial: String): LogBatch? {
    val message = jsonObjectOrNull(text) ?: return null
    val type = message.string(FIELD_TYPE)
    if (type == TYPE_PROCESS_MAP) {
        val processes = message.stringMap(FIELD_MAP)
        // the map is re-sent every 5s while the log runs, and comes back empty from a device that
        // wouldn't answer `ps` — an empty one is nothing to pass on
        if (processes.isEmpty()) return null
        log.d("parseMessage: $serial ${processes.size} process names")
        return LogBatch(processes = processes)
    }
    val said = message.string(FIELD_MESSAGE)
    if (type == TYPE_ERROR) {
        log.w("parseMessage: $serial error: ${said.orEmpty()}")
    } else {
        log.d("parseMessage: $serial ${type.orEmpty()} ${said.orEmpty()}")
    }
    return null
}

/**
 * a line the server built from an entry it couldn't parse arrives as `null null null null null:
 * <line>`, because its `toString` doesn't know its own fields are missing.
 *
 * Those are logcat's own `--------- beginning of main` separators. Handing on just the line puts
 * them where the adb route puts them — a message with no date, pid or level — instead of a row of
 * five `null`s.
 */
private fun String.withoutNullFields(): String =
    if (startsWith(UNPARSED_PREFIX)) substring(UNPARSED_PREFIX.length) else this

private const val UNPARSED_PREFIX = "null null null null null: "

private const val PATH_LOGS = "/ws/logs"
private const val PARAM_SERIAL = "serial"

private const val FIELD_TYPE = "type"
private const val FIELD_MESSAGE = "message"
private const val FIELD_MAP = "map"
private const val TYPE_PROCESS_MAP = "processMap"
private const val TYPE_ERROR = "error"
