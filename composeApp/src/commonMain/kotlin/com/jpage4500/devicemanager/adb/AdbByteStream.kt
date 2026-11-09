package com.jpage4500.devicemanager.adb

/**
 * a stream of bytes with a length-framed protocol on top — all [AdbSync] needs of what carries it.
 *
 * Extracted because the sync protocol is identical on both routes and its carrier is not. On the
 * server route a `sync:` session owns a whole socket ([AdbStream]); on the direct route it is one
 * multiplexed stream among several on a shared connection, arriving as discrete chunks that have to
 * be stitched back into a byte stream. The sync code should not know which it has — and with this,
 * it doesn't.
 */
interface AdbByteStream {
    suspend fun write(bytes: ByteArray)

    suspend fun flush()

    /** exactly [length] bytes into [into], or a failure — every framed read wants this. */
    suspend fun readFully(into: ByteArray, offset: Int = 0, length: Int = into.size)

    suspend fun readFully(length: Int): ByteArray
}
