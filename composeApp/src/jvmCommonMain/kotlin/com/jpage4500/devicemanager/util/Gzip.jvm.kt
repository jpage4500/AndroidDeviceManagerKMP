package com.jpage4500.devicemanager.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream

private val log = LoggerConfig.createLogger("Gzip")

actual fun gunzip(bytes: ByteArray): ByteArray? = try {
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
} catch (e: Exception) {
    log.w("gunzip: ${bytes.size} bytes: ${e.message}")
    null
}

actual fun zlibDeflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    return try {
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / 8 + 64)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    } finally {
        deflater.end()
    }
}
