package com.jpage4500.devicemanager.crypto

import com.jpage4500.devicemanager.util.*
import java.security.*
import javax.crypto.*
import javax.crypto.spec.*

private val log = LoggerConfig.createLogger("PairingCipher")

/**
 * desktop and android share these: both are the JVM, and `javax.crypto` has all three.
 *
 * HKDF is not in the JCE below Java 21, so it is expanded by hand from `Mac` — which is all HKDF is:
 * one HMAC to extract, then a counter-driven chain to expand. RFC 5869, and short enough that
 * depending on a library for it would cost more than it saves.
 */
internal actual fun hkdfSha256(keyMaterial: ByteArray, info: ByteArray, length: Int): ByteArray? = try {
    val mac = Mac.getInstance("HmacSHA256")

    // extract: an all-zero salt of the hash length, which is what adb passes as `nullptr, 0`
    mac.init(SecretKeySpec(ByteArray(mac.macLength), "HmacSHA256"))
    val prk = mac.doFinal(keyMaterial)

    // expand: T(n) = HMAC(prk, T(n-1) || info || n), concatenated until `length` bytes
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var written = 0
    var counter = 1
    while (written < length) {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        previous = mac.doFinal()
        val take = minOf(previous.size, length - written)
        previous.copyInto(out, written, 0, take)
        written += take
        counter++
    }
    out
} catch (e: GeneralSecurityException) {
    log.e("hkdfSha256: ${e.message}")
    null
}

internal actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray? =
    gcm(Cipher.ENCRYPT_MODE, key, nonce, plaintext)

internal actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? =
    gcm(Cipher.DECRYPT_MODE, key, nonce, ciphertext)

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray? = try {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    // 128-bit tag, stated rather than assumed: it is BoringSSL's default for AES-GCM and the JCE's
    // default has differed by platform
    cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(PairingCipher.TAG_SIZE * 8, nonce))
    cipher.doFinal(input)
} catch (e: AEADBadTagException) {
    // the expected failure rather than an error: a wrong pairing code lands exactly here
    log.d("gcm: the tag didn't verify")
    null
} catch (e: GeneralSecurityException) {
    log.e("gcm: ${e.message}")
    null
}
