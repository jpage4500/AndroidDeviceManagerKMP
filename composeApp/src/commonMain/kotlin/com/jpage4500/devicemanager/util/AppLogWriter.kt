package com.jpage4500.devicemanager.util

import co.touchlab.kermit.*
import com.jpage4500.devicemanager.data.*

const val PATH_LOGS = "logs"

// stamped in front of every logger tag, so the app's lines stand out in a shared console (logcat,
// Xcode). anything displaying a log strips it back out again — keep the two in sync
const val LOG_TAG_PREFIX = "ADM_"
const val FILE_LOG = "device-manager.log"
const val FILE_BACKUP = "device-manager.log.old"
private const val MAX_LOG_SIZE = 2 * 1024 * 1024 // 2 MB

// how much FILE_LOG must grow before a failed rotation is retried
private const val ROTATE_RETRY_SIZE = 64 * 1024

// running size of FILE_LOG, so a log line doesn't cost a directory listing. -1 means unknown — the
// next write re-reads the real size from disk. only ever touched from StorageManager's single write
// thread, so it needs no locking. counts characters rather than utf-8 bytes, so it undercounts
// slightly for non-ascii; it becomes exact again on every rotation
private var logFileSize = -1L

/**
 * forget the cached size after something writes to FILE_LOG behind [AppLogWriter]'s back —
 * [com.jpage4500.devicemanager.manager.LogManager] appending a crash report through writeFileSync.
 */
fun invalidateLogFileSize() {
    logFileSize = -1L
}

/**
 * rotate FILE_LOG to FILE_BACKUP once it grows past MAX_LOG_SIZE.
 *
 * called after each successful write, on StorageManager's single write thread.
 * can't use a Logger here — logging from inside the log writer recurses.
 */
private fun checkRotateLog(charsWritten: Int) {
    logFileSize = if (logFileSize < 0) {
        // unknown — first write of the session, or invalidated. the size on disk already counts this write
        StorageManager.getFileDetails(PATH_LOGS, FILE_LOG)?.size ?: 0L
    } else {
        logFileSize + charsWritten
    }
    if (logFileSize <= MAX_LOG_SIZE) return

    // delete the previous backup first — renaming over an existing file fails on iOS and windows
    StorageManager.deleteFile(PATH_LOGS, FILE_BACKUP)
    logFileSize = if (StorageManager.renameFile(PATH_LOGS, FILE_LOG, FILE_BACKUP)) {
        0L
    } else {
        // back off rather than retrying the rotate on every subsequent log line
        println("checkRotateLog: unable to rotate $FILE_LOG")
        MAX_LOG_SIZE - ROTATE_RETRY_SIZE.toLong()
    }
}

/**
 * the console format, and the file the log also goes to.
 *
 * `MM-dd-HH:mm:ss [L] TT Tag: message`, where L is the severity letter and TT is a two-hex-digit
 * thread id — or `UI`, which is the whole point of it: a socket read that shouldn't be on the UI
 * thread is visible in the log rather than only in a profiler.
 *
 * each platform subclasses this for those two things; the file half is shared. ported from
 * DashboardWeatherKMP.
 */
open class AppLogWriter : LogWriter() {

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val dateStr = DateUtils.formatDate("MM-dd-HH:mm:ss")
        val threadId = getThreadId()
        val appTag = "$LOG_TAG_PREFIX$tag"

        logToConsole(dateStr, severity, threadId, appTag, message)
        if (throwable != null) {
            logToConsole(dateStr, severity, threadId, tag, throwable.stackTraceToString())
        }

        // INFO and above go to the file; debug mode drops that to DEBUG. verbose never does — the
        // http body log alone would fill 2 MB during a fleet refresh
        val fileSeverity = if (SettingsManager.getBoolean(AppPref.DEBUG_MODE)) Severity.Debug else Severity.Info
        if (severity >= fileSeverity) {
            val formattedMessage = "$dateStr [${getSeverityDesc(severity)}] $threadId $appTag: $message\n"
            StorageManager.writeFile(PATH_LOGS, FILE_LOG, formattedMessage, append = true) { isOk ->
                if (isOk) checkRotateLog(formattedMessage.length)
            }
        }
    }

    /** two hex digits of the current thread, or `UI` for the main one. */
    protected open fun getThreadId(): String = "??"

    protected open fun logToConsole(
        timeStr: String,
        severity: Severity,
        threadId: String,
        tag: String,
        message: String,
    ) {
        println("$timeStr [${getSeverityDesc(severity)}] $threadId $tag: $message")
    }

    protected fun getSeverityDesc(severity: Severity): String = when (severity) {
        Severity.Verbose -> "V"
        Severity.Debug -> "D"
        Severity.Info -> "I"
        Severity.Warn -> "W"
        Severity.Error -> "E"
        Severity.Assert -> "A"
    }
}
