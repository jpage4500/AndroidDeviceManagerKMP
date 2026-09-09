package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*

private val log = LoggerConfig.createLogger("AdbSync")

/**
 * the `sync:` sub-protocol — listing, stat, pull and push.
 *
 * **the framing changes here.** Outside a sync session a length is 4 lowercase hex characters;
 * inside one it is a 4-byte **little-endian** integer, and every command is an 8-byte header (a
 * 4-character id followed by that length). Getting the two mixed up is the classic way to break an
 * adb client, which is why this can only be reached through [AdbConnection.startSync].
 *
 * A session serves any number of round trips before it is finished with. jadb opens a fresh one per
 * file, which costs a connect, a transport switch and a service switch every time.
 */
class AdbSync internal constructor(
    private val stream: AdbByteStream,
    /** true when the device has `stat_v2`/`ls_v2` — see [AdbFeatures.syncV2]. */
    private val v2: Boolean,
) {
    /** one folder. `.` and `..` are dropped; adb sends them as ordinary records. */
    suspend fun list(path: String): AdbOutcome<List<DeviceFile>> = try {
        sendCommand(if (v2) ID_LIS2 else ID_LIST, path)
        val entries = mutableListOf<DeviceFile>()
        while (true) {
            val id = readId()
            when (id) {
                ID_DENT -> readDent(path)?.let { entries += it }
                ID_DNT2 -> readDnt2(path)?.let { entries += it }
                ID_DONE -> {
                    // the trailer is the rest of a zeroed record, and it has to be consumed or the
                    // next command reads its own reply out of frame
                    stream.readFully(if (v2) TRAILER_V2 else TRAILER_V1)
                    break
                }
                ID_FAIL -> {
                    val message = readFailMessage()
                    return AdbOutcome.Err(classifyAdbFailure(message), message)
                }
                else -> return AdbOutcome.Err(AdbError.IO, "unexpected sync reply: $id")
            }
        }
        AdbOutcome.Ok(entries.filterNot { it.name == "." || it.name == ".." })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the listing stopped")
    }

    /** one entry, or null when the path isn't there. */
    suspend fun stat(path: String): AdbOutcome<DeviceFile?> = try {
        sendCommand(if (v2) ID_STA2 else ID_STAT, path)
        val id = readId()
        when (id) {
            ID_STAT -> {
                val mode = readInt()
                val size = readInt().toLong() and 0xFFFFFFFFL
                val mtime = readInt().toLong() and 0xFFFFFFFFL
                // a v1 stat of something that isn't there is all zeroes rather than a FAIL
                if (mode == 0 && size == 0L && mtime == 0L) AdbOutcome.Ok(null)
                else AdbOutcome.Ok(
                    DeviceFile(
                        name = path.substringAfterLast('/'),
                        path = path,
                        size = size,
                        modifiedMs = mtime * 1000,
                        mode = mode,
                        ownerKnown = false,
                    )
                )
            }
            ID_STA2 -> {
                val error = readInt()
                readSkip(16) // dev, ino
                val mode = readInt()
                readInt() // nlink
                val uid = readInt()
                val gid = readInt()
                val size = readLong()
                readSkip(8) // atime
                val mtime = readLong()
                readSkip(8) // ctime
                if (error != 0) AdbOutcome.Ok(null)
                else AdbOutcome.Ok(
                    DeviceFile(
                        name = path.substringAfterLast('/'),
                        path = path,
                        size = size,
                        modifiedMs = mtime * 1000,
                        mode = mode,
                        uid = uid,
                        gid = gid,
                    )
                )
            }
            ID_FAIL -> AdbOutcome.Err(AdbError.IO, readFailMessage())
            else -> AdbOutcome.Err(AdbError.IO, "unexpected sync reply: $id")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the stat stopped")
    }

    /**
     * pull [path], handing each chunk to [onChunk] as it arrives.
     *
     * streamed rather than collected so a large file doesn't have to fit in memory twice, and so a
     * progress bar has something to move on — jadb has no progress callback at all, which the Swing
     * app works around by polling the output file's length every 200ms.
     */
    suspend fun pull(
        path: String,
        onChunk: suspend (ByteArray, Int) -> Unit,
    ): AdbOutcome<Long> = try {
        sendCommand(ID_RECV, path)
        var total = 0L
        val buffer = ByteArray(SYNC_CHUNK)
        while (true) {
            when (val id = readId()) {
                ID_DATA -> {
                    val length = readInt()
                    var remaining = length
                    while (remaining > 0) {
                        val take = minOf(remaining, buffer.size)
                        stream.readFully(buffer, 0, take)
                        onChunk(buffer, take)
                        remaining -= take
                        total += take
                    }
                }
                ID_DONE -> {
                    readInt() // mtime
                    break
                }
                ID_FAIL -> {
                    val message = readFailMessage()
                    return AdbOutcome.Err(classifyAdbFailure(message), message)
                }
                else -> return AdbOutcome.Err(AdbError.IO, "unexpected sync reply: $id")
            }
        }
        AdbOutcome.Ok(total)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the transfer stopped")
    }

    /** the whole of [path] in memory — for a screenshot or anything else small and known. */
    suspend fun pullBytes(path: String): AdbOutcome<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        return when (val outcome = pull(path) { buffer, length -> chunks += buffer.copyOf(length) }) {
            is AdbOutcome.Err -> outcome
            is AdbOutcome.Ok -> AdbOutcome.Ok(joinChunks(chunks))
        }
    }

    /** push [bytes] to [path] with [mode] permission bits. */
    suspend fun push(
        bytes: ByteArray,
        path: String,
        mode: Int = 0b110_100_100,
        modifiedMs: Long = 0,
    ): AdbOutcome<Unit> = try {
        sendCommand(ID_SEND, "$path,$mode")
        var at = 0
        while (at < bytes.size) {
            val take = minOf(SYNC_CHUNK, bytes.size - at)
            stream.write(ID_DATA.encodeToByteArray())
            stream.write(littleEndian(take))
            stream.write(bytes.copyOfRange(at, at + take))
            at += take
        }
        stream.write(ID_DONE.encodeToByteArray())
        stream.write(littleEndian((modifiedMs / 1000).toInt()))
        stream.flush()
        when (val id = readId()) {
            ID_OKAY -> {
                readInt()
                AdbOutcome.Ok(Unit)
            }
            ID_FAIL -> readFailMessage().let { AdbOutcome.Err(classifyAdbFailure(it), it) }
            else -> AdbOutcome.Err(AdbError.IO, "unexpected sync reply: $id")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the transfer stopped")
    }

    /** end the session politely, so the far end doesn't log a dropped socket. */
    suspend fun quit() {
        runCatching {
            stream.write(ID_QUIT.encodeToByteArray())
            stream.write(littleEndian(0))
            stream.flush()
        }
    }

    // ── framing ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun sendCommand(id: String, payload: String) {
        val bytes = payload.encodeToByteArray()
        stream.write(id.encodeToByteArray())
        stream.write(littleEndian(bytes.size))
        stream.write(bytes)
        stream.flush()
    }

    private suspend fun readId(): String = stream.readFully(4).decodeToString()

    private suspend fun readInt(): Int {
        val bytes = stream.readFully(4)
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
    }

    private suspend fun readLong(): Long {
        val bytes = stream.readFully(8)
        var value = 0L
        for (index in 7 downTo 0) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        return value
    }

    private suspend fun readSkip(count: Int) {
        stream.readFully(count)
    }

    /** a FAIL's own length-prefixed reason, in the sync framing. */
    private suspend fun readFailMessage(): String {
        val length = readInt()
        return stream.readFully(length).decodeToString()
    }

    private suspend fun readDent(parent: String): DeviceFile? {
        val mode = readInt()
        val size = readInt().toLong() and 0xFFFFFFFFL
        val mtime = readInt().toLong() and 0xFFFFFFFFL
        val name = stream.readFully(readInt()).decodeToString()
        // a v1 listing carries no ownership at all, and guessing readability from it dims everything
        return DeviceFile(name, childPath(parent, name), size, mtime * 1000, mode, ownerKnown = false)
    }

    private suspend fun readDnt2(parent: String): DeviceFile? {
        val error = readInt()
        readSkip(16) // dev, ino
        val mode = readInt()
        readInt() // nlink
        val uid = readInt()
        val gid = readInt()
        val size = readLong()
        readSkip(8) // atime
        val mtime = readLong()
        readSkip(8) // ctime
        val name = stream.readFully(readInt()).decodeToString()
        // adbd sends an unreadable entry with an errno and every other field zeroed. a zero mode
        // reads as "no permissions", so it has to mean *unknown* instead
        if (error != 0) return DeviceFile(name, childPath(parent, name), 0, 0, 0, ownerKnown = false)
        return DeviceFile(name, childPath(parent, name), size, mtime * 1000, mode, uid, gid)
    }

    private fun littleEndian(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    companion object {
        const val SYNC_CHUNK = 64 * 1024

        private const val ID_LIST = "LIST"
        private const val ID_LIS2 = "LIS2"
        private const val ID_DENT = "DENT"
        private const val ID_DNT2 = "DNT2"
        private const val ID_STAT = "STAT"
        private const val ID_STA2 = "STA2"
        private const val ID_RECV = "RECV"
        private const val ID_SEND = "SEND"
        private const val ID_DATA = "DATA"
        private const val ID_DONE = "DONE"
        private const val ID_OKAY = "OKAY"
        private const val ID_FAIL = "FAIL"
        private const val ID_QUIT = "QUIT"

        // what follows DONE: the rest of a zeroed record of whichever kind was being read
        private const val TRAILER_V1 = 16
        private const val TRAILER_V2 = 72
    }
}

internal fun childPath(parent: String, name: String): String =
    if (parent.endsWith('/')) "$parent$name" else "$parent/$name"

internal fun joinChunks(chunks: List<ByteArray>): ByteArray {
    val out = ByteArray(chunks.sumOf { it.size })
    var at = 0
    chunks.forEach { chunk ->
        chunk.copyInto(out, at)
        at += chunk.size
    }
    return out
}

/**
 * run [block] against a sync session on [serial], closing everything afterwards.
 *
 * the session is held for a whole operation rather than one file — see [AdbSync].
 */
suspend fun <T> withSync(
    server: AdbServer,
    serial: String,
    block: suspend (AdbSync) -> AdbOutcome<T>,
): AdbOutcome<T> {
    val opened = AdbConnection.toDevice(server, serial)
    val connection = when (opened) {
        is AdbOutcome.Err -> return opened
        is AdbOutcome.Ok -> opened.value
    }
    return connection.use {
        when (val session = it.startSync(AdbFeatures.syncV2(server, serial))) {
            is AdbOutcome.Err -> session
            is AdbOutcome.Ok -> try {
                block(session.value).also { _ -> session.value.quit() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d("withSync: $serial: ${e.message}")
                AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the device went away")
            }
        }
    }
}
