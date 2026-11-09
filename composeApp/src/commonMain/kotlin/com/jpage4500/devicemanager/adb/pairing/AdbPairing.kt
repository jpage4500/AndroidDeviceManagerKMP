package com.jpage4500.devicemanager.adb.pairing

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.adb.device.*
import com.jpage4500.devicemanager.crypto.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*

private val log = LoggerConfig.createLogger("AdbPairing")

/**
 * Android 11+ wireless pairing, done here rather than by an adb server.
 *
 * The exchange, and the ordering that matters:
 *
 * 1. **TLS first**, presenting a self-signed certificate over this app's adb key
 * 2. 64 bytes of keying material are exported from that session and **appended to the pairing code**
 * 3. SPAKE2 over the result, one message each way
 * 4. the SPAKE2 key becomes an AES-128-GCM key, and each side sends its public key under it
 *
 * Step 2 is the one that surprises people: the password is *not* the six digits on screen, it is the
 * digits followed by material only this TLS session can produce. That is what stops the exchange
 * being relayed, and it is why pairing cannot be written without an exporter.
 *
 * Nothing here is Android-specific. [PairingTls] is, and it is the only reason this runs on one
 * platform.
 */
internal class AdbPairing(private val key: AdbKeyPair, private val name: String) {

    /**
     * pair with the device at [host]:[port], the address it advertises while its pairing screen is
     * open.
     *
     * @param code the pairing code — from the QR this app showed, or typed from the device's screen
     * @return the peer's own information on success
     */
    suspend fun pair(host: String, port: Int, code: String): AdbOutcome<PeerInfo> {
        val certificate = X509Self.create(key, "adb-$name") ?: return AdbOutcome.Err(
            AdbError.IO,
            "couldn't build a certificate to pair with",
        )

        val tls = openPairingTls(host, port, key, certificate) ?: return AdbOutcome.Err(
            AdbError.CONNECT_FAILED,
            "couldn't open a TLS connection to $host:$port",
        )

        return try {
            withTimeoutOrNull(TIMEOUT_MS) { exchange(tls, code) }
                ?: AdbOutcome.Err(AdbError.TIMEOUT, "$host:$port stopped answering")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("pair: $host:$port: ${e.message}")
            AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the connection dropped")
        } finally {
            tls.close()
        }
    }

    private suspend fun exchange(tls: PairingTls, code: String): AdbOutcome<PeerInfo> {
        val exported = tls.exportKeyingMaterial(EXPORT_LABEL, EXPORT_SIZE) ?: return AdbOutcome.Err(
            AdbError.IO,
            "this platform can't export TLS keying material, so it can't pair",
        )
        // the code alone is not the password; see the class comment
        val password = code.encodeToByteArray() + exported

        val random = secureRandomBytes(64) ?: return AdbOutcome.Err(
            AdbError.IO,
            "no secure random source to pair with",
        )

        // the client is alice, and the names carry their trailing NUL
        val spake = Spake2(Spake2.Role.ALICE, Spake2.ADB_CLIENT_NAME, Spake2.ADB_SERVER_NAME)
        val ourMessage = spake.generateMessage(password, random)

        writePacket(tls, TYPE_SPAKE2_MSG, ourMessage)
        val theirMessage = readPacket(tls, TYPE_SPAKE2_MSG)
            ?: return AdbOutcome.Err(AdbError.IO, "the device didn't send a SPAKE2 message")

        val keyMaterial = spake.processMessage(theirMessage) ?: return AdbOutcome.Err(
            AdbError.IO,
            "the device's SPAKE2 message wasn't a valid point",
        )
        val cipher = PairingCipher.fromKeyMaterial(keyMaterial) ?: return AdbOutcome.Err(
            AdbError.IO,
            "this platform can't do AES-128-GCM, so it can't pair",
        )

        val blob = AdbPublicKey.blob(key.modulus) ?: return AdbOutcome.Err(
            AdbError.IO,
            "the adb key isn't RSA-2048",
        )
        val ours = PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, AdbPublicKey.authPayload(blob, name))
        val sealed = cipher.encrypt(ours.encode()) ?: return AdbOutcome.Err(
            AdbError.IO,
            "couldn't encrypt this app's key",
        )

