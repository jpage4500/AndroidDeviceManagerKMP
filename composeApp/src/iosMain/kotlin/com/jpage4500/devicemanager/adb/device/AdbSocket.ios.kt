package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.adb.*

/**
 * the ktor socket, with no way to become TLS.
 *
 * ktor's socket cannot be upgraded in place on native, and it would not help if it could: iOS has no
 * TLS keying-material exporter, so it can never pair with a device and never holds a certificate
 * that one would accept. See docs/ADB-TRANSPORTS.md.
 *
 * So this is the plain-port path and nothing else: a device on `adb tcpip 5555` works, and one with
 * wireless debugging on is refused with a reason rather than hung.
 */
internal actual class AdbSocket internal constructor(private val stream: AdbStream) {

    actual suspend fun write(bytes: ByteArray) {
        stream.write(bytes)
        stream.flush()
    }

    actual suspend fun readFully(length: Int): ByteArray = stream.readFully(length)

    actual val canStartTls: Boolean get() = false

    actual suspend fun startTls(key: AdbKeyPair, certificateDer: ByteArray): Boolean = false

    actual fun close() = stream.close()
}

internal actual suspend fun openAdbSocket(host: String, port: Int, connectMs: Long): AdbSocket? =
    AdbStream.openOrNull(host, port, connectMs)?.let { AdbSocket(it) }
