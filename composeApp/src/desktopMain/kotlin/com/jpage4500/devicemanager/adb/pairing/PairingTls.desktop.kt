package com.jpage4500.devicemanager.adb.pairing

import com.jpage4500.devicemanager.adb.device.*

/**
 * refused, because a desktop has no RFC 5705 exporter and does not need one.
 *
 * `SSLSocket` would do the certificate half perfectly well, but the JDK has never exposed keying
 * material export — Conscrypt could be added as a dependency for it, at ~8 MB, to serve a machine
 * that already has a real `adb` doing this properly. `AdbServerTransport` pairs through that.
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

/** no exporter in the JDK, and a desktop pairs through its own adb server. */
internal actual val canPairDirectly: Boolean = false
