package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.util.*
import java.security.*
import java.security.interfaces.*
import java.security.spec.*

private val log = LoggerConfig.createLogger("AdbKey")

/**
 * desktop and android share this: both are the JVM, and `java.security` does all of it.
 *
 * Only the private key is held. An RSA private key carries the modulus, so the public half never
 * has to be stored or passed around — [modulus] is read straight off it.
 */
actual class AdbKeyPair internal constructor(private val key: RSAPrivateKey) {

    actual val modulus: ByteArray get() = key.modulus.toByteArray()

    actual val privateKey: ByteArray get() = key.encoded

    /**
     * `NONEwithRSA` — PKCS#1 v1.5 padding over the bytes handed in, with no hashing of its own.
     *
     * Which is exactly what adbd wants, once the SHA-1 `DigestInfo` header is prepended by hand.
     * `SHA1withRSA` would hash the token first and produce a signature adbd rejects.
     */
    actual fun signToken(token: ByteArray): ByteArray? = try {
        Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(SHA1_DIGEST_INFO + token)
            sign()
        }
    } catch (e: GeneralSecurityException) {
        log.e("signToken: ${e.message}")
        null
    }

    actual fun signSha256(data: ByteArray): ByteArray? = try {
        Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(data)
            sign()
        }
    } catch (e: GeneralSecurityException) {
        log.e("signSha256: ${e.message}")
        null
    }
}

/**
 * the ASN.1 that turns 20 raw bytes into a SHA-1 `DigestInfo`.
 *
 * `SEQUENCE { SEQUENCE { OID 1.3.14.3.2.26, NULL }, OCTET STRING (20) }` — fixed, because the digest
 * length is fixed, so it is a constant rather than something built.
 */
private val SHA1_DIGEST_INFO = byteArrayOf(
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
    0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
)

internal actual fun createAdbKeyPair(): AdbKeyPair? = try {
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    AdbKeyPair(generator.generateKeyPair().private as RSAPrivateKey)
} catch (e: GeneralSecurityException) {
    log.e("createAdbKeyPair: ${e.message}")
    null
}

internal actual fun restoreAdbKeyPair(der: ByteArray): AdbKeyPair? = try {
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    AdbKeyPair(key as RSAPrivateKey)
} catch (e: GeneralSecurityException) {
    log.w("restoreAdbKeyPair: ${e.message}")
    null
}
