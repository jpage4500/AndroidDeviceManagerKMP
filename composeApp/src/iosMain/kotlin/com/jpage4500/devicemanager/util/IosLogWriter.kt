package com.jpage4500.devicemanager.util

import co.touchlab.kermit.Severity
import platform.Foundation.NSThread

/** [AppLogWriter] over stdout, which is what Xcode's console reads. */
class IosLogWriter : AppLogWriter() {
    override fun getThreadId(): String {
        if (NSThread.isMainThread) return "UI"
        // NSThread has no portable numeric id; its hash is stable for the life of the thread, which
        // is all a two-digit tag needs
        val id = NSThread.currentThread.hash.toUInt() % 256u
        return id.toString(16).padStart(2, '0').uppercase()
    }

    override fun logToConsole(
        timeStr: String,
        severity: Severity,
        threadId: String,
        tag: String,
        message: String,
    ) {
        println("$timeStr [${getSeverityDesc(severity)}] $threadId $tag: $message")
    }
}
