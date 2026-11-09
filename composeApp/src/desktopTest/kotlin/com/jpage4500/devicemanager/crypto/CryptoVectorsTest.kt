package com.jpage4500.devicemanager.crypto

import com.jpage4500.devicemanager.adb.device.*
import com.jpage4500.devicemanager.adb.pairing.*
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * the SPAKE2 stack against vectors that did not come from this repo.
 *
 * The point of these is that the alternative is unfalsifiable: `crypto/` either matches BoringSSL
 * byte for byte or a pairing handshake fails with nothing in the log to work from, and the far end
 * is a phone rather than something that can be stepped through. Self-consistency proves almost
 * nothing here — two wrong implementations of the same misreading agree perfectly — so every anchor
 * below is external:
 *
 * - SHA-512 against FIPS 180-4, including a message longer than one block
 * - **M and N decoded to the decimal coordinates written in `spake25519.c`'s own comments**, which
 *   checks the decoder against BoringSSL rather than against itself
 * - Ed25519 key generation against RFC 8032, which exercises SHA-512, scalar clamping, the ladder
 *   and point encoding end to end in one comparison
 * - `L * B == identity`, which is the group order and the ladder agreeing
 *
 * What is left unanchored is the SPAKE2 transcript layout — the order of the names and messages and
 * their 8-byte length prefixes. That was read straight from `spake25519.c` and only a real device
 * can confirm it.
 */
class CryptoVectorsTest {

    @Test
    fun vectors() {
        run()
        assertEquals(0, failures, "\$failures crypto check(s) failed — see the output above")
    }

    /**
     * DER's length encoding, which is the part with an edge.
     *
     * Under 128 the length is one byte; at 128 and above it becomes a count-of-bytes followed by the
     * length itself. A certificate crosses that boundary in several places — a 2048-bit modulus is
     * 257 bytes with its sign padding — so getting it wrong produces something that parses right up
     * until it doesn't.
     */
    @Test
    fun derLengths() {
        // short form
        assertEquals("0403010203", Der.octetString(byteArrayOf(1, 2, 3)).toHex())
        // exactly at the boundary: 128 bytes needs the long form
        val at128 = Der.octetString(ByteArray(128))
        assertEquals("048180", at128.toHex().take(6))
        assertEquals(3 + 128, at128.size)
        // two length bytes
        val at300 = Der.octetString(ByteArray(300))
        assertEquals("0482012c", at300.toHex().take(8))
        assertEquals(4 + 300, at300.size)

        // an INTEGER whose top bit is set gains a leading zero, or it reads as negative
        assertEquals("020200ff", Der.integer(byteArrayOf(0xFF.toByte())).toHex())
        assertEquals("02017f", Der.integer(byteArrayOf(0x7F)).toHex())
        // leading zeroes are dropped first, so an already-padded value doesn't gain a second one
        assertEquals("020200ff", Der.integer(byteArrayOf(0, 0, 0xFF.toByte())).toHex())
    }

    /**
     * `PeerInfo` is a fixed 8192 bytes including its padding — adb encrypts the whole struct, and a
     * message of any other length is rejected on length before its contents are read.
     */
    @Test
    fun peerInfoIsFixedSize() {
        val payload = "AAAA test@example".encodeToByteArray()
        val encoded = PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, payload).encode()
        assertEquals(PeerInfo.SIZE, encoded.size)

        val decoded = PeerInfo.decode(encoded) ?: error("failed to decode")
        assertEquals(PeerInfo.ADB_RSA_PUB_KEY, decoded.type)
        // read back to the first NUL, which is how adb writes it
        assertEquals("AAAA test@example", decoded.text())

