package com.jpage4500.devicemanager.server

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.concurrent.Volatile
import kotlin.math.*

private val log = LoggerConfig.createLogger("ShareScreenStream")

/**
 * `/ws/screen`: a device's screen as a stream of stills, with input coming back up the same socket.
 *
 * The Swing server's `ScreenStreamWebSocket`, and the other half of [RemoteScreenStream]. Each frame
 * is one binary message — a 4-byte big-endian header length, a JSON header, then the image — and
 * the header's `width` and `height` are the **device's**, so a client maps input against them
 * whatever the image was scaled to.
 *
 * The capture is `screencap -p` through [DeviceManager.screenshot], on a fixed delay *after* each
 * frame rather than a fixed rate: a capture over USB takes a few hundred ms, and a rate faster than
 * that would only queue frames that are stale by the time they go.
 *
 * The device is woken when a stream opens and kept awake while any stream of it is open — see
 * [StayAwake].
 */
internal class ShareScreenStream(private val device: Device, compress: Boolean) {
    @Volatile
    private var intervalMs = DEFAULT_INTERVAL_MS

    @Volatile
    private var compress = compress

    @Volatile
    private var quality = ScreenQuality.HIGH

    @Volatile
    private var paused = false

    private var frameId = 0L
    // consecutive failures, so a device that's gone logs once rather than four times a second
    private var failures = 0

    // a nudge for the capture loop: a new interval starts now rather than after the old one's wait
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    suspend fun run(socket: ShareSocket) = coroutineScope {
        ShareApi.wake(device)
        StayAwake.hold(device)
        try {
            socket.sendText(status("connected", "Screen stream started"))
            launch {
                while (isActive) {
                    if (!paused) capture(socket)
                    withTimeoutOrNull(intervalMs.toLong()) { nudge.receive() }
                }
            }
            val control = launch {
                for (text in socket.incoming) {
                    if (!onControl(text, socket)) break
                }
            }
            control.join()
            log.d("run: ${device.serial} closed after $frameId frames")
            coroutineContext.cancelChildren()
        } finally {
            withContext(NonCancellable) { StayAwake.release(device) }
        }
    }

    private suspend fun capture(socket: ShareSocket) {
        val png = DeviceManager.screenshot(device.id).getOrElse {
            if (failures++ == 0) log.w("capture: ${device.serial}: ${it.message}")
            socket.sendText(error("Capture error: ${it.message}"))
            return
        }
        val current = quality
        val frame = withContext(Dispatchers.Default) {
            FrameEncoder.encode(png, scaleOf(current), if (compress) jpegQualityOf(current) else null)
        }
        if (frame == null) {
            if (failures++ == 0) log.w("capture: ${device.serial}: ${png.size} bytes wouldn't decode")
            socket.sendText(error("Capture error: the screen couldn't be read"))
            return
        }
        failures = 0
        val header = buildJsonObject {
            put("type", "frame")
            put("frameId", ++frameId)
            put("width", frame.deviceWidth)
            put("height", frame.deviceHeight)
            put("timestamp", DateUtils.nowMs())
            put("format", frame.format)
            put("size", frame.bytes.size)
        }.toString().encodeToByteArray()
        val message = ByteBuffer.allocate(4 + header.size + frame.bytes.size)
            .putInt(header.size)
            .put(header)
            .put(frame.bytes)
            .array()
        socket.sendBinary(message)
    }

    /** @return false when the client asked for the socket to close */
    private suspend fun onControl(text: String, socket: ShareSocket): Boolean {
        val message = jsonObjectOrNull(text)
        val action = message?.text("action")
        when (action) {
            ACTION_CLOSE -> return false
            ACTION_PAUSE -> {
                paused = true
                socket.sendText(status("paused", "Stream paused"))
            }

            ACTION_RESUME -> {
                paused = false
                nudge.trySend(Unit)
                socket.sendText(status("resumed", "Stream resumed"))
            }

            ACTION_SET_INTERVAL -> {
                val ms = message.number("intervalMs")?.toInt()
                when {
                    ms == null -> socket.sendText(error("Invalid intervalMs value"))
                    ms !in MIN_INTERVAL_MS..MAX_INTERVAL_MS ->
                        socket.sendText(error("Interval must be between $MIN_INTERVAL_MS and ${MAX_INTERVAL_MS}ms"))
                    else -> {
                        intervalMs = ms
                        nudge.trySend(Unit)
                        socket.sendText(status("interval_changed", "Interval set to ${ms}ms"))
                    }
                }
            }

            ACTION_SET_COMPRESSION -> {
                val on = (message["useCompression"] as? JsonPrimitive)?.booleanOrNull
                if (on == null) {
                    socket.sendText(error("Invalid useCompression value"))
                } else if (on != compress) {
                    compress = on
                    socket.sendText(status("compression_changed", "Compression set to ${if (on) "JPEG" else "PNG"}"))
                }
            }

            ACTION_SET_QUALITY -> {
                val wanted = ScreenQuality.entries.firstOrNull { it.wire == message.text("quality") }
                if (wanted == null) {
                    socket.sendText(error("Quality must be 'high', 'medium', or 'low'"))
                } else if (wanted != quality) {
                    quality = wanted
                    socket.sendText(status("quality_changed", "Quality set to ${wanted.wire}"))
                }
            }

            ACTION_INPUT -> input(message, socket)
            null -> socket.sendText(error("Missing 'action' field in control message"))
            else -> socket.sendText(error("Unknown action: $action"))
        }
        return true
    }