        writePacket(tls, TYPE_PEER_INFO, sealed)
        val theirSealed = readPacket(tls, TYPE_PEER_INFO)
            ?: return AdbOutcome.Err(AdbError.IO, "the device didn't send its key")

        // **the only place a wrong code is noticed.** SPAKE2 completes either way and simply agrees
        // on different keys; it is this tag that fails
        val opened = cipher.decrypt(theirSealed) ?: return AdbOutcome.Err(
            AdbError.UNAUTHORIZED,
            "the pairing code was wrong",
        )
        val peer = PeerInfo.decode(opened) ?: return AdbOutcome.Err(
            AdbError.IO,
            "the device sent ${opened.size} bytes where a peer info is ${PeerInfo.SIZE}",
        )

        log.i("exchange: paired with a peer of type ${peer.type}")
        return AdbOutcome.Ok(peer)
    }

    /** version, type, then a 4-byte **big-endian** payload size — the one big-endian field in adb. */
    private suspend fun writePacket(tls: PairingTls, type: Int, payload: ByteArray) {
        val header = ByteArray(HEADER_SIZE)
        header[0] = HEADER_VERSION.toByte()
        header[1] = type.toByte()
        for (index in 0 until 4) {
            header[2 + index] = ((payload.size shr ((3 - index) * 8)) and 0xFF).toByte()
        }
        tls.write(header)
        tls.write(payload)
    }

    /** @return the payload, or null when the header is unusable or the wrong type */
    private suspend fun readPacket(tls: PairingTls, expected: Int): ByteArray? {
        val header = tls.readFully(HEADER_SIZE)
        val version = header[0].toInt() and 0xFF
        if (version != HEADER_VERSION) {
            log.w("readPacket: header version $version, expected $HEADER_VERSION")
            return null
        }
        val type = header[1].toInt() and 0xFF
        if (type != expected) {
            log.w("readPacket: type $type, expected $expected")
            return null
        }
        var size = 0
        for (index in 0 until 4) {
            size = (size shl 8) or (header[2 + index].toInt() and 0xFF)
        }
        if (size <= 0 || size > MAX_PAYLOAD) {
            log.w("readPacket: payload size $size is not usable")
            return null
        }
        return tls.readFully(size)
    }

    private companion object {
        const val HEADER_SIZE = 6
        const val HEADER_VERSION = 1

        // adb.proto.PairingPacket.Type
        const val TYPE_SPAKE2_MSG = 0
        const val TYPE_PEER_INFO = 1

        /** adb's own cap: two peer infos. */
        const val MAX_PAYLOAD = PeerInfo.SIZE * 2

        /**
         * the exporter label, **with its trailing NUL**.
         *
         * adb passes `sizeof(kExportedKeyLabel)` for a `char[]` initialised from `"adb-label"`, so
         * the NUL is counted. The HKDF info string a few steps later excludes its own — the two
         * genuinely differ.
         */
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_SIZE = 64

        const val TIMEOUT_MS = 30_000L
    }
}

/**
 * what each side sends the other once the exchange is encrypted: a type byte and 8191 bytes of data.
 *
 * **A fixed 8192 bytes on the wire, padding included.** adb encrypts the whole C struct rather than
 * the part of it that is used, so a shorter message is not merely unusual — it is rejected for its
 * length before anything looks at the contents.
 */
internal class PeerInfo(val type: Int, val data: ByteArray) {

    /** the data as adb writes it: a NUL-terminated string, so it is read back to the first NUL. */
    fun text(): String = data.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = type.toByte()
        data.copyInto(out, 1, 0, minOf(data.size, SIZE - 1))
        return out
    }

    companion object {
        /** `1 << 13`, and the padding is part of the message. */
        const val SIZE = 8192

        /** the base64 blob plus a name, exactly as an `AUTH RSAPUBLICKEY` message carries it. */
        const val ADB_RSA_PUB_KEY = 0

        const val ADB_DEVICE_GUID = 1

        fun decode(bytes: ByteArray): PeerInfo? {
            if (bytes.size != SIZE) return null
            return PeerInfo(bytes[0].toInt() and 0xFF, bytes.copyOfRange(1, SIZE))
        }
    }
}
