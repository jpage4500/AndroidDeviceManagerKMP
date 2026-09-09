package com.jpage4500.devicemanager.crypto

/**
 * an unsigned arbitrary-precision integer, with only what Ed25519 and SPAKE2 need of one.
 *
 * Kotlin/Native has no `BigInteger`, and the alternative to this is hand-packed 51-bit limbs with
 * lazy carries — the representation every fast Curve25519 uses and the one where every bug in this
 * kind of code lives. **This deliberately chooses the slow, obvious algorithms**: schoolbook
 * multiplication and bit-by-bit long division, which are short enough to read and check against a
 * reference by eye.
 *
 * That is affordable because of how little of it runs: a pairing does one scalar multiplication and
 * then never again for the life of the dialog. A few million word operations once is nothing;
 * a wrong answer is a handshake that fails with no way to tell why.
 *
 * Little-endian 32-bit limbs, normalised so the top limb is never zero (except for [ZERO] itself),
 * which is what makes [compareTo] and [bitLength] cheap and unambiguous.
 */
// unsigned limbs throughout: carries and borrows are read off the top of a 64-bit intermediate, and
// a signed type would make every one of those a masking question
@OptIn(ExperimentalUnsignedTypes::class)
internal class BigUInt private constructor(private val limbs: UIntArray) : Comparable<BigUInt> {

    val isZero: Boolean get() = limbs.isEmpty()

    val isOdd: Boolean get() = limbs.isNotEmpty() && (limbs[0] and 1u) == 1u

    /** the position of the highest set bit, plus one; 0 for zero. */
    val bitLength: Int
        get() {
            if (limbs.isEmpty()) return 0
            val top = limbs[limbs.size - 1]
            var bits = 32
            while (bits > 0 && (top shr (bits - 1)) == 0u) bits--
            return (limbs.size - 1) * 32 + bits
        }

    fun testBit(index: Int): Boolean {
        val limb = index / 32
        if (limb >= limbs.size) return false
        return ((limbs[limb] shr (index % 32)) and 1u) == 1u
    }

