package com.jpage4500.devicemanager.adb.device

import kotlin.io.encoding.*

/**
 * adb's own public-key encoding — the 524-byte blob an `AUTH RSAPUBLICKEY` message carries.
 *
 * Not any standard: it is `struct RSAPublicKey` from Android's own mincrypt, laid out for a
 * Montgomery-form verifier with no bignum library. So on top of the modulus it carries two values
 * derived from it, and adbd will not accept a key without them:
 *
 * ```
 * int      len       // words in the modulus — always 64 for RSA-2048
 * uint32   n0inv     // -1 / n[0]  mod 2^32
 * uint32   n[64]     // the modulus, least-significant word first
 * uint32   rr[64]    // (2^2048)^2 mod n — Montgomery's R^2
 * int      exponent  // 65537
 * ```
 *
 * Both derived values are computed here rather than by the platform because Kotlin/Native has no
 * big-integer type, and needing one on iOS would otherwise push the whole encoding behind an
 * `expect`. Neither actually needs general arithmetic: `n0inv` is a Newton iteration on one 32-bit
 * word, and `rr` is 4096 doublings with a conditional subtract — shift and compare, no multiply.
 */
// unsigned words throughout: `n0inv` is defined mod 2^32 and the carry out of a doubling is the
// 33rd bit, both of which a signed Int would need masking at every step to say
@OptIn(ExperimentalUnsignedTypes::class)
object AdbPublicKey {
    /** RSA-2048, which is the only size adb has ever used. */
    const val WORDS = 64
    const val MODULUS_BYTES = WORDS * 4
    const val EXPONENT = 65537
    const val BLOB_SIZE = 4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4

    /**
     * the blob for a modulus given big-endian, as every platform's crypto hands it over.
     *
     * @return null when [modulusBigEndian] isn't an RSA-2048 modulus — leading zero bytes are
     *   tolerated, since a JVM `BigInteger` adds one to keep the value unsigned
     */
    fun blob(modulusBigEndian: ByteArray, exponent: Int = EXPONENT): ByteArray? {
        val trimmed = modulusBigEndian.dropWhile { it == 0.toByte() }.toByteArray()
        if (trimmed.size != MODULUS_BYTES) return null

        val n = wordsOf(trimmed)
        // an even modulus has no inverse mod 2^32, and nothing downstream would say why
        if (n[0] and 1u == 0u) return null

        val out = ByteArray(BLOB_SIZE)
        var at = 0
        AdbMessage.writeLe(out, at, WORDS)
        at += 4
        AdbMessage.writeLe(out, at, n0inv(n[0]).toInt())
        at += 4
        n.forEach {
            AdbMessage.writeLe(out, at, it.toInt())
            at += 4
        }
        rr(n).forEach {
            AdbMessage.writeLe(out, at, it.toInt())
            at += 4
        }
        AdbMessage.writeLe(out, at, exponent)
        return out
    }

    /**
     * the payload of an `AUTH RSAPUBLICKEY` message: the blob in base64, a name, and a NUL.
     *
     * The name is what the device's "Allow debugging?" dialog shows, and what it writes beside the
     * key in `/data/misc/adb/adb_keys` — so it wants to identify this install rather than the phone
     * it runs on. **The trailing NUL is not optional**: adbd reads the payload as a C string.
     */
    fun authPayload(blob: ByteArray, name: String): ByteArray =
        (Base64.encode(blob) + " " + name + "\u0000").encodeToByteArray()

    /**
     * the modulus out of a PKCS#1 `RSAPublicKey`: `SEQUENCE { INTEGER n, INTEGER e }`.
     *
     * Here because iOS hands a public key over as exactly this DER and offers no accessor for the
     * modulus on its own, while [blob] needs the modulus and nothing else. Just enough DER to walk
     * two tags — a general parser would be more code than the format it is reading.
     *
     * @return the modulus big-endian, or null when [der] isn't that structure
     */
    fun modulusOfPkcs1(der: ByteArray): ByteArray? {
        var at = 0

        fun length(): Int {
            if (at >= der.size) return -1
            val first = der[at++].toInt() and 0xFF
            // short form is the length itself; long form is "how many bytes of length follow"
            if (first < 0x80) return first
            val count = first and 0x7F
            if (count == 0 || count > 4 || at + count > der.size) return -1
            var value = 0
            repeat(count) { value = (value shl 8) or (der[at++].toInt() and 0xFF) }
            return value
        }

        if (der.isEmpty() || der[at++] != 0x30.toByte()) return null
        if (length() < 0) return null
        if (at >= der.size || der[at++] != 0x02.toByte()) return null
        val size = length()
        if (size <= 0 || at + size > der.size) return null
        // DER keeps an INTEGER signed, so a modulus with a high top bit carries a leading zero
        return der.copyOfRange(at, at + size).dropWhile { it == 0.toByte() }.toByteArray()
    }

    /** big-endian bytes to least-significant-word-first 32-bit words. */
    private fun wordsOf(bigEndian: ByteArray): UIntArray {
        val words = UIntArray(WORDS)
        for (index in 0 until WORDS) {
            val at = bigEndian.size - 4 * (index + 1)
            words[index] = ((bigEndian[at].toUInt() and 0xFFu) shl 24) or
                ((bigEndian[at + 1].toUInt() and 0xFFu) shl 16) or
                ((bigEndian[at + 2].toUInt() and 0xFFu) shl 8) or
                (bigEndian[at + 3].toUInt() and 0xFFu)
        }
        return words
    }

    /**
     * `-1 / word mod 2^32`, by Newton iteration.
     *
     * each step doubles the number of correct bits — 1, 2, 4, 8, 16, 32 — so five are enough for a
     * 32-bit word, and the negation at the end is what makes it adb's `n0inv` rather than a plain
     * inverse.
     */
    private fun n0inv(word: UInt): UInt {
        var inverse = 1u
        repeat(5) { inverse *= 2u - word * inverse }
        return 0u - inverse
    }

    /**
     * `(2^2048)^2 mod n`, as 64 words.
     *
     * 2^4096 reached by doubling from 1, reducing whenever the value reaches [n]. Doubling something
     * already below [n] lands below `2n`, so a single conditional subtract always suffices — which
     * is the whole reason this needs no division.
     */
    private fun rr(n: UIntArray): UIntArray {
        val value = UIntArray(WORDS)
        value[0] = 1u
        repeat(MODULUS_BYTES * 8 * 2) {
            var carry = 0u
            for (index in 0 until WORDS) {
                val shifted = (value[index] shl 1) or carry
                carry = value[index] shr 31
                value[index] = shifted
            }
            // a set carry means the doubled value passed 2^2048, so it is certainly at least n; the
            // borrow out of the subtraction is what cancels that bit again
            if (carry != 0u || compare(value, n) >= 0) subtract(value, n)
        }
        return value
    }

    private fun compare(left: UIntArray, right: UIntArray): Int {
        for (index in WORDS - 1 downTo 0) {
            if (left[index] != right[index]) return if (left[index] < right[index]) -1 else 1
        }
        return 0
    }

    /** `left -= right`, in place. */
    private fun subtract(left: UIntArray, right: UIntArray) {
        var borrow = 0uL
        for (index in 0 until WORDS) {
            val difference = left[index].toULong() - right[index].toULong() - borrow
            left[index] = difference.toUInt()
            borrow = if (difference shr 32 != 0uL) 1uL else 0uL
        }
    }
}