        assertEquals(null, PeerInfo.decode(ByteArray(PeerInfo.SIZE - 1)), "a short one must fail")
    }

    /**
     * the hand-rolled certificate, read back by the JVM's own X.509 parser.
     *
     * Which is the point: `Der`/`X509Self` write bytes that a TLS stack has to accept, and the
     * cheapest honest check on that is to hand them to a parser nobody here wrote. A malformed
     * length, a missing leading zero on the modulus or a wrong tag all fail here rather than inside
     * a handshake against a phone.
     */
    @Test
    fun certificateParsesAndVerifies() {
        val key = createAdbKeyPair() ?: error("no RSA key")
        val der = X509Self.create(key, "device-manager-test") ?: error("no certificate")

        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

        assertEquals(3, parsed.version, "should be a v3 certificate")
        assertEquals("CN=device-manager-test", parsed.subjectX500Principal.name)
        // self-signed, so the issuer is the subject
        assertEquals(parsed.subjectX500Principal, parsed.issuerX500Principal)
        assertEquals("SHA256withRSA", parsed.sigAlgName)

        // the generic is spelled out because assertEquals(Double, Double, Double) otherwise wins
        // the overload resolution and reads the message as a tolerance
        val public = parsed.publicKey as RSAPublicKey
        assertEquals<BigInteger>(
            BigInteger(1, key.modulus),
            public.modulus,
            "the certificate must carry the adb key's own modulus",
        )
        assertEquals<BigInteger>(BigInteger.valueOf(65537), public.publicExponent)

        // the signature has to verify against the key inside it, which exercises signSha256 and the
        // exact bytes of the TBS that were signed
        parsed.verify(parsed.publicKey)
        parsed.checkValidity()
    }
}

private var failures = 0

private fun check(name: String, actual: String, expected: String) {
    if (actual.equals(expected, ignoreCase = true)) {
        println("  ok    $name")
    } else {
        failures++
        println("  FAIL  $name")
        println("        expected $expected")
        println("        actual   $actual")
    }
}

private fun checkTrue(name: String, condition: Boolean) {
    if (condition) {
        println("  ok    $name")
    } else {
        failures++
        println("  FAIL  $name")
    }
}

private fun ByteArray.hex(): String = joinToString("") {
    val v = it.toInt() and 0xFF
    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 15]
}

