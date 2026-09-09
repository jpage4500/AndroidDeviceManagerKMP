package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.adb.*

/**
 * an [AdbDeviceStream] seen as a byte stream, so the sync protocol can run over it unchanged.
 *
 * Two mismatches to close. Reads arrive as whole `WRTE` chunks whose boundaries have nothing to do
 * with the fields being read, so a chunk is held and served from until it runs out. Writes are the
 * other way round: [AdbSync] writes a header in three small pieces and then flushes, and each write
 * reaching the wire on its own would be three messages and three acknowledgements for ten bytes — so
 * they are gathered and sent on the flush that follows.
 */
internal class AdbDeviceByteStream(private val stream: AdbDeviceStream) : AdbByteStream {

    private var pending = ByteArray(0)
    private var at = 0

    private val outgoing = mutableListOf<ByteArray>()
    private var outgoingSize = 0

    override suspend fun write(bytes: ByteArray) {
        outgoing += bytes
        outgoingSize += bytes.size
        // a push writes a whole file through here, so the buffer is capped rather than unbounded
        if (outgoingSize >= FLUSH_AT) flush()
    }

    override suspend fun flush() {
        if (outgoing.isEmpty()) return
        val joined = ByteArray(outgoingSize)
        var into = 0
        outgoing.forEach { chunk ->
            chunk.copyInto(joined, into)
            into += chunk.size
        }
        outgoing.clear()
        outgoingSize = 0
        // AdbDeviceStream splits this across as many WRTEs as the negotiated payload needs
        val sent = stream.write(joined)
        if (sent is AdbOutcome.Err) throw IllegalStateException(sent.message)
    }

    override suspend fun readFully(into: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            if (at >= pending.size) {
                pending = stream.read() ?: throw IllegalStateException("the device closed the stream")
                at = 0
                continue
            }
            val take = minOf(length - written, pending.size - at)
            pending.copyInto(into, offset + written, at, at + take)
            at += take
            written += take
        }
    }

    override suspend fun readFully(length: Int): ByteArray {
        val out = ByteArray(length)
        if (length > 0) readFully(out, 0, length)
        return out
    }

    private companion object {
        /** the same 64KB the socket route reads and writes in. */
        const val FLUSH_AT = 64 * 1024
    }
}
