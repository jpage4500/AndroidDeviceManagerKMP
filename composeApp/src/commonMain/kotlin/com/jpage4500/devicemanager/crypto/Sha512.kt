package com.jpage4500.devicemanager.crypto

/**
 * SHA-512, in `commonMain`.
 *
 * Written rather than reached for because SPAKE2 needs it on every target: the JVM has
 * `MessageDigest` and iOS has CommonCrypto, but putting a digest behind `expect`/`actual` would put
 * the *protocol* behind it too — [Spake2] hashes a transcript incrementally, so the seam would have
 * to carry a streaming API for three platforms. FIPS 180-4 is 120 lines; the seam is more.
 *
 * Streaming, because the SPAKE2 transcript is a series of length-prefixed pieces and buffering the
 * whole thing to hash it once would be the same code with an extra copy.
 */
// the state and round constants are 64-bit unsigned; a signed Long would need masking on every
// shift, which is the one place a digest quietly goes wrong
@OptIn(ExperimentalUnsignedTypes::class)
internal class Sha512 {
    private val state = INITIAL.copyOf()
    private val block = ByteArray(BLOCK)
    private var blockUsed = 0
    private var totalBytes = 0L

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Sha512 {
        var at = offset
        var left = length
        totalBytes += length
        while (left > 0) {
            val take = minOf(left, BLOCK - blockUsed)
            bytes.copyInto(block, blockUsed, at, at + take)
            blockUsed += take
            at += take
            left -= take
            if (blockUsed == BLOCK) {
                compress(block)
                blockUsed = 0
            }
        }
        return this
    }

    /**
     * the digest, after the padding FIPS 180-4 asks for.
     *
     * A 0x80 byte, then zeros, then the length in **bits** as a 128-bit big-endian integer — of
     * which only the low 64 bits can ever be non-zero here, so the top eight bytes are left zero.
     */
    fun digest(): ByteArray {
        val bitLength = totalBytes * 8
        update(byteArrayOf(0x80.toByte()))
        // pad to 112 mod 128, leaving room for the 16-byte length
        val padding = if (blockUsed <= 112) 112 - blockUsed else 240 - blockUsed
        if (padding > 0) update(ByteArray(padding))
        update(ByteArray(8))
        val length = ByteArray(8)
        for (index in 0 until 8) {
            length[index] = ((bitLength shr ((7 - index) * 8)) and 0xFF).toByte()
        }
        update(length)

        val out = ByteArray(64)
        state.forEachIndexed { word, value ->
            for (byte in 0 until 8) {
                out[word * 8 + byte] = ((value shr ((7 - byte) * 8)) and 0xFFuL).toByte()
            }
        }
        return out
    }

    private fun compress(chunk: ByteArray) {
        val w = ULongArray(80)
        for (index in 0 until 16) {
            var value = 0uL
            for (byte in 0 until 8) {
                value = (value shl 8) or (chunk[index * 8 + byte].toULong() and 0xFFuL)
            }
            w[index] = value
        }
        for (index in 16 until 80) {
            val s0 = w[index - 15].rotateRight(1) xor w[index - 15].rotateRight(8) xor (w[index - 15] shr 7)
            val s1 = w[index - 2].rotateRight(19) xor w[index - 2].rotateRight(61) xor (w[index - 2] shr 6)
            w[index] = w[index - 16] + s0 + w[index - 7] + s1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (index in 0 until 80) {
            val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[index] + w[index]
            val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    companion object {
        private const val BLOCK = 128

        fun hash(bytes: ByteArray): ByteArray = Sha512().update(bytes).digest()

        private val INITIAL = ulongArrayOf(
            0x6a09e667f3bcc908uL, 0xbb67ae8584caa73buL, 0x3c6ef372fe94f82buL, 0xa54ff53a5f1d36f1uL,
            0x510e527fade682d1uL, 0x9b05688c2b3e6c1fuL, 0x1f83d9abfb41bd6buL, 0x5be0cd19137e2179uL,
        )

        private val K = ulongArrayOf(
            0x428a2f98d728ae22uL, 0x7137449123ef65cduL, 0xb5c0fbcfec4d3b2fuL, 0xe9b5dba58189dbbcuL,
            0x3956c25bf348b538uL, 0x59f111f1b605d019uL, 0x923f82a4af194f9buL, 0xab1c5ed5da6d8118uL,
            0xd807aa98a3030242uL, 0x12835b0145706fbeuL, 0x243185be4ee4b28cuL, 0x550c7dc3d5ffb4e2uL,
            0x72be5d74f27b896fuL, 0x80deb1fe3b1696b1uL, 0x9bdc06a725c71235uL, 0xc19bf174cf692694uL,
            0xe49b69c19ef14ad2uL, 0xefbe4786384f25e3uL, 0x0fc19dc68b8cd5b5uL, 0x240ca1cc77ac9c65uL,
            0x2de92c6f592b0275uL, 0x4a7484aa6ea6e483uL, 0x5cb0a9dcbd41fbd4uL, 0x76f988da831153b5uL,
            0x983e5152ee66dfabuL, 0xa831c66d2db43210uL, 0xb00327c898fb213fuL, 0xbf597fc7beef0ee4uL,
            0xc6e00bf33da88fc2uL, 0xd5a79147930aa725uL, 0x06ca6351e003826fuL, 0x142929670a0e6e70uL,
            0x27b70a8546d22ffcuL, 0x2e1b21385c26c926uL, 0x4d2c6dfc5ac42aeduL, 0x53380d139d95b3dfuL,
            0x650a73548baf63deuL, 0x766a0abb3c77b2a8uL, 0x81c2c92e47edaee6uL, 0x92722c851482353buL,
            0xa2bfe8a14cf10364uL, 0xa81a664bbc423001uL, 0xc24b8b70d0f89791uL, 0xc76c51a30654be30uL,
            0xd192e819d6ef5218uL, 0xd69906245565a910uL, 0xf40e35855771202auL, 0x106aa07032bbd1b8uL,
            0x19a4c116b8d2d0c8uL, 0x1e376c085141ab53uL, 0x2748774cdf8eeb99uL, 0x34b0bcb5e19b48a8uL,
            0x391c0cb3c5c95a63uL, 0x4ed8aa4ae3418acbuL, 0x5b9cca4f7763e373uL, 0x682e6ff3d6b2b8a3uL,
            0x748f82ee5defb2fcuL, 0x78a5636f43172f60uL, 0x84c87814a1f0ab72uL, 0x8cc702081a6439ecuL,
            0x90befffa23631e28uL, 0xa4506cebde82bde9uL, 0xbef9a3f7b2c67915uL, 0xc67178f2e372532buL,
            0xca273eceea26619cuL, 0xd186b8c721c0c207uL, 0xeada7dd6cde0eb1euL, 0xf57d4f7fee6ed178uL,
            0x06f067aa72176fbauL, 0x0a637dc5a2c898a6uL, 0x113f9804bef90daeuL, 0x1b710b35131c471buL,
            0x28db77f523047d84uL, 0x32caab7b40c72493uL, 0x3c9ebe0a15c9bebcuL, 0x431d67c49c100d4cuL,
            0x4cc5d4becb3e42b6uL, 0x597f299cfc657e2auL, 0x5fcb6fab3ad6faecuL, 0x6c44198c4a475817uL,
        )
    }
}
