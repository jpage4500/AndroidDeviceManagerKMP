package com.jpage4500.devicemanager.adb.device

import com.jpage4500.devicemanager.adb.*
import kotlinx.coroutines.channels.*
import kotlin.concurrent.*

/**
 * one service running on an [AdbDeviceConnection] — the device protocol's answer to a socket.
 *
 * A stream is a pair of ids: the one this end minted when it sent `OPEN`, and the one the device
 * answered with. Both travel on every message, which is what lets a mirror, a logcat and a file pull
 * share a single connection.
 *
 * **Every `WRTE` waits for an `OKAY`.** That is adb's whole flow control: the device will not accept
 * a second write until it has acknowledged the first, so sending without waiting doesn't go faster,
 * it deadlocks. [acks] is that acknowledgement, and the `OKAY` answering `OPEN` is simply the first
 * one — which is why [awaitReady] reads from the same channel.
 */
class AdbDeviceStream internal constructor(
    private val connection: AdbDeviceConnection,
    private val localId: Int,
    private val maxPayload: Int,
) {
    /** the device's id for this stream, known once it has answered `OPEN`. */
    private var remoteId = 0

    // unlimited on both: the reader coroutine owns the only socket, so a channel that blocked it
    // would stall every other stream on the connection behind this one
    private val acks = Channel<Unit>(Channel.UNLIMITED)
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var closed = false

    internal fun onReady(remote: Int) {
        remoteId = remote
        acks.trySend(Unit)
    }

    internal fun onData(bytes: ByteArray) {
        incoming.trySend(bytes)
    }

    internal fun onClosed() {
        closed = true
        incoming.close()
        acks.close()
    }

    /** the `OKAY` that says the device took the service, or the `CLSE` that says it didn't. */
    internal suspend fun awaitReady(): AdbOutcome<Unit> {
        val acked = runCatching { acks.receive() }.isSuccess
        return if (acked && !closed) {
            AdbOutcome.Ok(Unit)
        } else {
            AdbOutcome.Err(AdbError.NOT_FOUND, "the device wouldn't open that service")
        }
    }

    /** the next chunk the device sent, or null once the stream has ended. */
    suspend fun read(): ByteArray? = runCatching { incoming.receive() }.getOrNull()

    /** everything until the device closes the stream — how an `exec:` payload is collected. */
    suspend fun readAll(): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        for (chunk in incoming) {
            chunks += chunk
            total += chunk.size
        }
        val out = ByteArray(total)
        var at = 0
        chunks.forEach { chunk ->
            chunk.copyInto(out, at)
            at += chunk.size
        }
        return out
    }

    /** write to the service, a `maxPayload` chunk at a time, waiting for each acknowledgement. */
    suspend fun write(bytes: ByteArray): AdbOutcome<Unit> {
        var at = 0
        while (at < bytes.size) {
            if (closed) return AdbOutcome.Err(AdbError.DISCONNECTED, "the stream is closed")
            val take = minOf(maxPayload, bytes.size - at)
            val chunk = bytes.copyOfRange(at, at + take)
            when (val sent = connection.send(AdbMessage(AdbMessage.A_WRTE, localId, remoteId, chunk))) {
                is AdbOutcome.Err -> return sent
                is AdbOutcome.Ok -> Unit
            }
            if (runCatching { acks.receive() }.isFailure) {
                return AdbOutcome.Err(AdbError.DISCONNECTED, "the device stopped acknowledging")
            }
            at += take
        }
        return AdbOutcome.Ok(Unit)
    }

    /** tell the device this service is done with. */
    suspend fun close() {
        if (closed) return
        closed = true
        connection.send(AdbMessage(AdbMessage.A_CLSE, localId, remoteId))
        connection.forget(localId)
        incoming.close()
        acks.close()
    }
}
