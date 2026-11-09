package com.jpage4500.devicemanager.adb.device

/**
 * one message on the **device** protocol — what `adbd` itself speaks, not what the server does.
 *
 * The two protocols share nothing but their service strings. A server command is a 4-lowercase-hex
 * length followed by ASCII ([com.jpage4500.devicemanager.adb.AdbConnection]); a device message is
 * this fixed 24-byte header of six little-endian 32-bit words, followed by [payload]. Confusing the
 * two is the easiest way to break either, which is why they are separate packages.
 *
 * [magic] isn't stored: it is always `command xor -1`, and it is the only thing that tells a real
 * header from a resynchronised stream, so it is checked on the way in rather than kept.
 *
 * See docs/ADB-TRANSPORTS.md, and `packages/modules/adb/types.h` for `struct amessage`.
 */
class AdbMessage(
    val command: Int,
    val arg0: Int = 0,
    val arg1: Int = 0,
    val payload: ByteArray = EMPTY,
) {
    /** the header, as the 24 bytes that go on the wire. */
    fun header(): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        writeLe(out, 0, command)
        writeLe(out, 4, arg0)
        writeLe(out, 8, arg1)
        writeLe(out, 12, payload.size)
        // adb 0x01000001 onwards declares the checksum dead and adbd stops looking at it, but a
        // device that negotiated down to A_VERSION_MIN still verifies it — so it is always sent
        writeLe(out, 16, checksum(payload))
        writeLe(out, 20, command xor -1)
        return out
    }

    /** the four ASCII letters a command spells, for a log line. */
    val name: String get() = nameOf(command)

    override fun toString(): String = "$name arg0=$arg0 arg1=$arg1 len=${payload.size}"

    companion object {
        const val HEADER_SIZE = 24

        // the command words, little-endian ASCII — `A_CNXN` is "CNXN" read as an int
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534c5453

        /** the version this client announces: 0x01000001 is "the checksum is no longer checked". */
        const val A_VERSION = 0x01000001

        /** what `adbd` will accept in one `WRTE`, and so the most this asks for. */
        const val MAX_PAYLOAD = 1024 * 1024

        /** the version word an `STLS` message carries. */
        const val A_STLS_VERSION = 0x01000000

        // the arg0 of an AUTH message says which of the three kinds it is
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        private val EMPTY = ByteArray(0)

        /** the header a device sent, or null when [bytes] can't be one. */
        fun parseHeader(bytes: ByteArray): AdbHeader? {
            if (bytes.size < HEADER_SIZE) return null
            val command = readLe(bytes, 0)
            // the magic is the whole integrity check on a protocol with no framing to resync to
            if (readLe(bytes, 20) != (command xor -1)) return null
            val length = readLe(bytes, 12)
            if (length < 0 || length > MAX_PAYLOAD) return null
            return AdbHeader(command, readLe(bytes, 4), readLe(bytes, 8), length)
        }

        /** adb's checksum: every payload byte, unsigned, added up. */
        fun checksum(payload: ByteArray): Int {
            var sum = 0
            payload.forEach { sum += it.toInt() and 0xFF }
            return sum
        }

        fun nameOf(command: Int): String {
            val out = CharArray(4)
            for (index in 0 until 4) {
                val byte = (command ushr (index * 8)) and 0xFF
                out[index] = if (byte in 0x20..0x7E) byte.toChar() else '?'
            }
            return out.concatToString()
        }

        /**
         * little-endian, written by hand.
         *
         * kept here rather than shared with `AdbSync`'s private pair on purpose: the sync protocol
         * and the device protocol are different framings that happen to agree on byte order, and one
         * helper spanning both would invite reading a sync length with a device reader.
         */
        internal fun writeLe(into: ByteArray, at: Int, value: Int) {
            into[at] = (value and 0xFF).toByte()
            into[at + 1] = ((value ushr 8) and 0xFF).toByte()
            into[at + 2] = ((value ushr 16) and 0xFF).toByte()
            into[at + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        internal fun readLe(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}

/** a header off the wire, before its payload has been read. */
class AdbHeader(val command: Int, val arg0: Int, val arg1: Int, val length: Int) {
    val name: String get() = AdbMessage.nameOf(command)

    override fun toString(): String = "$name arg0=$arg0 arg1=$arg1 len=$length"
}
