package com.jpage4500.devicemanager.crypto

/**
 * the edwards25519 group, as much of it as SPAKE2 needs: decode, encode, add, subtract, multiply.
 *
 * Not a signature implementation — nothing here signs or verifies. It exists because
 * [Spake2] does arithmetic *on the group*, which no platform exposes: the JVM and iOS will both do
 * Ed25519 signatures, and neither will add two points for you.
 *
 * Points are kept in **extended coordinates** `(X, Y, Z, T)` with `x = X/Z`, `y = Y/Z` and
 * `T = XY/Z`, and added with the unified formulas from Hisil–Wong–Carter–Dawson — unified meaning
 * the same formula doubles a point as adds two, so there is no special case to get wrong. Field
 * arithmetic is [BigUInt] mod [P]: slow and legible, which is the trade that file explains.
 *
 * **Nothing here is constant time.** It never touches a long-lived secret: SPAKE2's scalar is
 * generated for one handshake and discarded, and the threat this protocol is built against is
 * someone guessing a six-digit code, not timing a phone.
 */
internal object Ed25519 {

    /** the field: 2^255 - 19. */
    val P = BigUInt.fromBeHex("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed")

    /** the group order, which is what a scalar is reduced modulo. */
    val L = BigUInt.fromBeHex("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed")

    /** the curve constant, `-121665/121666 mod p`. */
    private val D = BigUInt.fromBeHex("52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3")

    /** `sqrt(-1) mod p`, the second candidate root [decode] tries. */
    private val SQRT_M1 = BigUInt.fromBeHex("2b8324804fc1df0b2b4d00993dfbd7a72f431806ad2fe478c4ee1b274a0ea0b0")

    /** `(p + 3) / 8`, the exponent that gives a square root mod this p. */
    private val SQRT_EXPONENT = BigUInt.fromBeHex("0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe")

    /**
     * the base point, decoded from its standard encoding rather than written out as coordinates.
     *
     * Which makes it a check on [decode] as much as a constant: a broken decoder cannot produce a
     * base point that satisfies the RFC 8032 test vectors.
     */
    val B: Point by lazy {
        decode(hexToLeBytes("5866666666666666666666666666666666666666666666666666666666666666"))
            ?: error("the base point failed to decode")
    }

    /**
     * SPAKE2's two fixed points, from `spake25519.c`.
     *
     * Their decimal coordinates are in that file's comments, which is what makes them checkable
     * independently of the encoding being read correctly.
     */
    val M: Point by lazy {
        decode(hexToLeBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
            ?: error("M failed to decode")
    }

    val N: Point by lazy {
        decode(hexToLeBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))
            ?: error("N failed to decode")
    }

    /** the identity, `(0, 1)`. */
    val ZERO = Point(BigUInt.ZERO, BigUInt.ONE, BigUInt.ONE, BigUInt.ZERO)

    class Point(
        val x: BigUInt,
        val y: BigUInt,
        val z: BigUInt,
        val t: BigUInt,
    ) {
        /** the affine coordinates, which is the only place an inversion is needed. */
        fun affine(): Pair<BigUInt, BigUInt> {
            val inverse = z.invMod(P)
            return x.mulMod(inverse, P) to y.mulMod(inverse, P)
        }

        fun negate(): Point = Point(P.subMod(x, P), y, z, P.subMod(t, P))
    }

    /**
     * `a + b`, by the unified extended-coordinate formulas.
     *
     * Unified is the point: the same eight multiplications add two different points and double one,
     * so there is no `if (a == b)` branch here to be wrong on the one input that exercises it.
     */
    fun add(a: Point, b: Point): Point {
        val aa = a.y.subMod(a.x, P).mulMod(b.y.subMod(b.x, P), P)
        val bb = a.y.addMod(a.x, P).mulMod(b.y.addMod(b.x, P), P)
        val cc = a.t.mulMod(D.addMod(D, P), P).mulMod(b.t, P)
        val dd = a.z.mulMod(b.z.addMod(b.z, P), P)
        val e = bb.subMod(aa, P)
        val f = dd.subMod(cc, P)
        val g = dd.addMod(cc, P)
        val h = bb.addMod(aa, P)
        return Point(
            x = e.mulMod(f, P),
            y = g.mulMod(h, P),
            z = f.mulMod(g, P),
            t = e.mulMod(h, P),
        )
    }

    fun subtract(a: Point, b: Point): Point = add(a, b.negate())

    /** `scalar * point`, double-and-add from the top bit down. */
    fun multiply(scalar: BigUInt, point: Point): Point {
        var result = ZERO
        for (index in scalar.bitLength - 1 downTo 0) {
            result = add(result, result)
            if (scalar.testBit(index)) result = add(result, point)
        }
        return result
    }

    fun multiplyBase(scalar: BigUInt): Point = multiply(scalar, B)

    /** the 32-byte encoding: `y` little-endian, with the low bit of `x` in the top bit. */
    fun encode(point: Point): ByteArray {
        val (x, y) = point.affine()
        val out = y.toLeBytes(32)
        if (x.isOdd) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /**
     * the point [bytes] encodes, or null when it isn't one.
     *
     * Null is a real answer and a caller must act on it: a peer's SPAKE2 message is 32 arbitrary
     * bytes off a socket, and "not on the curve" is exactly what a tampered one looks like.
     */
    fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val stripped = bytes.copyOf()
        val xOdd = (stripped[31].toInt() and 0x80) != 0
        stripped[31] = (stripped[31].toInt() and 0x7F).toByte()

        val y = BigUInt.fromLeBytes(stripped)
        // a y at or past the field size is a non-canonical encoding, which is not a point
        if (y >= P) return null

        // x^2 = (y^2 - 1) / (d * y^2 + 1)
        val ySquared = y.mulMod(y, P)
        val u = ySquared.subMod(BigUInt.ONE, P)
        val v = D.mulMod(ySquared, P).addMod(BigUInt.ONE, P)
        if (v.isZero) return null
        val ratio = u.mulMod(v.invMod(P), P)

        var x = ratio.powMod(SQRT_EXPONENT, P)
        if (x.mulMod(x, P) != ratio) {
            x = x.mulMod(SQRT_M1, P)
            // no square root means y is not the ordinate of any point on the curve
            if (x.mulMod(x, P) != ratio) return null
        }
        // both roots are valid points; the encoding's top bit says which was meant
        if (x.isOdd != xOdd) x = P.subMod(x, P)
        // x = 0 with the sign bit set is the other non-canonical encoding
        if (x.isZero && xOdd) return null

        return Point(x, y, BigUInt.ONE, x.mulMod(y, P))
    }

    private fun hexToLeBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
