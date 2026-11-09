package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*

/**
 * where an adb server is listening.
 *
 * carried as a value into every call rather than held as a global, because a phone can be reading
 * two desks at once and each of them runs its own server — see
 * [com.jpage4500.devicemanager.manager.DeviceManager]. 5037 has no authentication at all, so
 * pointing this at another machine is a trusted-LAN move only; see docs/ADB-TRANSPORTS.md.
 */
data class AdbServer(val host: String = "127.0.0.1", val port: Int = 5037) {
    /** no adb server can run inside an Android or iOS app, so loopback means different things. */
    val isLoopback: Boolean get() = host == "127.0.0.1" || host == "localhost"

    override fun toString(): String = "$host:$port"

    companion object {
        /** the desktop's own server, which is the only one the Swing app ever talks to. */
        val LOCAL = AdbServer()
    }
}

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

        /** connect to the server, or null when nothing is listening. */
        suspend fun openOrNull(
            server: AdbServer,
            connectMs: Long = AdbStream.CONNECT_MS,
        ): AdbConnection? =
            AdbStream.openOrNull(server.host, server.port, connectMs)?.let { AdbConnection(it) }

        /** connect and hand the socket to one device. */
        suspend fun toDevice(server: AdbServer, serial: String): AdbOutcome<AdbConnection> {
            val connection = openOrNull(server)
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
        suspend fun featuresOf(server: AdbServer, serial: String): Set<String> {
            val connection = openOrNull(server) ?: return emptySet()
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
 *
 * keyed on the **server** as well as the serial, because two desks can each have an
 * `emulator-5554` and they are not the same device — the same reason a device's identity above this
 * layer is its connection plus its serial.
 */
object AdbFeatures {
    private val cache = mutableMapOf<String, Set<String>>()

    suspend fun of(server: AdbServer, serial: String): Set<String> {
        val key = keyOf(server, serial)
        return cache[key] ?: AdbConnection.featuresOf(server, serial).also { cache[key] = it }
    }

    /**
     * true when the device has `stat_v2`/`ls_v2`.
     *
     * not a nicety. v1's `STAT` does **not** follow symlinks, and `/sdcard` — the one folder anybody
     * wants — is itself a symlink, so v1 reports it as a 21-byte file that can't be opened. v1's size
     * field is also a signed 32-bit int, which goes negative past 2GB.
     */
    suspend fun syncV2(server: AdbServer, serial: String): Boolean =
        of(server, serial).let { "stat_v2" in it && "ls_v2" in it }

    suspend fun hasCmd(server: AdbServer, serial: String): Boolean = "cmd" in of(server, serial)

    /** drop what [server] no longer reports, leaving every other server's answers alone. */
    fun retain(server: AdbServer, serials: Set<String>) {
        val prefix = "$server/"
        val keep = serials.mapTo(mutableSetOf()) { keyOf(server, it) }
        cache.keys.retainAll { !it.startsWith(prefix) || it in keep }
    }

    private fun keyOf(server: AdbServer, serial: String) = "$server/$serial"
}
