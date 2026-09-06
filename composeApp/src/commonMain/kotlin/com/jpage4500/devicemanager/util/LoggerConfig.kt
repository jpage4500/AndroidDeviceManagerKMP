package com.jpage4500.devicemanager.util

import co.touchlab.kermit.Logger

/**
 * one logger per class, tagged so a log line names the file it came from.
 *
 * every line starts with the enclosing function name — `log.d("connect: ...")` — which is what makes
 * a log readable without opening the source beside it.
 */
object LoggerConfig {
    fun createLogger(tag: String): Logger = Logger.withTag("ADM_$tag")
}
