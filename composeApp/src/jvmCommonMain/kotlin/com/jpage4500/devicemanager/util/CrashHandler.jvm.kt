package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.manager.*

private val log = LoggerConfig.createLogger("CrashHandler")

/**
 * the JVM hook, on both desktop and android.
 *
 * chains rather than replaces: the previous handler is what actually kills the process (and on
 * android puts up the crash dialog), so recording has to happen in front of it, not instead of it.
 */
actual fun installCrashHandler() {
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        if (isRecoverable(throwable)) {
            // logged only, so it still reaches an uploaded log without going through the crash flow
            log.w("installCrashHandler: recovered from non-fatal ${throwable::class.simpleName} on '${thread.name}': ${throwable.message}")
            return@setDefaultUncaughtExceptionHandler
        }

        LogManager.recordCrash(throwable, thread.name)
        prev?.uncaughtException(thread, throwable)
    }
}

/**
 * true for uncaught errors the app survives without tearing itself down.
 *
 * the concrete case: something running inside our process broadcasts
 * android.intent.action.CLOSE_SYSTEM_DIALOGS to collapse the notification shade. since android 12
 * that broadcast needs a signature|privileged permission, so ActivityManager throws SecurityException
 * back into our process and the default handler kills the app. nothing here sends it and we can't be
 * granted the permission, so swallowing it is safe.
 */
private fun isRecoverable(throwable: Throwable): Boolean {
    var t: Throwable? = throwable
    while (t != null) {
        if (t is SecurityException && t.message?.contains("CLOSE_SYSTEM_DIALOGS") == true) return true
        t = t.cause
    }
    return false
}
