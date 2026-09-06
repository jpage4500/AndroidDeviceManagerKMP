package com.jpage4500.devicemanager.util

import co.touchlab.kermit.*

/**
 * one logger per class, tagged so a log line names the file it came from.
 *
 * every line starts with the enclosing function name — `log.d("connect: ...")` — which is what makes
 * a log readable without opening the source beside it.
 *
 * the writer is set once at startup by whichever entry point is running ([initialize]) and every
 * logger created before that still reaches it: they all hold [delegatingWriter], which forwards to
 * whatever is current. that ordering is not theoretical — a `private val log` at the top of a file
 * is built the first time that file is touched, which for `main.kt` is before anything has run.
 */
object LoggerConfig {
    var logWriter: LogWriter? = null

    // the single writer every Logger is built with; nothing else is ever installed
    private val delegatingWriter = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            logWriter?.log(severity, message, tag, throwable)
        }
    }

    /** install the platform's writer — call from `main()`, `MainActivity` or `MainViewController`. */
    fun initialize(logWriter: LogWriter? = null) {
        this.logWriter = logWriter
    }

    fun createLogger(tag: String): Logger =
        Logger(config = StaticConfig(logWriterList = listOf(delegatingWriter)), tag = tag)
}
