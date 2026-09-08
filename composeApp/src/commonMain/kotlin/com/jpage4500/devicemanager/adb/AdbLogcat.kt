package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

private val log = LoggerConfig.createLogger("AdbLogcat")

/**
 * `logcat` as it arrives, a batch of lines at a time.
 *
 * **Batched, not one line per emit.** A busy device prints thousands of lines a second, and a flow
 * emission per line would recompose the table thousands of times a second; the collector gets
 * whatever accumulated over [flushMs] instead. That interval is the Swing app's `LOG_INTERVAL_MS`.
 *
 * `-v threadtime` is the format [com.jpage4500.devicemanager.data.LogEntry] parses. The stream is
 * closed when the collector goes away, which is what stops `logcat` on the device.
 */
fun adbLogcat(
    server: AdbServer,
    serial: String,
    args: String = "-v threadtime",
    flushMs: Long = 100,
): Flow<List<String>> = channelFlow {
    val opened = AdbConnection.toDevice(server, serial)
    val connection = when (opened) {
        is AdbOutcome.Err -> {
            log.w("adbLogcat: $serial: ${opened.message}")
            return@channelFlow
        }
        is AdbOutcome.Ok -> opened.value
    }

    try {
        if (connection.request("exec:logcat $args") is AdbOutcome.Err) {
            log.w("adbLogcat: $serial: refused")
            return@channelFlow
        }
        log.d("adbLogcat: $serial: started")

        val pending = mutableListOf<String>()
        // a line can be split across two reads, so whatever follows the last newline is held over
        var partial = StringBuilder()
        val buffer = ByteArray(AdbStream.BUFFER)
        var lastFlush = DateUtils.nowMs()

        while (isActive) {
            val count = connection.raw.readSome(buffer)
            if (count <= 0) break
            partial.append(buffer.decodeToString(0, count))

            val text = partial.toString()
            val lastNewline = text.lastIndexOf('\n')
            if (lastNewline >= 0) {
                text.substring(0, lastNewline).split('\n').forEach { line ->
                    if (line.isNotEmpty()) pending += line.removeSuffix("\r")
                }
                partial = StringBuilder(text.substring(lastNewline + 1))
            }

            val now = DateUtils.nowMs()
            if (pending.isNotEmpty() && now - lastFlush >= flushMs) {
                send(pending.toList())
                pending.clear()
                lastFlush = now
            }
        }
        if (pending.isNotEmpty()) send(pending.toList())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.d("adbLogcat: $serial: ${e.message}")
    } finally {
        log.d("adbLogcat: $serial: stopped")
        connection.close()
    }
}.flowOn(ioDispatcher)
