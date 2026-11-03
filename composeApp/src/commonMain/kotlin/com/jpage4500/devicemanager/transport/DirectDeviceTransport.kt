package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.adb.device.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

/**
 * one device, reached with no adb server anywhere — the fourth route in docs/ADB-TRANSPORTS.md.
 *
 * The other three all end at a desktop: two at an adb server on 5037, one at a Device Manager
 * instance on 8765. This one talks to `adbd` on the device itself, so a phone can drive another
 * phone with nothing in between. That is the whole reason the device protocol was written.
 *
 * **One transport is one device**, which is what makes it different from every other transport here.
 * An adb server is asked what is attached; a device is not — reaching it *is* knowing about it. So
 * [devices] reports a list of one, and a second device is a second connection.
 *
 * What it cannot do is USB, ever: `adbd` is only reachable over TCP. The desktop keeps talking to
 * the adb server for exactly that reason, and this is an addition rather than a replacement.
 */
class DirectDeviceTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
) : DeviceTransport {

    private val log = LoggerConfig.createLogger("DirectDeviceTransport")

    /** the serial adb itself would use for a network device, and what the row is keyed on. */
    private val serial = "$host:$port"

    override val id: String get() = "adbd://$serial"

    override val label: String get() = "device ($host)"

    /**
     * the id the one device on this connection will have in the list.
     *
     * Known up front rather than waited for, because [devices] is the only thing that ever publishes
     * it and a caller that has just connected wants to open the device *now* — see the Connect panel,
     * which closes itself and opens this device's page.
     */
    val deviceId: String get() = Device(serial = serial, connectionId = id).id

    private val lock = Mutex()
    private var connection: AdbDeviceConnection? = null

    // what devices() is pushing, so a failure anywhere can move the row to offline without the
    // caller having to know there is a device list at all
    private val state = MutableStateFlow(DeviceState.CONNECTING)

    /**
     * a list of one, re-pushed as the connection comes and goes.
     *
     * The model is filled in from the connection banner rather than a `getprop`: `adbd` puts
     * `ro.product.model` in the `CNXN` payload, so the name arrives with the handshake and costs
     * nothing — the same trick `host:devices-l` plays on the server route.
     */
    override fun devices(): Flow<List<Device>> = state.map { current ->
        val banner = connection?.banner.orEmpty()
        listOf(
            Device(
                serial = serial,
                state = current,
                model = bannerValue(banner, "ro.product.model")?.replace('_', ' '),
                product = bannerValue(banner, "ro.product.name"),
                deviceName = bannerValue(banner, "ro.product.device"),
            ),
        )
    }

    /**
     * the live connection, opening one if there isn't one.
     *
     * Held rather than made per call: the handshake is a round trip plus an RSA signature, and it is
     * also what raises the "Allow debugging?" dialog — reconnecting per command would ask again.
     */
    private suspend fun open(): AdbOutcome<AdbDeviceConnection> = lock.withLock {
        connection?.let { return@withLock AdbOutcome.Ok(it) }
        state.value = DeviceState.CONNECTING
        when (val outcome = AdbDeviceConnection.open(host, port)) {
            is AdbOutcome.Err -> {
                // an unauthorized device is a real state a row can show, not a failure to hide
                state.value =
                    if (outcome.reason == AdbError.UNAUTHORIZED) DeviceState.UNAUTHORIZED
                    else DeviceState.OFFLINE
                log.w("open: $serial: ${outcome.message}")
                outcome
            }
            is AdbOutcome.Ok -> {
                connection = outcome.value
                state.value = DeviceState.DEVICE
                outcome
            }
        }
    }

    /** run [block] on the connection, dropping it if the connection turns out to be dead. */
    private suspend fun <T> onConnection(
        block: suspend (AdbDeviceConnection) -> AdbOutcome<T>,
    ): AdbOutcome<T> {
        val live = when (val opened = open()) {
            is AdbOutcome.Err -> return opened
            is AdbOutcome.Ok -> opened.value
        }
        val outcome = block(live)
        if (outcome is AdbOutcome.Err && outcome.reason == AdbError.DISCONNECTED) {
            lock.withLock {
                if (connection === live) {
                    connection = null
                    state.value = DeviceState.OFFLINE
                }
            }
            live.close()
        }
        return outcome
    }

    override suspend fun exec(serial: String, command: String): Result<String> =
        onConnection { it.exec("exec:$command") }.map { it.decodeToString() }.toResult()

    override suspend fun properties(serial: String): Result<Map<String, String>> =
        exec(serial, "getprop").map { parseGetProp(it) }

    /**
     * `screencap -p` over `exec:`.
     *
     * `exec:` and not `shell:` for the same reason as everywhere else in this app: `shell:` rewrites
     * every `\n` as `\r\n`, and doing that to a PNG corrupts it.
     */
    override suspend fun screenshot(serial: String): Result<ByteArray> =
        onConnection { it.exec("exec:screencap -p", AdbStream.TRANSFER_MS) }.toResult()

    /** [CMD_PREVIEW_STILL], on the same shared connection. */
    override suspend fun previewStill(serial: String): Result<ScreenStill> =
        onConnection { it.exec("exec:$CMD_PREVIEW_STILL", AdbStream.TRANSFER_MS) }.toResult()
            .mapCatching { parsePreviewStill(it).getOrThrow() }

    /**
     * `logcat` on the device, drained off one stream of the shared connection.
     *
     * Batched on the same 100ms flush the adb-server route uses — a busy device prints thousands of
     * lines a second, and an emission each would be thousands of recompositions.
     */
    override fun logcat(serial: String): Flow<LogBatch> = channelFlow {
        val live = when (val opened = open()) {
            is AdbOutcome.Err -> {
                log.w("logcat: $serial: ${opened.message}")
                return@channelFlow
            }
            is AdbOutcome.Ok -> opened.value
        }
        val stream = when (val opened = live.open("exec:logcat -v threadtime")) {
            is AdbOutcome.Err -> {
                log.w("logcat: $serial: ${opened.message}")
                return@channelFlow
            }
            is AdbOutcome.Ok -> opened.value
        }
        log.d("logcat: $serial: started")
        try {
            val pending = mutableListOf<String>()
            // a line can be split across two chunks, so whatever follows the last newline is held
            var partial = ""
            var lastFlush = DateUtils.nowMs()
            while (isActive) {
                val chunk = stream.read() ?: break
                val text = partial + chunk.decodeToString()
                val lastNewline = text.lastIndexOf('\n')
                if (lastNewline >= 0) {
                    text.substring(0, lastNewline).split('\n').forEach { line ->
                        if (line.isNotEmpty()) pending += line.removeSuffix("\r")
                    }
                    partial = text.substring(lastNewline + 1)
                } else {
                    partial = text
                }
                val now = DateUtils.nowMs()
                if (pending.isNotEmpty() && now - lastFlush >= LOGCAT_FLUSH_MS) {
                    send(LogBatch(pending.toList()))
                    pending.clear()
                    lastFlush = now
                }
            }
            if (pending.isNotEmpty()) send(LogBatch(pending.toList()))
        } finally {
            log.d("logcat: $serial: stopped")
            stream.close()
        }
    }.flowOn(ioDispatcher)

    override val canStreamLogs: Boolean get() = true

    /**
     * a `sync:` session on this connection, for the duration of [block].
     *
     * `stat_v2`/`ls_v2` come off the connection banner rather than a `host-serial:…:features` round
     * trip, because the device already said so in its `CNXN` — and v1 is not merely older: its
     * `STAT` doesn't follow symlinks, which makes `/sdcard` unopenable, and its size field goes
     * negative past 2GB.
     */
    private suspend fun <T> withDeviceSync(
        block: suspend (AdbSync) -> AdbOutcome<T>,
    ): AdbOutcome<T> = onConnection { live ->
        val opened = when (val outcome = live.open("sync:")) {
            is AdbOutcome.Err -> return@onConnection outcome
            is AdbOutcome.Ok -> outcome.value
        }
        val v2 = "stat_v2" in live.features && "ls_v2" in live.features
        val sync = AdbSync(AdbDeviceByteStream(opened), v2)
        try {
            block(sync).also { sync.quit() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.d("withDeviceSync: $serial: ${e.message}")
            AdbOutcome.Err(AdbError.DISCONNECTED, e.message ?: "the device went away")
        } finally {
            opened.close()
        }
    }

    override suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> =
        withDeviceSync { it.list(path) }.toResult()

    override suspend fun pullFile(serial: String, path: String): Result<ByteArray> =
        withDeviceSync { it.pullBytes(path) }.toResult()

    override suspend fun pushFile(
        serial: String,
        bytes: ByteArray,
        path: String,
        mode: Int,
    ): Result<Unit> = withDeviceSync { it.push(bytes, path, mode) }.toResult()

    override suspend fun reboot(serial: String): Result<Unit> =
        onConnection { it.exec("reboot:", AdbStream.READ_MS) }.map { }.toResult()

    /**
     * there is nothing to dial: this transport *is* one device, so connecting is what [open] does.
     *
     * Adding another device is another transport, which is `DeviceManager.addConnection` — so
     * answering false here is what keeps the Connect screen pointing an address at an adb server
     * that can actually take it.
     */
    override val canConnect: Boolean get() = false

    override suspend fun disconnect(serial: String): Result<String> {
        close()
        return Result.success("disconnected from $serial")
    }

    override suspend fun probe(): Result<String> = when (val opened = open()) {
        is AdbOutcome.Err -> Result.failure(AdbException(opened))
        is AdbOutcome.Ok -> Result.success(
            bannerValue(opened.value.banner, "ro.product.model") ?: label,
        )
    }

    override fun close() {
        connection?.close()
        connection = null
        state.value = DeviceState.OFFLINE
    }

    /** one `key=value` out of a `CNXN` banner: `device::ro.product.name=x;ro.product.model=y;…`. */
    private fun bannerValue(banner: String, key: String): String? = banner
        .substringAfter("$key=", "")
        .substringBefore(';')
        .takeIf { it.isNotBlank() }

    companion object {
        /** what `adb tcpip` opens, and the only fixed port `adbd` ever listens on. */
        const val DEFAULT_PORT = 5555

        private const val LOGCAT_FLUSH_MS = 100L
    }
}
