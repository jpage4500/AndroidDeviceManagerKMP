package com.jpage4500.devicemanager.server

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.Volatile

private val log = LoggerConfig.createLogger("ShareLogStream")

/**
 * `/ws/logs`: one device's logcat, sent to a client as it arrives.
 *
 * The Swing server's `LogStreamWebSocket`, and the other half of [com.jpage4500.devicemanager.transport.remoteLogcat]:
 * lines are buffered for half a second and sent as **gzipped JSON in a binary frame**, an array of
 * `logcat -v threadtime` lines — log text compresses to about a tenth, which is what makes a busy
 * device affordable over a phone's Wi-Fi. Text frames carry the rest: `connected`, the pid -> name
 * map, `status` replies, `pong`, `error`.
 *
 * The lines go as logcat printed them, not re-formatted: both clients split a line on whitespace,
 * and the column padding `threadtime` adds is whitespace.
 *
 * Reads through [DeviceManager.logcat], the same stream a log tab here reads, so a device whose log
 * is open on this desk and on a phone is two `logcat`s — as it would be with two log tabs.
 */
internal class ShareLogStream(private val device: Device, @Volatile private var filter: LogFilter?) {
    private val buffer = mutableListOf<String>()
    private val bufferLock = Mutex()

    // pid -> process name, which an App filter term matches on too
    @Volatile
    private var processes: Map<String, String> = emptyMap()

    @Volatile
    private var paused = false

    @Volatile
    private var reader: Job? = null

    // the loop reading the client's messages; ending it is what ends the stream
    private lateinit var control: Job

    suspend fun run(socket: ShareSocket) = coroutineScope {
        socket.sendText(message(TYPE_CONNECTED) {
            put("device", device.serial)
            put("message", "Connected to log stream")
        })

        control = launch {
            for (text in socket.incoming) onControl(text, socket, this@coroutineScope)
        }
        startReading(socket, this)

        launch {
            while (isActive) {
                delay(BATCH_MS)
                flush(socket)
            }
        }

        launch {
            while (isActive) {
                sendProcesses(socket)
                delay(PROCESS_MAP_MS)
            }
        }

        // the client going is the end of it; so is the device's logcat ending
        control.join()
        log.d("run: ${device.serial} closed")
        coroutineContext.cancelChildren()
    }

    private fun startReading(socket: ShareSocket, scope: CoroutineScope) {
        if (reader?.isActive == true) return
        reader = scope.launch {
            val ended = runCatching {
                DeviceManager.logcat(device.id).collect { batch ->
                    if (batch.lines.isEmpty()) return@collect
                    val current = filter
                    val kept = if (current == null) {
                        batch.lines
                    } else {
                        val names = processes
                        batch.lines.filter { current.accepts(LogEntry.parse(it, 0), names) }
                    }
                    if (kept.isNotEmpty()) bufferLock.withLock { buffer += kept }
                }
            }
            val error = ended.exceptionOrNull()
            if (error is CancellationException) throw error
            // a pause cancels this on purpose; anything else ending it means the device went
            if (!paused) {
                log.d("startReading: ${device.serial} logcat ended: ${error?.message}")
                flush(socket)
                socket.sendText(message(TYPE_ERROR) { put("message", "log stream ended: ${error?.message ?: "device disconnected"}") })
                control.cancel()
            }
        }
    }

    /** whatever has built up since the last flush, as one gzipped batch. */
    private suspend fun flush(socket: ShareSocket) {
        if (paused) return
        val lines = bufferLock.withLock {
            if (buffer.isEmpty()) return
            buffer.toList().also { buffer.clear() }
        }
        val json = JsonArray(lines.map { JsonPrimitive(it) }).toString()
        socket.sendBinary(gzip(json.encodeToByteArray()))
    }

    /** `ps`, sent only when it changed — the client keeps the last one it was given. */
    private suspend fun sendProcesses(socket: ShareSocket) {
        val output = DeviceManager.exec(device.id, LogsSource.CMD_PS).getOrNull() ?: return
        val map = LogsSource.parseProcessList(output)
        if (map.isEmpty() || map == processes) return
        processes = map
        socket.sendText(message(TYPE_PROCESS_MAP) { put("map", JsonObject(map.mapValues { JsonPrimitive(it.value) })) })
    }

    private suspend fun onControl(text: String, socket: ShareSocket, scope: CoroutineScope) {
        val action = jsonObjectOrNull(text)?.let { (it["action"] as? JsonPrimitive)?.contentOrNull }
        when (action) {
            // stops logcat on the device, not just the sending — nothing builds up while paused
            ACTION_PAUSE -> if (!paused) {
                paused = true
                reader?.cancel()
                bufferLock.withLock { buffer.clear() }
                socket.sendText(message(TYPE_STATUS) { put("state", "paused") })
            }

            ACTION_RESUME -> if (paused) {
                paused = false
                startReading(socket, scope)
                socket.sendText(message(TYPE_STATUS) { put("state", "resumed") })
            }

            ACTION_FILTER -> {
                val filterText = jsonObjectOrNull(text)?.let { (it["filterText"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                filter = filterText.takeIf { it.isNotBlank() }?.let { LogFilter(name = "", expression = it) }
                socket.sendText(message(TYPE_STATUS) {
                    put("state", "filter_updated")
                    put("filter", filterText)
                })
            }

            ACTION_PING -> socket.sendText(message(TYPE_PONG) { put("timestamp", DateUtils.nowMs()) })
            null -> socket.sendText(message(TYPE_ERROR) { put("message", "Missing 'action' field in control message") })
            else -> socket.sendText(message(TYPE_ERROR) { put("message", "Unknown action: $action") })
        }
    }

    private fun message(type: String, fields: JsonObjectBuilder.() -> Unit): String = buildJsonObject {
        put("type", type)
        fields()
    }.toString()

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private companion object {
        // the Swing server's batch interval: often enough to read as live, rarely enough to compress
        const val BATCH_MS = 500L
        // the local log window re-reads `ps` every 30s; this is a little keener, since a remote
        // client has no other way to name a process that started since
        const val PROCESS_MAP_MS = 10_000L

        const val TYPE_CONNECTED = "connected"
        const val TYPE_PROCESS_MAP = "processMap"
        const val TYPE_STATUS = "status"
        const val TYPE_ERROR = "error"
        const val TYPE_PONG = "pong"

        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_FILTER = "filter"
        const val ACTION_PING = "ping"
    }
}
