package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.util.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

/**
 * a raw TCP connection, and the only platform-facing thing in the adb client.
 *
 * ktor-network rather than `java.net.Socket` on purpose: it publishes jvm, android **and** ios
 * artifacts, which is what lets everything above this file live in `commonMain`. The Swing app's
 * jadb is JVM-only, and that single fact is what would otherwise have forced the protocol to be
 * written twice. See docs/ADB-TRANSPORTS.md.
 */
class AdbStream private constructor(
    private val socket: Socket,
    private val read: ByteReadChannel,
    private val write: ByteWriteChannel,
) : AdbByteStream {
    override suspend fun write(bytes: ByteArray) {
        write.writeFully(bytes, 0, bytes.size)
    }

    override suspend fun flush() {
        write.flush()
    }

    override suspend fun readFully(into: ByteArray, offset: Int, length: Int) {
        read.readFully(into, offset, length)
    }

    override suspend fun readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        readFully(buffer, 0, length)
        return buffer
    }

    /** whatever has arrived, up to [into]'s size. -1 at end of stream. */
    suspend fun readSome(into: ByteArray): Int {
        val count = read.readAvailable(into, 0, into.size)
        return count
    }

    /** everything until the far end closes — how an `exec:` payload is collected. */
    suspend fun readAll(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = readSome(buffer)
            if (count <= 0) break
            chunks += buffer.copyOf(count)
            total += count
        }
        val out = ByteArray(total)
        var at = 0
        chunks.forEach { chunk ->
            chunk.copyInto(out, at)
            at += chunk.size
        }
        return out
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val BUFFER = 64 * 1024

        private val log = LoggerConfig.createLogger("AdbStream")

        // one selector for the whole app; a SelectorManager owns threads, so making one per socket
        // would spawn a pair per device refresh
        private val selector by lazy { SelectorManager(ioDispatcher) }

        /**
         * connect, or null when nothing is listening.
         *
         * **never runs `adb start-server`.** a refused connect to loopback comes back in single-digit
         * milliseconds, which is what makes it reasonable to ask this on a timer.
         */
        suspend fun openOrNull(host: String, port: Int, connectMs: Long = CONNECT_MS): AdbStream? =
            withContext(ioDispatcher) {
                try {
                    val socket = withTimeoutOrNull(connectMs) {
                        aSocket(selector).tcp().connect(host, port) { noDelay = true }
                    } ?: return@withContext null
                    AdbStream(socket, socket.openReadChannel(), socket.openWriteChannel(autoFlush = false))
                } catch (e: Exception) {
                    // "no adb server" is the overwhelmingly common case and isn't worth a log line
                    null
                }
            }

        const val CONNECT_MS = 1_000L
        const val READ_MS = 5_000L
        const val LIST_MS = 10_000L
        const val EXEC_MS = 15_000L
        const val TRANSFER_MS = 30_000L
        const val INSTALL_MS = 5 * 60_000L
    }
}
