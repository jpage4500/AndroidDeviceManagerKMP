package com.jpage4500.devicemanager.adb.pairing

import com.jpage4500.devicemanager.adb.device.*

/**
 * refused, and there is no version of this that works.
 *
 * Neither Network.framework nor SecureTransport exposes RFC 5705 keying material, so the SPAKE2
 * password can never be assembled — the certificate half is straightforward and the exporter is
 * simply absent. iOS pairs only through an adb server somewhere else. See docs/ADB-TRANSPORTS.md.
 */
internal actual class PairingTls {
    actual suspend fun write(bytes: ByteArray) = Unit

    actual suspend fun readFully(length: Int): ByteArray = ByteArray(0)

    actual fun exportKeyingMaterial(label: String, length: Int): ByteArray? = null

    actual fun close() = Unit
}

internal actual suspend fun openPairingTls(
    host: String,
    port: Int,
    key: AdbKeyPair,
    certificateDer: ByteArray,
): PairingTls? = null

/** no exporter in any Apple TLS API, so this can never be true. */
internal actual val canPairDirectly: Boolean = false
