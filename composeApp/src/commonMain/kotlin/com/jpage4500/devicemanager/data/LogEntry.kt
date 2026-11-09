package com.jpage4500.devicemanager.data

/**
 * one `logcat -v threadtime` line, split up.
 *
 * ```
 * 10-16 11:34:17.824  2063  2063 D PluginAODManager: onNotificationInfoUpdated() 0|com.test|2000
 * ```
 *
 * ported from the Swing app's `LogEntry`. A line that doesn't have six fields is kept whole as the
 * message — `--------- beginning of system` is one, and dropping it would lose the only marker
 * saying where a buffer restarted.
 */
data class LogEntry(
    val id: Long,
    val date: String? = null,
    val pid: String? = null,
    val tid: String? = null,
    val level: String? = null,
    val tag: String? = null,
    val message: String = "",
) {
    /** back to the line it was parsed from — what "Copy Line" puts on the clipboard. */
    fun asLine(): String = when (date) {
        null -> message
        else -> "$date ${pid.orEmpty()} ${tid.orEmpty()} ${level.orEmpty()} ${tag.orEmpty()}: $message"
    }

    companion object {
        fun parse(line: String, id: Long): LogEntry {
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) return LogEntry(id, message = line)

            // "11:34:17.824" -> "11:34:17": the microseconds are noise in a column this narrow
            val time = parts[1].let { if (it.length >= 12) it.substring(0, it.length - 4) else it }
            var message = parts[5]
            var tag: String? = null
            // threadtime puts the tag in front of the message, separated by a colon
            val colon = message.indexOf(':')
            if (colon > 0) {
                tag = message.substring(0, colon).trim()
                message = message.substring(colon + 1).trim()
            }
            return LogEntry(
                id = id,
                date = "${parts[0]} $time",
                pid = parts[2],
                tid = parts[3],
                level = parts[4],
                tag = tag,
                message = message,
            )
        }
    }
}

/**
 * what the log table is showing, and how much of the log has already scrolled off the front of it.
 *
 * the two travel together because the table needs both in the same frame: [dropped] is what a row's
 * index has lost to the ring buffer, and the row banding is index plus that — see `DataTable`'s
 * `stripeOffset`. Published apart, a batch could paint a new list against the old count and invert
 * every stripe for a frame.
 */
data class LogPage(
    val entries: List<LogEntry> = emptyList(),
    val dropped: Long = 0L,
)

/**
 * what the ring buffer's hold is doing, for the status bar.
 *
 * [lines] is how many of the oldest lines are being kept that the buffer would otherwise have
 * dropped — it is also why the line count can read above the configured buffer size, which is worth
 * saying out loud rather than leaving to look like a broken setting. [full] means the hold has run
 * out and the oldest lines are going again.
 */
data class LogHold(
    val lines: Int = 0,
    val full: Boolean = false,
)

/** the columns the log table can show, in the Swing app's order. */
enum class LogColumn(val label: String, val defaultWidth: Int, val hideByDefault: Boolean = false) {
    DATE("Date", 110),
    APP("App", 130, hideByDefault = true),
    TID("TID", 55, hideByDefault = true),
    LEVEL("Level", 45),
    TAG("Tag", 150),
    MSG("Message", 700),
    ;

    fun text(entry: LogEntry): String? = when (this) {
        DATE -> entry.date
        APP -> entry.pid
        TID -> entry.tid
        LEVEL -> entry.level
        TAG -> entry.tag
        MSG -> entry.message
    }

    companion object {
        val defaultOrder: List<LogColumn> = entries.toList()
        val defaultHidden: Set<LogColumn> = entries.filter { it.hideByDefault }.toSet()
    }
}
