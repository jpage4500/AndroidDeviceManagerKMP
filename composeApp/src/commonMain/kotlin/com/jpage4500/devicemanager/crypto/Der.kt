package com.jpage4500.devicemanager.crypto

/**
 * just enough DER to write one X.509 certificate.
 *
 * Hand-written because the JDK has no public API for *building* a certificate — `sun.security.x509`
 * is not exported, Android's BouncyCastle is a hidden package, and adding BouncyCastle for one
 * self-signed cert is 4 MB to write about 200 bytes. iOS would need its own answer either way.
 *
 * Writing DER is much easier than reading it: everything here is tag-length-value with a definite
 * length, built innermost-first, and there is no ambiguity to resolve or malformed input to defend
 * against — the only consumer of the output is a TLS stack that will reject anything wrong.
 */
internal object Der {
    const val TAG_INTEGER = 0x02
    const val TAG_BIT_STRING = 0x03
    const val TAG_OCTET_STRING = 0x04
    const val TAG_NULL = 0x05
    const val TAG_OID = 0x06
    const val TAG_UTF8_STRING = 0x0C
    const val TAG_UTC_TIME = 0x17
    const val TAG_SEQUENCE = 0x30
    const val TAG_SET = 0x31

    /** tag, length, then [content]. */
    fun encode(tag: Int, content: ByteArray): ByteArray {
        val length = lengthOf(content.size)
        val out = ByteArray(1 + length.size + content.size)
        out[0] = tag.toByte()
        length.copyInto(out, 1)
        content.copyInto(out, 1 + length.size)
        return out
    }

    fun sequence(vararg parts: ByteArray): ByteArray = encode(TAG_SEQUENCE, concat(*parts))

    fun set(vararg parts: ByteArray): ByteArray = encode(TAG_SET, concat(*parts))

    /**
     * a non-negative INTEGER from big-endian bytes.
     *
     * DER integers are **signed**, so a value whose top bit is set needs a leading zero or it reads
     * as negative — which is what turns a valid modulus into a rejected certificate.
     */
    fun integer(bigEndian: ByteArray): ByteArray {
        val trimmed = bigEndian.dropWhile { it == 0.toByte() }.toByteArray()
        if (trimmed.isEmpty()) return encode(TAG_INTEGER, byteArrayOf(0))
        val content =
            if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        return encode(TAG_INTEGER, content)
    }

    fun integer(value: Int): ByteArray {
        val bytes = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte(),
        )
        return integer(bytes)
    }

    /** an OID from its already-encoded content bytes, which is how the constants below carry them. */
    fun oid(content: ByteArray): ByteArray = encode(TAG_OID, content)

    fun nullValue(): ByteArray = encode(TAG_NULL, ByteArray(0))

    /** a BIT STRING with no unused trailing bits, which is every case here. */
    fun bitString(content: ByteArray): ByteArray = encode(TAG_BIT_STRING, byteArrayOf(0) + content)

    fun octetString(content: ByteArray): ByteArray = encode(TAG_OCTET_STRING, content)

    fun utf8String(value: String): ByteArray = encode(TAG_UTF8_STRING, value.encodeToByteArray())

    fun utcTime(value: String): ByteArray = encode(TAG_UTC_TIME, value.encodeToByteArray())

    /** `[n] EXPLICIT` — a constructed context-specific tag wrapping [content]. */
    fun explicit(tag: Int, content: ByteArray): ByteArray = encode(0xA0 or tag, content)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach {
            it.copyInto(out, at)
            at += it.size
        }
        return out
    }

    /** short form under 128, otherwise a byte count followed by the length big-endian. */
    private fun lengthOf(length: Int): ByteArray {
        if (length < 0x80) return byteArrayOf(length.toByte())
        var bytes = 0
        var remaining = length
        while (remaining > 0) {
            bytes++
            remaining = remaining shr 8
        }
        val out = ByteArray(1 + bytes)
        out[0] = (0x80 or bytes).toByte()
        for (index in 0 until bytes) {
            out[bytes - index] = ((length shr (index * 8)) and 0xFF).toByte()
        }
        return out
    }

    // the three object identifiers a self-signed RSA certificate needs, as content bytes
    /** 1.2.840.113549.1.1.1 — rsaEncryption. */
    val OID_RSA_ENCRYPTION = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01)

    /** 1.2.840.113549.1.1.11 — sha256WithRSAEncryption. */
    val OID_SHA256_WITH_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)

    /** 2.5.4.3 — commonName. */
    val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
}