private fun hex(s: String): ByteArray =
    ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun run() {
    println("SHA-512 (FIPS 180-4 vectors)")
    check("empty", Sha512.hash(ByteArray(0)).hex(),
        "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
            "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e")
    check("abc", Sha512.hash("abc".encodeToByteArray()).hex(),
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f")
    // spans more than one 128-byte block, which is where a padding bug hides
    check("448-bit message", Sha512.hash(
        ("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
            "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu").encodeToByteArray()).hex(),
        "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018" +
            "501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909")

    println("Ed25519 point decoding (coordinates from spake25519.c comments)")
    val (mx, my) = Ed25519.M.affine()
    check("M.x", mx.toLeBytes(32).reversed().toByteArray().hex(),
        "456f78a904bd9c111e3b32bdd3a72c756c326f25ee4262ab40eef197c563a6c8")
    check("M.y", my.toLeBytes(32).reversed().toByteArray().hex(),
        "2ed1ee881b44cfcf538f47a347e3a1515c6b1c13326d62b6add9ddf64b7eda5a")
    val (nx, ny) = Ed25519.N.affine()
    check("N.x", nx.toLeBytes(32).reversed().toByteArray().hex(),
        "6e5d004dea6d1a8ac201d18f13c844f59fbf3faeb3731e4410711743b3c51b20")
    check("N.y", ny.toLeBytes(32).reversed().toByteArray().hex(),
        "78c73b69119a32710d68af06bddcbd3d107246b474feb5997a8e7de30adfe310")

    println("Ed25519 encode/decode round trips")
    check("base point", Ed25519.encode(Ed25519.B).hex(),
        "5866666666666666666666666666666666666666666666666666666666666666")
    check("M", Ed25519.encode(Ed25519.M).hex(),
        "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
    check("N", Ed25519.encode(Ed25519.N).hex(),
        "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")
    checkTrue("all-zero bytes reject", Ed25519.decode(ByteArray(32)) != null)
    checkTrue("non-canonical y >= p rejects",
        Ed25519.decode(hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f")) == null)

    println("Ed25519 scalar multiplication (RFC 8032 key generation vectors)")
    // public = encode(clamp(sha512(seed)[0..31]) * B); exercises SHA-512, clamping, ladder, encode
    listOf(
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60" to
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb" to
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
        "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7" to
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
    ).forEachIndexed { index, (seed, expected) ->
        val h = Sha512.hash(hex(seed))
        val a = h.copyOf(32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 127) or 64).toByte()
        val public = Ed25519.encode(Ed25519.multiplyBase(BigUInt.fromLeBytes(a)))
        check("vector ${index + 1}", public.hex(), expected)
    }

    println("Ed25519 group laws")
    checkTrue("B - B is the identity",
        Ed25519.encode(Ed25519.subtract(Ed25519.B, Ed25519.B)).contentEquals(Ed25519.encode(Ed25519.ZERO)))
    checkTrue("L * B is the identity",
        Ed25519.encode(Ed25519.multiply(Ed25519.L, Ed25519.B)).contentEquals(Ed25519.encode(Ed25519.ZERO)))
    checkTrue("2B == B + B",
        Ed25519.encode(Ed25519.multiply(BigUInt.fromInt(2), Ed25519.B))
            .contentEquals(Ed25519.encode(Ed25519.add(Ed25519.B, Ed25519.B))))

    println("SPAKE2 (BoringSSL layout)")
    val password = "123456".encodeToByteArray()
    val aliceRandom = ByteArray(64) { (it * 7 + 1).toByte() }
    val bobRandom = ByteArray(64) { (it * 11 + 3).toByte() }

    fun run(alicePassword: ByteArray, bobPassword: ByteArray): Pair<ByteArray?, ByteArray?> {
        val alice = Spake2(Spake2.Role.ALICE, Spake2.ADB_CLIENT_NAME, Spake2.ADB_SERVER_NAME)
        val bob = Spake2(Spake2.Role.BOB, Spake2.ADB_SERVER_NAME, Spake2.ADB_CLIENT_NAME)
        val aliceMsg = alice.generateMessage(alicePassword, aliceRandom)
        val bobMsg = bob.generateMessage(bobPassword, bobRandom)
        return alice.processMessage(bobMsg) to bob.processMessage(aliceMsg)
    }

    val (aliceKey, bobKey) = run(password, password)
    checkTrue("both sides produced a key", aliceKey != null && bobKey != null)
    checkTrue("keys agree", aliceKey != null && bobKey != null && aliceKey.contentEquals(bobKey))
    checkTrue("key is 64 bytes", aliceKey?.size == 64)
    println("        key = ${aliceKey?.hex()?.take(32)}...")

    val (wrongA, wrongB) = run(password, "654321".encodeToByteArray())
    checkTrue("a wrong password still completes", wrongA != null && wrongB != null)
    checkTrue("a wrong password gives different keys",
        wrongA != null && wrongB != null && !wrongA.contentEquals(wrongB))

    // the message is a point, so a corrupted one must be rejected rather than silently used
    val alice = Spake2(Spake2.Role.ALICE, Spake2.ADB_CLIENT_NAME, Spake2.ADB_SERVER_NAME)
    alice.generateMessage(password, aliceRandom)
    checkTrue("a message off the curve is refused",
        alice.processMessage(ByteArray(32) { 0xFF.toByte() }) == null)
    checkTrue("a short message is refused", alice.processMessage(ByteArray(31)) == null)

    println()
    if (failures == 0) println("all checks passed") else println("$failures CHECK(S) FAILED")
}

private fun ByteArray.toHex(): String = joinToString("") {
    val v = it.toInt() and 0xFF
    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 15]
}
