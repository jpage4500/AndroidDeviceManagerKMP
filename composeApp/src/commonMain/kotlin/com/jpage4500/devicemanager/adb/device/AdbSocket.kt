package com.jpage4500.devicemanager.adb.device

/**
 * the socket the device protocol runs over, which on some platforms can become TLS mid-stream.
 *
 * A seam that exists for one message. `adbd` with wireless debugging on answers the opening `CNXN`
 * with `A_STLS`, and the upgrade that follows happens **on the same connection**: the two ends
 * exchange `A_STLS` in plain, then a TLS handshake starts on the bytes immediately after. There is
 * no second port and no reconnect, so a socket that cannot be wrapped in place cannot take that
 * route at all — which is why this is an `expect` rather than [com.jpage4500.devicemanager.adb.AdbStream]
 * everywhere.
 *
 * Android wraps a `java.net.Socket`, because `SSLSocketFactory` can only upgrade one of those.
 * Desktop and iOS keep the ktor socket and answer false to [canStartTls]: a desktop reaches
 * wireless-debugging devices through its own adb server, and iOS has no keying-material exporter, so
 * neither can be paired with by this app in the first place.
 */
internal expect class AdbSocket {
    suspend fun write(bytes: ByteArray)

    /** exactly [length] bytes, or a failure — every read in the device protocol is framed. */
    suspend fun readFully(length: Int): ByteArray

    /** whether [startTls] can do anything, checked before `A_STLS` is answered rather than after. */
    val canStartTls: Boolean

    /**
     * turn this connection into TLS, presenting [certificateDer] as the client certificate.
     *
     * Called immediately after the `A_STLS` exchange and before anything else is read or written.
     *
     * @return false when the handshake failed or the platform can't
     */
    suspend fun startTls(key: AdbKeyPair, certificateDer: ByteArray): Boolean

    fun close()
}

/** @return null when nothing is listening */
internal expect suspend fun openAdbSocket(host: String, port: Int, connectMs: Long): AdbSocket?
