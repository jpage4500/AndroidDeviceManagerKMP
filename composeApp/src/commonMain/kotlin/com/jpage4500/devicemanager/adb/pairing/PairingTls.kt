package com.jpage4500.devicemanager.adb.pairing

import com.jpage4500.devicemanager.adb.device.*

/**
 * the TLS connection a pairing handshake runs inside, and the one platform seam pairing needs.
 *
 * Two things it must do that an ordinary TLS client does not:
 *
 * 1. **present a client certificate** — the peer's whole purpose is to learn the public key inside it
 * 2. **export keying material** (RFC 5705), because adb mixes 64 bytes of it into the SPAKE2 password
 *
 * The second is the entire reason pairing is Android-only. `SSLSocket` has no exporter in the JDK
 * and Apple exposes none at all; Android's TLS *is* Conscrypt, whose `exportKeyingMaterial` is
 * reachable by reflection. See docs/ADB-TRANSPORTS.md.
 *
 * **Any server certificate is accepted, and that is correct here.** adb does the same — its own
 * comment is "Allow any peer certificate" — because the authentication comes from the password, not
 * from the certificate. The exporter is what makes that safe: mixing this session's keying material
 * into the password binds the exchange to *this* TLS connection, so a relayed one derives a
 * different key and fails.
 */
internal expect class PairingTls {
    suspend fun write(bytes: ByteArray)

    /** exactly [length] bytes, or a failure — every read in the pairing protocol is framed. */
    suspend fun readFully(length: Int): ByteArray

    /**
     * RFC 5705 keying material.
     *
     * @return null where the platform has no exporter, which a caller must report rather than work
     *   around — there is nothing to fall back to
     */
    fun exportKeyingMaterial(label: String, length: Int): ByteArray?

    fun close()
}

/**
 * connect and hand over [certificateDer] during the handshake.
 *
 * @return null where TLS with a client certificate can't be done, or the connection failed
 */
internal expect suspend fun openPairingTls(
    host: String,
    port: Int,
    key: AdbKeyPair,
    certificateDer: ByteArray,
): PairingTls?

/**
 * whether this app can pair with a device by itself, with no adb server anywhere.
 *
 * True only where [PairingTls] can export keying material, which today means Android. Checked
 * before the QR is offered rather than after someone has scanned it — see `PairingSession`.
 */
internal expect val canPairDirectly: Boolean
