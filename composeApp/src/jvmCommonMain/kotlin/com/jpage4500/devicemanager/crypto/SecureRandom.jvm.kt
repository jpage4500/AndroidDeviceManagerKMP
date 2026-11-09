package com.jpage4500.devicemanager.crypto

import com.jpage4500.devicemanager.util.*

private val log = LoggerConfig.createLogger("SecureRandom")

// one instance: SecureRandom seeds itself on first use, and a new one per call pays that repeatedly
private val generator by lazy { java.security.SecureRandom() }

internal actual fun secureRandomBytes(size: Int): ByteArray? = try {
    ByteArray(size).also { generator.nextBytes(it) }
} catch (e: Exception) {
    log.e("secureRandomBytes: ${e.message}")
    null
}
