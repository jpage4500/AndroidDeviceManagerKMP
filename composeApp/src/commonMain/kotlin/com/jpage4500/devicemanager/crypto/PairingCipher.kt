package com.jpage4500.devicemanager.crypto

/**
 * the AES-128-GCM layer adb wraps the `PeerInfo` exchange in, keyed from the SPAKE2 output.
 *
 * The **nonce is where the interesting decision is**, and it is adb's rather than a choice: twelve
 * zero bytes with a sequence counter written into the first eight, little-endian, incremented after
 * every operation — and counted *separately* for sending and receiving. Nothing is transmitted to
 * say which nonce was used, so both ends must agree by construction; a shared counter, or one that
 * failed to advance, decrypts to garbage with no clue as to why.
 *
 * The construction lives here in `commonMain`, where it can be read against
 * `pairing_auth/aes_128_gcm.cpp`, and only the two primitives are platform code — see [hkdfSha256]
 * and [aesGcmSeal].
 */
internal class PairingCipher private constructor(private val key: ByteArray) {

    private var sendSequence = 0L
    private var receiveSequence = 0L

    /** @return null when the key is wrong, which is what a mistyped pairing code looks like */
    fun encrypt(plaintext: ByteArray): ByteArray? =
        aesGcmSeal(key, nonceOf(sendSequence), plaintext)?.also { sendSequence++ }

    /**
     * @return null when authentication fails — **the only place a wrong pairing code is detected**.
     *   SPAKE2 completes happily on both sides with different keys; it is this that notices.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray? =
        aesGcmOpen(key, nonceOf(receiveSequence), ciphertext)?.also { receiveSequence++ }

    private fun nonceOf(sequence: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        for (index in 0 until 8) {
            nonce[index] = ((sequence shr (index * 8)) and 0xFF).toByte()
        }
        return nonce
    }

    companion object {
        /** GCM's nonce length; adb takes the default rather than choosing. */
        private const val NONCE_SIZE = 12

        private const val KEY_SIZE = 16

        /**
         * HKDF's `info` string, and it is **not** NUL-terminated on the wire.
         *
         * adb passes `sizeof(info) - 1` for a C array initialised from a string literal, so the
         * trailing NUL is deliberately excluded — unlike the SPAKE2 names, where it is included.
         * The two differ, and getting them the same way round is the whole of it.
         */
        private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"

        /**
         * a cipher from SPAKE2's 64 bytes of key material.
         *
         * @return null when the platform has no HKDF — the same platforms that have no exporter, so
         *   a caller that got this far has already been told pairing isn't available
         */
        fun fromKeyMaterial(keyMaterial: ByteArray): PairingCipher? {
            val key = hkdfSha256(keyMaterial, HKDF_INFO.encodeToByteArray(), KEY_SIZE) ?: return null
            return PairingCipher(key)
        }

        /** GCM's tag is appended, so the ciphertext is this much longer than the plaintext. */
        const val TAG_SIZE = 16
    }
}

/**
 * HKDF-SHA256 with an empty salt, as `HKDF(..., EVP_sha256(), ikm, len, nullptr, 0, info, len)`.
 *
 * Platform code, along with [aesGcmSeal] and [aesGcmOpen], for a reason worth stating: everything
 * else in `crypto/` is written out because the *protocol* needed it in `commonMain`, but these three
 * are ordinary primitives every platform ships a vetted implementation of, and pairing only runs
 * where TLS does. Writing AES and GHASH by hand to serve a platform that cannot pair anyway would be
 * effort spent in the one place where being wrong is hardest to notice.
 *
 * @return null where the platform has no such algorithm
 */
internal expect fun hkdfSha256(keyMaterial: ByteArray, info: ByteArray, length: Int): ByteArray?

/** AES-128-GCM, no associated data, tag appended to the ciphertext. */
internal expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray?

/** @return null when the tag doesn't verify, which is a wrong key or a tampered message */
internal expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray?