    override fun compareTo(other: BigUInt): Int {
        if (limbs.size != other.limbs.size) return if (limbs.size < other.limbs.size) -1 else 1
        for (index in limbs.size - 1 downTo 0) {
            if (limbs[index] != other.limbs[index]) {
                return if (limbs[index] < other.limbs[index]) -1 else 1
            }
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is BigUInt && compareTo(other) == 0

    override fun hashCode(): Int = limbs.contentHashCode()

    operator fun plus(other: BigUInt): BigUInt {
        val size = maxOf(limbs.size, other.limbs.size) + 1
        val out = UIntArray(size)
        var carry = 0uL
        for (index in 0 until size) {
            val sum = limbAt(index).toULong() + other.limbAt(index).toULong() + carry
            out[index] = sum.toUInt()
            carry = sum shr 32
        }
        return of(out)
    }

    /** `this - other`, which every caller here has already established is not negative. */
    operator fun minus(other: BigUInt): BigUInt {
        val out = UIntArray(limbs.size)
        var borrow = 0uL
        for (index in out.indices) {
            val difference = limbAt(index).toULong() - other.limbAt(index).toULong() - borrow
            out[index] = difference.toUInt()
            borrow = (difference shr 32) and 1uL
        }
        return of(out)
    }

    operator fun times(other: BigUInt): BigUInt {
        if (isZero || other.isZero) return ZERO
        val out = UIntArray(limbs.size + other.limbs.size)
        for (i in limbs.indices) {
            var carry = 0uL
            val a = limbs[i].toULong()
            for (j in other.limbs.indices) {
                val t = a * other.limbs[j].toULong() + out[i + j].toULong() + carry
                out[i + j] = t.toUInt()
                carry = t shr 32
            }
            var at = i + other.limbs.size
            while (carry != 0uL) {
                val t = out[at].toULong() + carry
                out[at] = t.toUInt()
                carry = t shr 32
                at++
            }
        }
        return of(out)
    }

    /** doubled — the one shift long division needs. */
    fun shl1(): BigUInt {
        if (isZero) return ZERO
        val out = UIntArray(limbs.size + 1)
        var carry = 0u
        for (index in limbs.indices) {
            out[index] = (limbs[index] shl 1) or carry
            carry = limbs[index] shr 31
        }
        out[limbs.size] = carry
        return of(out)
    }

    private fun withBit0(): BigUInt {
        val out = if (limbs.isEmpty()) UIntArray(1) else limbs.copyOf()
        out[0] = out[0] or 1u
        return of(out)
    }

    /**
     * `this mod modulus`, by binary long division.
     *
     * One shift, one compare and one conditional subtract per bit of [this]. For a 512-bit value
     * against a 256-bit modulus that is ~512 iterations over 8 limbs — the slow way, and the way
     * whose correctness is obvious by inspection.
     */
    fun mod(modulus: BigUInt): BigUInt {
        if (this < modulus) return this
        var remainder = ZERO
        for (index in bitLength - 1 downTo 0) {
            remainder = remainder.shl1()
            if (testBit(index)) remainder = remainder.withBit0()
            if (remainder >= modulus) remainder -= modulus
        }
        return remainder
    }

    fun addMod(other: BigUInt, modulus: BigUInt): BigUInt {
        val sum = this + other
        return if (sum >= modulus) sum - modulus else sum
    }

    fun subMod(other: BigUInt, modulus: BigUInt): BigUInt =
        if (this >= other) this - other else this + modulus - other

    fun mulMod(other: BigUInt, modulus: BigUInt): BigUInt = (this * other).mod(modulus)

    /** square-and-multiply, most significant bit first. Not constant time — see the class comment. */
    fun powMod(exponent: BigUInt, modulus: BigUInt): BigUInt {
        var result = ONE
        for (index in exponent.bitLength - 1 downTo 0) {
            result = result.mulMod(result, modulus)
            if (exponent.testBit(index)) result = result.mulMod(this, modulus)
        }
        return result
    }

    /** the inverse mod a **prime** [modulus], by Fermat: `a^(p-2)`. */
    fun invMod(modulus: BigUInt): BigUInt = powMod(modulus - TWO, modulus)

    /** little-endian, padded or truncated to [length] bytes. */
    fun toLeBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        for (index in 0 until length) {
            val limb = index / 4
            if (limb >= limbs.size) break
            out[index] = ((limbs[limb] shr ((index % 4) * 8)) and 0xFFu).toByte()
        }
        return out
    }

    private fun limbAt(index: Int): UInt = if (index < limbs.size) limbs[index] else 0u

    companion object {
        val ZERO = BigUInt(UIntArray(0))
        val ONE = fromInt(1)
        val TWO = fromInt(2)

        fun fromInt(value: Int): BigUInt = of(uintArrayOf(value.toUInt()))

        fun fromLeBytes(bytes: ByteArray): BigUInt {
            val out = UIntArray((bytes.size + 3) / 4)
            bytes.forEachIndexed { index, byte ->
                out[index / 4] = out[index / 4] or ((byte.toUInt() and 0xFFu) shl ((index % 4) * 8))
            }
            return of(out)
        }

        fun fromBeHex(hex: String): BigUInt {
            val clean = if (hex.length % 2 == 0) hex else "0$hex"
            val bytes = ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
            return fromLeBytes(bytes.reversedArray())
        }

        /** normalise: drop the leading zero limbs, so the representation of a value is unique. */
        private fun of(limbs: UIntArray): BigUInt {
            var size = limbs.size
            while (size > 0 && limbs[size - 1] == 0u) size--
            return if (size == limbs.size) BigUInt(limbs) else BigUInt(limbs.copyOf(size))
        }
    }
}

private fun ByteArray.reversedArray(): ByteArray = ByteArray(size) { this[size - 1 - it] }
