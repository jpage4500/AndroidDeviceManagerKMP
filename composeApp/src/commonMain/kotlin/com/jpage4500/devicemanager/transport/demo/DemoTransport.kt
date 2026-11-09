package com.jpage4500.devicemanager.transport.demo

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

/**
 * Demo Mode's forty invented devices, for screenshots — Settings › About on a local build only.
 *
 * A connection like any other, which is the whole design: they go behind [DeviceTransport], so the
 * device table, Files, Logs, Commands, Input, install, mirror and the details panel all work on them
 * with no branch anywhere above this. They're told apart from real devices the way any two
 * connections are — their ids start with [ID] — so a real `emulator-5554` and the demo one can sit
 * in the same table.
 *
 * What a device says comes from [DemoShell], which answers the app's real shell commands; what its
 * screen shows from [DemoScreen]; its log from [DemoLog].
 */
class DemoTransport : DeviceTransport {
    private val log = LoggerConfig.createLogger("DemoTransport")

    override val id: String get() = ID

    override val label: String get() = "Demo devices"

    private val fleet = DemoFleet.build()
    private val shell = DemoShell(fleet)
    private val startMs = DateUtils.nowMs()

    // the shell's state is plain maps; this keeps twelve detail fetches from writing them at once
    private val lock = Mutex()

    private val states = MutableStateFlow(fleet.associate { it.serial to it.state })

    override fun devices(): Flow<List<Device>> =
        states.map { current -> fleet.map { it.toDevice().copy(state = current[it.serial] ?: it.state) } }

    override val canStreamLogs: Boolean get() = true

    override val hasProcessNames: Boolean get() = true

    override suspend fun exec(serial: String, command: String): Result<String> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        return withContext(ioDispatcher) {
            lock.withLock { Result.success(shell.run(device, command).first) }
        }
    }

    override fun logcat(serial: String, since: String?): Flow<LogBatch> {
        val device = ready(serial).getOrNull() ?: return emptyFlow()
        return DemoLog.stream(device, resuming = since != null)
    }

    override suspend fun properties(serial: String): Result<Map<String, String>> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        return lock.withLock { Result.success(shell.properties(device)) }
    }

    override suspend fun screenshot(serial: String): Result<ByteArray> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        return withContext(ioDispatcher) { Result.success(DemoScreen.render(device, DateUtils.formatDate("h:mm"))) }
    }

    override suspend fun previewStill(serial: String): Result<ScreenStill> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        if (device.screenOff) return Result.success(ScreenStill.Off)
        return screenshot(serial).map { ScreenStill.Shot(it) }
    }

    override suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        return lock.withLock { shell.files(device).list(path) }
    }

    override suspend fun pullFile(serial: String, path: String): Result<ByteArray> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        return withContext(ioDispatcher) { lock.withLock { shell.files(device).read(path) } }
    }

    override suspend fun pushFile(serial: String, bytes: ByteArray, path: String, mode: Int): Result<Unit> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        lock.withLock { shell.files(device).write(path, bytes, mode) }
        return Result.success(Unit)
    }

    /** brings a disconnected demo device back — what a wireless row's Reconnect sends. */
    override suspend fun connect(host: String, port: Int): Result<String> {
        val serial = "$host:$port"
        val device = fleet.firstOrNull { it.serial == serial }
            ?: return Result.failure(IllegalStateException("failed to connect to $serial"))
        states.update { it + (device.serial to DeviceState.DEVICE) }
        return Result.success("connected to $serial")
    }

    override suspend fun disconnect(serial: String): Result<String> {
        val device = fleet.firstOrNull { it.serial == serial && it.isWireless }
            ?: return Result.failure(IllegalStateException("$serial isn't a wireless device"))
        states.update { it + (device.serial to DeviceState.OFFLINE) }
        return Result.success("disconnected $serial")
    }

    override suspend fun reboot(serial: String): Result<Unit> {
        val device = ready(serial).getOrElse { return Result.failure(it) }
        log.d("reboot: $serial")
        lock.withLock { shell.reboot(device) }
        return Result.success(Unit)
    }

    /**
     * the device, if it would answer a command right now.
     *
     * offline and unauthorised ones refuse the way adb does, and the not-responding ones stop
     * answering after their first refresh — so the table fills their row in, and then they go stale.
     */
    private fun ready(serial: String): Result<DemoDevice> {
        val device = fleet.firstOrNull { it.serial == serial }
            ?: return Result.failure(IllegalStateException("device '$serial' not found"))
        return when (states.value[serial]) {
            DeviceState.OFFLINE -> Result.failure(IllegalStateException("device offline"))
            DeviceState.UNAUTHORIZED -> Result.failure(IllegalStateException("device unauthorized"))
            else -> if (device.goesQuiet && DateUtils.nowMs() - startMs > QUIET_AFTER_MS) {
                Result.failure(IllegalStateException("timed out waiting for $serial"))
            } else {
                Result.success(device)
            }
        }
    }

    companion object {
        /** the connection id every demo device's id starts with. */
        const val ID = "demo://devices"

        // long enough for the first refresh to fill the row in
        private const val QUIET_AFTER_MS = 15_000L
    }
}
