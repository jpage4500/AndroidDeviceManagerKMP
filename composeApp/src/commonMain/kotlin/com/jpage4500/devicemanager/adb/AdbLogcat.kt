package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

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
 *
 * [since] is where to start — see [logcatArgs].
 */
fun adbLogcat(
    server: AdbServer,
    serial: String,
    since: String? = null,
    flushMs: Long = 100,
): Flow<List<String>> = channelFlow {
    val args = logcatArgs(since)
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
        log.d("adbLogcat: $serial: started, $args")

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

/**
 * `logcat`'s arguments, starting no further back than [MAX_LOG_HISTORY_MINS].
 *
 * Without `-T` logcat replays its whole ring buffer first, which on a Galaxy S21 reached back most
 * of a day. The Swing app's rule: start [since] — the last line already seen, when a log is resumed
 * — or 10 minutes ago, whichever is later.
 *
 * The time is this computer's clock, read as the device's: logcat has no other way to take it. A
 * device on another time zone starts that much earlier or later, as it did in the Swing app.
 */
fun logcatArgs(since: String?): String = "-v threadtime -T '${logcatStartTime(since)}'"

// max logcat history pulled when logging starts — the Swing app's MAX_LOG_HISTORY_MINS
private const val MAX_LOG_HISTORY_MINS = 10
private const val LOG_TIME_PATTERN = "MM-dd HH:mm:ss.SSS"

/** `MM-dd HH:mm:ss.SSS`, the form `logcat -T` takes — [since], or 10 minutes ago if that's later. */
internal fun logcatStartTime(since: String?): String {
    val zone = TimeZone.currentSystemDefault()
    val now = DateUtils.nowMs()
    val earliest = now - MAX_LOG_HISTORY_MINS * 60_000L
    val last = since?.let { DateUtils.parseDate(it, if ('.' in it) LOG_TIME_PATTERN else "MM-dd HH:mm:ss") }
    if (last != null) {
        var ms = last.toInstant(zone).toEpochMilliseconds()
        // a line from December read in January: parseDate gave it this year
        if (ms - now > 86_400_000L) {
            ms = LocalDateTime(last.year - 1, last.month, last.day, last.hour, last.minute, last.second, last.nanosecond)
                .toInstant(zone).toEpochMilliseconds()
        }
        if (ms >= earliest) return DateUtils.formatDate(last, LOG_TIME_PATTERN)
    }
    return DateUtils.formatDate(DateUtils.timeToDate(earliest), LOG_TIME_PATTERN)
}
