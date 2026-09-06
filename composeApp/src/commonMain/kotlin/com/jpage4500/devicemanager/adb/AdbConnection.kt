package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*

/**
 * one conversation with the adb **server** (port 5037), not with a device.
 *
 * adb speaks **two different framings** down the same socket and confusing them is the easiest way
 * to break this. a host or service command is a 4-**lowercase-hex** length followed by ASCII;
 * everything inside a `sync:` session is a 4-byte **little-endian** length. this class does the
 * first and hands the second to [AdbSync], which is only reachable through [startSync] — so the two
 * can't be mixed by accident.
 *
 * a socket is good for exactly one service. once it has carried `host:transport:<serial>` it belongs
 * to that device, and once it has carried `sync:` or `exec:` the server closes it when that service
 * ends. there is no state it can return to, which is why nothing here is pooled.
 */
class AdbConnection private constructor(private val stream: AdbStream) {

    /** raw bytes past the OKAY — a `shell:`/`exec:` payload, or a file being streamed. */
    val raw: AdbStream get() = stream

    /** 4-hex length, then the command's UTF-8 bytes. */
    suspend fun send(command: String) {
        val bytes = command.encodeToByteArray()
        stream.write(Utils.hexLength(bytes.size).encodeToByteArray())
        stream.write(bytes)
        stream.flush()
    }

    /** the OKAY/FAIL that answers every command; a FAIL carries its own length-prefixed reason. */
    suspend fun readStatus(): AdbOutcome<Unit> {
        val status = readAscii(4)
        if (status == "OKAY") return AdbOutcome.Ok(Unit)
        val reason = runCatching { readFrame() }.getOrDefault("")
        return AdbOutcome.Err(classifyAdbFailure(reason), reason.ifEmpty { "adb refused the request" })
    }

    /** 4-hex length, then that many bytes — a host command's payload, or one tracker frame. */
    suspend fun readFrame(): String {
        val length = readAscii(4).toInt(16)
        return readAscii(length)
    }

    /** send and check in one step, which is what every caller wants. */
    suspend fun request(command: String): AdbOutcome<Unit> = try {
        send(command)
        readStatus()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the connection dropped")
    }

    /** switch this socket into a sync session. it belongs to that session from here on. */
    suspend fun startSync(v2: Boolean): AdbOutcome<AdbSync> = when (val outcome = request("sync:")) {
        is AdbOutcome.Err -> outcome
        is AdbOutcome.Ok -> AdbOutcome.Ok(AdbSync(stream, v2))
    }

    private suspend fun readAscii(length: Int): String =
        stream.readFully(length).decodeToString()

    fun close() = stream.close()

    suspend inline fun <T> use(block: (AdbConnection) -> T): T = try {
        block(this)
    } finally {
        close()
    }

    companion object {
        private val log = LoggerConfig.createLogger("AdbConnection")

        /**
         * where the adb server is.
         *
         * a var rather than a constant because this is the one knob that lets a phone drive a
         * desktop's whole USB fleet: point it at that machine and every call below works unchanged.
         * 5037 has no authentication at all, so that is a trusted-LAN move only — see
         * docs/ADB-TRANSPORTS.md.
         */
        var host: String = "127.0.0.1"
        var port: Int = 5037

        /** connect to the server, or null when nothing is listening. */
        suspend fun openOrNull(connectMs: Long = AdbStream.CONNECT_MS): AdbConnection? =
            AdbStream.openOrNull(host, port, connectMs)?.let { AdbConnection(it) }

        /** connect and hand the socket to one device. */
        suspend fun toDevice(serial: String): AdbOutcome<AdbConnection> {
            val connection = openOrNull()
                ?: return AdbOutcome.Err(AdbError.NO_SERVER, "adb isn't running")
            return when (val outcome = connection.request("host:transport:$serial")) {
                is AdbOutcome.Err -> {
                    connection.close()
                    outcome
                }
                is AdbOutcome.Ok -> AdbOutcome.Ok(connection)
            }
        }

        /** what the device can do, so the v2 sync commands are only used where they exist. */
        suspend fun featuresOf(serial: String): Set<String> {
            val connection = openOrNull() ?: return emptySet()
            return connection.use {
                when (it.request("host-serial:$serial:features")) {
                    is AdbOutcome.Err -> emptySet()
                    is AdbOutcome.Ok -> runCatching { it.readFrame().split(',').toSet() }
                        .getOrDefault(emptySet())
                }
            }
        }
    }
}

/**
 * which sync commands a device understands, asked once per serial.
 *
 * evicted whenever the tracker stops reporting a serial: a TCP serial like `192.168.1.5:5555` can be
 * reused by a different phone, and answering for the old one would pick the wrong commands.
 */
object AdbFeatures {
    private val cache = mutableMapOf<String, Set<String>>()

    suspend fun of(serial: String): Set<String> =
        cache[serial] ?: AdbConnection.featuresOf(serial).also { cache[serial] = it }

    /**
     * true when the device has `stat_v2`/`ls_v2`.
     *
     * not a nicety. v1's `STAT` does **not** follow symlinks, and `/sdcard` — the one folder anybody
     * wants — is itself a symlink, so v1 reports it as a 21-byte file that can't be opened. v1's size
     * field is also a signed 32-bit int, which goes negative past 2GB.
     */
    suspend fun syncV2(serial: String): Boolean = of(serial).let { "stat_v2" in it && "ls_v2" in it }

    suspend fun hasCmd(serial: String): Boolean = "cmd" in of(serial)

    fun retain(serials: Set<String>) {
        cache.keys.retainAll(serials)
    }
}
