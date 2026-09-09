package com.jpage4500.devicemanager.util

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private val log = LoggerConfig.createLogger("Gzip")

actual fun gunzip(bytes: ByteArray): ByteArray? = try {
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
} catch (e: Exception) {
    log.w("gunzip: ${bytes.size} bytes: ${e.message}")
    null
}
