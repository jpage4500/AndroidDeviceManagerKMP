package com.jpage4500.devicemanager.util

import android.os.Process
import android.util.Log
import co.touchlab.kermit.Severity

/** [AppLogWriter] over logcat. */
class AndroidLogWriter : AppLogWriter() {

    override fun getThreadId(): String {
        val thread = Thread.currentThread()
        if (thread.name.contains("main", ignoreCase = true)) return "UI"
        // the linux TID rather than the JVM thread id — it's what a native trace and `top` show
        return "%02X".format(Process.myTid() % 256)
    }

    override fun logToConsole(
        timeStr: String,
        severity: Severity,
        threadId: String,
        tag: String,
        message: String,
    ) {
        val level = when (severity) {
            Severity.Verbose -> Log.VERBOSE
            Severity.Debug -> Log.DEBUG
            Severity.Info -> Log.INFO
            Severity.Warn -> Log.WARN
            Severity.Error -> Log.ERROR
            Severity.Assert -> Log.ASSERT
        }

        // logcat truncates a line past ~4000 chars, so split rather than lose the tail
        if (message.length <= MAX_LOG_LENGTH) {
            Log.println(level, tag, message)
            return
        }
        var index = 0
        while (index < message.length) {
            val end = minOf(index + MAX_LOG_LENGTH, message.length)
            // prefer to break on a space inside the chunk
            val breakAt = if (end < message.length) {
                val space = message.lastIndexOf(' ', end - 1)
                if (space > index) space + 1 else end
            } else {
                end
            }
            Log.println(level, tag, message.substring(index, breakAt))
            index = breakAt
        }
    }

    companion object {
        private const val MAX_LOG_LENGTH = 4000
    }
}
