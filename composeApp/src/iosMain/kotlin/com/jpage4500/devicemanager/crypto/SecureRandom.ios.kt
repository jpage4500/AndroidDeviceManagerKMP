package com.jpage4500.devicemanager.crypto

import com.jpage4500.devicemanager.util.*
import kotlinx.cinterop.*
import platform.Security.*

private val log = LoggerConfig.createLogger("SecureRandom")

/** `SecRandomCopyBytes`, which is the system generator rather than a userspace one. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray? {
    val out = ByteArray(size)
    val status = out.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
    }
    if (status != 0) {
        log.e("secureRandomBytes: SecRandomCopyBytes answered $status")
        return null
    }
    return out
}