    /** a tap, swipe, text or key — run as the same `input` command the adb route uses. */
    private suspend fun input(message: JsonObject, socket: ShareSocket) {
        val event = when (val type = message.text("type")) {
            INPUT_TAP -> {
                val x = message.number("x")
                val y = message.number("y")
                if (x == null || y == null) return socket.sendText(error("Invalid tap coordinates"))
                DeviceInput.Tap(x.toInt(), y.toInt())
            }

            INPUT_SWIPE -> {
                val x1 = message.number("x1")
                val y1 = message.number("y1")
                val x2 = message.number("x2")
                val y2 = message.number("y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    return socket.sendText(error("Invalid swipe coordinates"))
                }
                val duration = message.number("duration")?.toInt() ?: DEFAULT_SWIPE_MS
                DeviceInput.Swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), duration)
            }

            INPUT_TEXT -> {
                val value = message.text("text")
                if (value.isNullOrEmpty()) return socket.sendText(error("Missing or empty text"))
                DeviceInput.Text(value)
            }

            INPUT_KEYEVENT -> {
                val code = message.number("keycode") ?: return socket.sendText(error("Invalid keycode"))
                DeviceInput.Key(code.toInt())
            }

            null -> return socket.sendText(error("Missing 'type' in input message"))
            else -> return socket.sendText(error("Unknown input type: $type"))
        }
        DeviceManager.sendInput(device.id, event)
        // the next frame now, so a tap shows its result without waiting out the interval
        nudge.trySend(Unit)
    }

    private fun status(status: String, message: String) = buildJsonObject {
        put("type", "status")
        put("status", status)
        put("message", message)
    }.toString()

    // the screen socket's errors carry `error`, not `message` — the Swing server's shape
    private fun error(text: String) = buildJsonObject {
        put("type", "error")
        put("error", text)
    }.toString()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private companion object {
        const val DEFAULT_INTERVAL_MS = 250
        const val MIN_INTERVAL_MS = 50
        const val MAX_INTERVAL_MS = 5_000
        const val DEFAULT_SWIPE_MS = 300

        const val ACTION_CLOSE = "close"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_SET_INTERVAL = "setInterval"
        const val ACTION_SET_COMPRESSION = "setCompression"
        const val ACTION_SET_QUALITY = "setQuality"
        const val ACTION_INPUT = "input"

        const val INPUT_TAP = "tap"
        const val INPUT_SWIPE = "swipe"
        const val INPUT_TEXT = "text"
        const val INPUT_KEYEVENT = "keyevent"

        // the Swing server's table: a smaller image at a lower JPEG quality for each step down
        fun scaleOf(quality: ScreenQuality): Double = when (quality) {
            ScreenQuality.HIGH -> 1.0
            ScreenQuality.MEDIUM -> 0.75
            ScreenQuality.LOW -> 0.5
        }

        fun jpegQualityOf(quality: ScreenQuality): Float = when (quality) {
            ScreenQuality.HIGH -> 0.70f
            ScreenQuality.MEDIUM -> 0.50f
            ScreenQuality.LOW -> 0.30f
        }
    }
}

/** a captured screen, encoded for the wire. */
internal class EncodedFrame(val bytes: ByteArray, val format: String, val deviceWidth: Int, val deviceHeight: Int)

/** `screencap`'s PNG, scaled and re-encoded with the JDK's own ImageIO — no image library. */
internal object FrameEncoder {
    /**
     * @param jpegQuality null for PNG
     * @return null when [png] isn't an image
     */
    fun encode(png: ByteArray, scale: Double, jpegQuality: Float?): EncodedFrame? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
        val width = image.width
        val height = image.height
        // full size and PNG is what screencap already produced; decoding it was only for the size
        if (jpegQuality == null && scale >= 1.0) return EncodedFrame(png, FORMAT_PNG, width, height)

        val scaledWidth = max(1, (width * scale).roundToInt())
        val scaledHeight = max(1, (height * scale).roundToInt())
        // JPEG has no alpha, and screencap's PNG has one — drawn onto RGB either way, which is also
        // what scales it
        val target = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(image, 0, 0, scaledWidth, scaledHeight, null)
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        if (jpegQuality == null) {
            ImageIO.write(target, "png", out)
            return EncodedFrame(out.toByteArray(), FORMAT_PNG, width, height)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = jpegQuality
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(target, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return EncodedFrame(out.toByteArray(), FORMAT_JPEG, width, height)
    }

    private const val FORMAT_PNG = "png"
    private const val FORMAT_JPEG = "jpeg"
}

/**
 * keep a device's screen on while anything is streaming it, and put the setting back after.
 *
 * Counted, because two clients can be watching the same device: the first one in saves what
 * `stay_on_while_plugged_in` was and sets it, the last one out restores it. The Swing app's
 * `setStayAwake`, which kept a map for the same reason.
 */
internal object StayAwake {
    private const val GET = "settings get global stay_on_while_plugged_in"
    private const val PUT = "settings put global stay_on_while_plugged_in"
    // AC, USB and wireless: on whatever it is plugged into
    private const val ALL = "7"

    private val lock = Mutex()
    private val held = mutableMapOf<String, Hold>()

    private class Hold(var count: Int, val previous: String)

    suspend fun hold(device: Device) = lock.withLock {
        held[device.id]?.let {
            it.count++
            return@withLock
        }
        // "settings get" prints "null" for a setting that was never set
        val previous = DeviceManager.exec(device.id, GET).getOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" } ?: "0"
        held[device.id] = Hold(1, previous)
        DeviceManager.exec(device.id, "$PUT $ALL")
    }

    suspend fun release(device: Device) = lock.withLock {
        val hold = held[device.id] ?: return@withLock
        if (--hold.count > 0) return@withLock
        held.remove(device.id)
        DeviceManager.exec(device.id, "$PUT ${hold.previous}")
    }
}
