package com.jpage4500.devicemanager.crypto

/**
 * SPAKE2 over edwards25519, byte-compatible with BoringSSL's `spake25519.c`.
 *
 * A password-authenticated key exchange: two peers who share a short secret — here the code in a QR
 * — end up with the same 64-byte key, and someone watching the exchange learns nothing that lets
 * them guess the code offline. Android's wireless debugging pairing is this, then AES-GCM under the
 * key it produces.
 *
 * **Byte-compatible with BoringSSL, not with the SPAKE2 paper.** The far end is `adbd`, which uses
 * BoringSSL, so every choice here is theirs: the two fixed points, the transcript order and its
 * 8-byte little-endian length prefixes, the password hashed to a scalar with SHA-512, and the
 * private scalar multiplied by the cofactor. The paper would allow other answers; the wire will not.
 *
 * The one that reads like a mistake and isn't: the private scalar is reduced mod the group order and
 * *then* multiplied by 8. That is BoringSSL clearing the cofactor here so it doesn't have to when it
 * operates on the peer's point later — `left_shift_3` in their source, and a scalar up to 2^255
 * rather than under the order is fine for a multiplication.
 *
 * @param role which side this is; it decides the point used to mask and the transcript order
 * @param myName this side's name, **including any trailing NUL** the peer expects — adb passes
 *   `sizeof("adb pair client")`, so the NUL is part of the hashed name
 */
internal class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
) {
    enum class Role { ALICE, BOB }

    private var privateScalar: BigUInt = BigUInt.ZERO
    private var passwordScalar: BigUInt = BigUInt.ZERO

    /** the full 64-byte SHA-512 of the password, which goes into the transcript unreduced. */
    private var passwordHash: ByteArray = ByteArray(0)

    private var myMessage: ByteArray = ByteArray(0)

    /**
     * this side's message, to send to the peer.
     *
     * [random] is 64 bytes of entropy, reduced mod the group order — 64 rather than 32 so the
     * reduction is unbiased, which is why BoringSSL asks for that much too.
     */
    fun generateMessage(password: ByteArray, random: ByteArray): ByteArray {
        require(random.size == 64) { "SPAKE2 needs 64 bytes of entropy" }

        // reduced, then tripled-shifted: exactly BoringSSL's `left_shift_3`, i.e. times the cofactor
        privateScalar = BigUInt.fromLeBytes(random).mod(Ed25519.L).shl1().shl1().shl1()

        passwordHash = Sha512.hash(password)
        passwordScalar = BigUInt.fromLeBytes(passwordHash).mod(Ed25519.L)

        val p = Ed25519.multiplyBase(privateScalar)
        val mask = Ed25519.multiply(passwordScalar, if (role == Role.ALICE) Ed25519.M else Ed25519.N)
        myMessage = Ed25519.encode(Ed25519.add(p, mask))
        return myMessage
    }

    /**
     * the shared key, or null when [theirMessage] isn't a point on the curve.
     *
     * A wrong password does **not** land here — both sides complete the exchange and simply end up
     * with different keys, which is the whole shape of a PAKE. It is the AES-GCM step afterwards
     * that fails, and that is where "wrong code" is reported from.
     */
    fun processMessage(theirMessage: ByteArray): ByteArray? {
        require(myMessage.isNotEmpty()) { "generateMessage must be called first" }
        if (theirMessage.size != 32) return null

        val qStar = Ed25519.decode(theirMessage) ?: return null
        val peerMask = Ed25519.multiply(
            passwordScalar,
            if (role == Role.ALICE) Ed25519.N else Ed25519.M,
        )
        val q = Ed25519.subtract(qStar, peerMask)
        val shared = Ed25519.encode(Ed25519.multiply(privateScalar, q))

        // the transcript is always alice's name, bob's name, alice's message, bob's message — so
        // each side orders its own and the peer's according to which it is
        val sha = Sha512()
        if (role == Role.ALICE) {
            sha.withLength(myName)
            sha.withLength(theirName)
            sha.withLength(myMessage)
            sha.withLength(theirMessage)
        } else {
            sha.withLength(theirName)
            sha.withLength(myName)
            sha.withLength(theirMessage)
            sha.withLength(myMessage)
        }
        sha.withLength(shared)
        sha.withLength(passwordHash)
        return sha.digest()
    }

    /** every piece of the transcript carries its length first, as 8 little-endian bytes. */
    private fun Sha512.withLength(data: ByteArray) {
        val length = ByteArray(8)
        for (index in 0 until 8) {
            length[index] = ((data.size.toLong() shr (index * 8)) and 0xFF).toByte()
        }
        update(length)
        update(data)
    }

    companion object {
        /** the names adb uses, NUL included — `sizeof` on a C string literal counts it. */
        val ADB_CLIENT_NAME = "adb pair client\u0000".encodeToByteArray()
        val ADB_SERVER_NAME = "adb pair server\u0000".encodeToByteArray()
    }
}
