package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*

/**
 * devices reached through an adb **server** — the same route the Swing app takes.
 *
 * [host] defaults to loopback, which is the desktop case. Pointing it at another machine on the LAN
 * is what lets a phone drive that machine's whole USB fleet with no new protocol code at all; 5037
 * has no authentication whatsoever, so that is a trusted network only.
 */
class AdbServerTransport(
    private val host: String = "127.0.0.1",
    private val port: Int = 5037,
) : DeviceTransport {

    private val log = LoggerConfig.createLogger("AdbServerTransport")

    override val label: String
        get() = if (host == "127.0.0.1" || host == "localhost") "adb (this computer)" else "adb ($host)"

    /**
     * point the adb package at this server before every call.
     *
     * The address is global rather than carried per connection because **one transport is active at
     * a time** — [com.jpage4500.devicemanager.manager.DeviceManager] owns the choice and closes the
     * previous one. Two `AdbServerTransport`s on different hosts running at once would fight over
     * this, so if that ever becomes a real state the address has to move onto the connection.
     */
    private fun aim() {
        AdbConnection.host = host
        AdbConnection.port = port
    }

    override fun devices(): Flow<List<Device>> {
        aim()
        return AdbDevices.track()
    }

    override suspend fun exec(serial: String, command: String): Result<String> {
        aim()
        return adbExec(serial, command).toResult()
    }

    override suspend fun properties(serial: String): Result<Map<String, String>> {
        aim()
        return adbProperties(serial).toResult()
    }

    /**
     * `screencap -p` over `exec:`, which is what the Swing app's mirror window polls.
     *
     * `exec:` and not `shell:` matters more here than anywhere else: `shell:` rewrites every `\n` in
     * the stream as `\r\n`, and doing that to a PNG corrupts it.
     */
    override suspend fun screenshot(serial: String): Result<ByteArray> {
        aim()
        val opened = AdbConnection.toDevice(serial)
        val connection = when (opened) {
            is AdbOutcome.Err -> return Result.failure(AdbException(opened))
            is AdbOutcome.Ok -> opened.value
        }
        return connection.use {
            when (val outcome = it.request("exec:screencap -p")) {
                is AdbOutcome.Err -> Result.failure(AdbException(outcome))
                is AdbOutcome.Ok -> runCatching { it.raw.readAll() }
            }
        }
    }

    override suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> {
        aim()
        return withSync(serial) { it.list(path) }.toResult()
    }

    override suspend fun pullFile(serial: String, path: String): Result<ByteArray> {
        aim()
        return withSync(serial) { it.pullBytes(path) }.toResult()
    }

    override suspend fun pushFile(
        serial: String,
        bytes: ByteArray,
        path: String,
        mode: Int,
    ): Result<Unit> {
        aim()
        return withSync(serial) { it.push(bytes, path, mode) }.toResult()
    }

    /**
     * `adb connect` — the server dials the device, not us.
     *
     * which is what makes this the cheap route for a phone: the desk's server holds the auth key and
     * does the handshake, and the new device simply turns up in the next `track-devices` push.
     */
    override suspend fun connect(host: String, port: Int): Result<String> {
        aim()
        return adbConnect(host, port).toResult()
    }

    override suspend fun disconnect(serial: String): Result<String> {
        aim()
        return adbDisconnect(serial).toResult()
    }

    override suspend fun reboot(serial: String): Result<Unit> {
        aim()
        return adbReboot(serial).toResult()
    }
}

/** an adb failure, once it has to cross out of the adb package as an exception. */
class AdbException(val reason: AdbError, message: String) : Exception(message) {
    constructor(error: AdbOutcome.Err) : this(error.reason, error.message)
}

fun <T> AdbOutcome<T>.toResult(): Result<T> = when (this) {
    is AdbOutcome.Ok -> Result.success(value)
    is AdbOutcome.Err -> Result.failure(AdbException(this))
}
