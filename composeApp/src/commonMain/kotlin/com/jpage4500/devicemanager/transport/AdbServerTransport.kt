package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*

/**
 * devices reached through an adb **server** — the same route the Swing app takes.
 *
 * The address defaults to loopback, which is the desktop case. Pointing it at another machine on the
 * LAN is what lets a phone drive that machine's whole USB fleet with no new protocol code at all;
 * 5037 has no authentication whatsoever, so that is a trusted network only.
 *
 * [server] is carried into every call rather than set on a global before each one, which is what
 * lets two of these run side by side — a desk at the office and a desk at home are two servers, and
 * each of them may well have its own `emulator-5554`.
 */
class AdbServerTransport(
    host: String = "127.0.0.1",
    port: Int = 5037,
) : DeviceTransport {

    // public so a program run against the same server — scrcpy — can be pointed at it
    val server = AdbServer(host, port)

    override val id: String get() = "adb://$server"

    override val label: String
        get() = if (server.isLoopback) "adb (this computer)" else "adb (${server.host})"

    /**
     * the tracker, after starting a loopback server that isn't running — the Swing app did the same.
     *
     * once per collection (launch, a refresh, a settings change), never from the tracker's own retry
     * loop: someone who ran `adb kill-server` on purpose shouldn't see it come straight back.
     */
    override fun devices(): Flow<List<Device>> = flow {
        if (server.isLoopback) {
            val running = AdbConnection.openOrNull(server)?.also { it.close() } != null
            if (!running) AdbServerHost.start(server)
        }
        emitAll(AdbDevices.track(server))
    }

    override suspend fun exec(serial: String, command: String): Result<String> =
        adbExec(server, serial, command).toResult()

    // the adb client knows nothing about process names — `ps` is the log window's job here
    override fun logcat(serial: String, since: String?): Flow<LogBatch> =
        adbLogcat(server, serial, since).map { LogBatch(it) }

    override val canStreamLogs: Boolean get() = true

    override suspend fun properties(serial: String): Result<Map<String, String>> =
        adbProperties(server, serial).toResult()

    /**
     * `screencap -p` over `exec:`, which is what the Swing app's mirror window polls.
     *
     * `exec:` and not `shell:` matters more here than anywhere else: `shell:` rewrites every `\n` in
     * the stream as `\r\n`, and doing that to a PNG corrupts it.
     */
    override suspend fun screenshot(serial: String): Result<ByteArray> = execBytes(serial, "screencap -p")

    /** [CMD_PREVIEW_STILL]: the power service first, the capture only if the screen is on. */
    override suspend fun previewStill(serial: String): Result<ScreenStill> =
        execBytes(serial, CMD_PREVIEW_STILL).mapCatching { parsePreviewStill(it).getOrThrow() }

    /** a command's whole output, unaltered — which is what a PNG needs. */
    private suspend fun execBytes(serial: String, command: String): Result<ByteArray> {
        val opened = AdbConnection.toDevice(server, serial)
        val connection = when (opened) {
            is AdbOutcome.Err -> return Result.failure(AdbException(opened))
            is AdbOutcome.Ok -> opened.value
        }
        return connection.use {
            when (val outcome = it.request("exec:$command")) {
                is AdbOutcome.Err -> Result.failure(AdbException(outcome))
                is AdbOutcome.Ok -> runCatching { it.raw.readAll() }
            }
        }
    }

    override suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> =
        withSync(server, serial) { it.list(path) }.toResult()

    override suspend fun pullFile(serial: String, path: String): Result<ByteArray> =
        withSync(server, serial) { it.pullBytes(path) }.toResult()

    override suspend fun pushFile(
        serial: String,
        bytes: ByteArray,
        path: String,
        mode: Int,
    ): Result<Unit> = withSync(server, serial) { it.push(bytes, path, mode) }.toResult()

    /**
     * `adb connect` — the server dials the device, not us.
     *
     * which is what makes this the cheap route for a phone: the desk's server holds the auth key and
     * does the handshake, and the new device simply turns up in the next `track-devices` push.
     */
    override val canConnect: Boolean get() = true

    override suspend fun connect(host: String, port: Int): Result<String> =
        adbConnect(server, host, port).toResult()

    override suspend fun disconnect(serial: String): Result<String> =
        adbDisconnect(server, serial).toResult()

    override suspend fun reboot(serial: String): Result<Unit> = adbReboot(server, serial).toResult()

    /**
     * pairing is done *by* the server, so it needs one that exists.
     *
     * The loopback case is the trap this closes: a phone left on the default address would otherwise
     * offer the button, show a QR, watch someone scan it, find the device over mDNS — and only then
     * say there was never a server. Measured in whose time, that is the worst failure in the app.
     *
     * A phone no longer needs this route at all — it pairs for itself through
     * [com.jpage4500.devicemanager.adb.pairing.AdbPairing] — so this is the desktop's answer and
     * iOS's only one.
     */
    override val canPair: Boolean
        get() = !server.isLoopback || PlatformUtils.getPlatform() == PlatformType.DESKTOP

    override suspend fun pair(host: String, port: Int, code: String): Result<String> =
        adbPair(server, host, port, code).toResult()

    override suspend fun probe(): Result<String> =
        adbVersion(server).toResult().map { "$label, protocol $it" }
}

/** an adb failure, once it has to cross out of the adb package as an exception. */
class AdbException(val reason: AdbError, message: String) : Exception(message) {
    constructor(error: AdbOutcome.Err) : this(error.reason, error.message)
}

fun <T> AdbOutcome<T>.toResult(): Result<T> = when (this) {
    is AdbOutcome.Ok -> Result.success(value)
    is AdbOutcome.Err -> Result.failure(AdbException(this))
}
