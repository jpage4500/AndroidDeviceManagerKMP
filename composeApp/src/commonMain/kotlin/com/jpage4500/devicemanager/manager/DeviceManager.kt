package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * the device list, and the one transport it is currently read through.
 *
 * The Swing app's `DeviceManager` is a singleton holding a jadb connection, a scheduled refresh and
 * per-device logging state. This is the same shape with the transport pulled out in front of it, so
 * the desktop can read a local adb server while a phone reads the same code over the network.
 *
 * Nothing here touches the UI thread: every call is a suspend function and the flows are collected
 * on whatever scope the caller owns.
 */
object DeviceManager {
    private val log = LoggerConfig.createLogger("DeviceManager")

    /**
     * how many devices are asked for their details at once.
     *
     * unbounded would open 45 sockets to the adb server and run 45 `dumpsys` on the host at the same
     * moment; one at a time makes a fleet take the sum of every device's latency. 12 keeps the whole
     * bench under a couple of seconds without a stampede.
     */
    private const val FETCH_LIMIT = 12

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _transport = MutableStateFlow<DeviceTransport>(transportFromSettings())
    val transport: StateFlow<DeviceTransport> = _transport.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /**
     * which devices the UI is acting on, kept here so every window agrees.
     *
     * a set rather than one serial: the desktop table multi-selects, and an action on five devices
     * at once is the whole point of an app built for 45 of them.
     */
    private val _selection = MutableStateFlow(TableSelection<Any?>())
    val selection: StateFlow<TableSelection<Any?>> = _selection.asStateFlow()

    /** the keyboard cursor — what a single-device caller (the menu bar, the phone) means. */
    val selectedSerial: StateFlow<String?> = _selection
        .map { it.lead as? String }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** every selected serial, in the order they were selected. */
    val selectedSerials: StateFlow<List<String>> = _selection
        .map { selection -> selection.keys.filterIsInstance<String>() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** serials with a command in flight; the row draws them differently, as the Swing app does. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private var trackJob: Job? = null
    private var detailJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * the transport the settings describe.
     *
     * a remote URL wins when one is set: it is a deliberate choice, where the adb host always has a
     * default. Read once at startup and again whenever Settings changes it.
     */
    fun transportFromSettings(): DeviceTransport {
        val remote = SettingsManager.getString(AppPref.REMOTE_URL)?.takeIf { it.isNotBlank() }
        return if (remote != null) {
            RemoteHttpTransport(remote, SettingsManager.getStringOrEmpty(AppPref.REMOTE_TOKEN))
        } else {
            AdbServerTransport(
                host = SettingsManager.getStringOrEmpty(AppPref.ADB_SERVER_HOST).ifBlank { "127.0.0.1" },
                port = SettingsManager.getInt(AppPref.ADB_SERVER_PORT),
            )
        }
    }

    /** start reading whatever transport is set. safe to call more than once. */
    fun start() {
        if (trackJob != null) return
        log.d("start: ${_transport.value.label}")
        startRefreshTimer()
        trackJob = scope.launch {
            _transport.collectLatest { transport ->
                transport.devices().collect { devices ->
                    // the long-form list already carries a model, so a row can be drawn before any
                    // getprop has run — the details are filled in behind it
                    _devices.value = merge(devices)
                    // remember any wireless device we can see, so the connect dialog opens with a
                    // list rather than an empty field
                    _devices.value.filter { it.isWireless && it.isOnline }.forEach(WirelessManager::remember)
                    fetchDetails(devices)
                }
            }
        }
    }

    /**
     * re-read the device list now.
     *
     * the transport pushes changes on its own — `host:track-devices-l` on the adb server, a poll on
     * the HTTP one — so this exists for the case the tracker can't see: an adb server that was
     * restarted under us, where the socket is dead but nothing errored. restarting the collection
     * opens a new one.
     */
    fun refresh() {
        log.d("refresh: restarting the tracker")
        trackJob?.cancel()
        trackJob = null
        // clearing first would blank the table for a frame; the merge in [start] keeps what's known
        start()
    }

    /** point the app at a different way in — see [DeviceTransport]. */
    fun useTransport(transport: DeviceTransport) {
        log.d("useTransport: ${transport.label}")
        val previous = _transport.value
        _transport.value = transport
        _devices.value = emptyList()
        _selection.value = TableSelection()
        if (previous !== transport) previous.close()
    }

    fun select(serial: String?) {
        _selection.value =
            if (serial == null) TableSelection() else TableSelection<Any?>().selectOnly(serial)
    }

    /** what the table hands back after a click or an arrow key. */
    fun setSelection(selection: TableSelection<Any?>) {
        _selection.value = selection
    }

    /** the devices an action should run on: the whole selection, or nothing. */
    fun selectedDevices(): List<Device> {
        val selected = _selection.value.keys
        return _devices.value.filter { it.serial in selected }
    }

    fun device(serial: String): Device? = _devices.value.firstOrNull { it.serial == serial }

    /** the friendliest name for a serial, for a window title that outlives the device. */
    fun nameOf(serial: String): String = device(serial)?.displayName ?: serial

    suspend fun exec(serial: String, command: String): Result<String> =
        _transport.value.exec(serial, command)

    suspend fun screenshot(serial: String): Result<ByteArray> =
        _transport.value.screenshot(serial)

    suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> =
        _transport.value.listFiles(serial, path)

    suspend fun pullFile(serial: String, path: String): Result<ByteArray> =
        _transport.value.pullFile(serial, path)

    suspend fun pushFile(serial: String, bytes: ByteArray, path: String): Result<Unit> =
        _transport.value.pushFile(serial, bytes, path)

    /** one input event on the mirrored screen. */
    suspend fun sendInput(serial: String, command: String) {
        _transport.value.exec(serial, command)
    }

    /**
     * battery and free space go stale on their own — nothing pushes a change — so they are re-read
     * on a timer. everything else about a device is fixed while it is plugged in.
     */
    private fun startRefreshTimer() {
        if (refreshJob != null) return
        refreshJob = scope.launch {
            var elapsedSecs = 0
            while (isActive) {
                val everySecs = SettingsManager.getInt(AppPref.DETAILS_REFRESH_SECS).coerceAtLeast(5)
                delay(everySecs * 1000L)
                elapsedSecs += everySecs

                val online = _devices.value.filter { it.isOnline }
                if (online.isEmpty()) continue
                // every so often re-read the fixed half too: a device that was still booting when it
                // was first asked has no properties at all, and nothing else would ever go back
                val fullMins = SettingsManager.getInt(AppPref.FULL_REFRESH_MINS)
                val full = fullMins > 0 && elapsedSecs >= fullMins * 60
                if (full) elapsedSecs = 0
                fetchDetails(online, changingOnly = !full, force = full)
            }
        }
    }

    /**
     * keep what an earlier refresh learned.
     *
     * the tracker only ever knows what `host:devices-l` carries, so a straight replace would throw
     * away the battery level and the OS version every time a device was plugged in anywhere.
     */
    private fun merge(incoming: List<Device>): List<Device> {
        val known = _devices.value.associateBy { it.serial }
        return incoming.map { device ->
            val existing = known[device.serial] ?: return@map device
            device.copy(
                nickname = existing.nickname,
                os = existing.os,
                sdk = existing.sdk,
                carrier = existing.carrier,
                timezone = existing.timezone,
                phone = existing.phone,
                imei = existing.imei,
                batteryLevel = existing.batteryLevel,
                powerStatus = existing.powerStatus,
                freeSpace = existing.freeSpace,
                totalSpace = existing.totalSpace,
                customProperties = existing.customProperties,
                bootTimeMs = existing.bootTimeMs,
            )
        }
    }

    /**
     * fill in what the device list doesn't carry, behind the rows already on screen.
     *
     * The tracker only ever knows what `host:devices-l` says, so everything in the table past the
     * model comes from here — see [DeviceDetails], which owns the commands and their parsers.
     *
     * Two things make this fast enough for 45 devices, and both were measured on the way:
     *
     * - **One round trip per phase, not one per command.** Every `exec:` opens a socket to the adb
     *   server, so five commands across 45 devices was 225 of them. Batched, it is 45.
     * - **Devices in parallel**, bounded by [FETCH_LIMIT]. Sequential, a fleet took as long as the
     *   sum of every device's latency.
     *
     * Split in two, as the Swing app splits it: the **fixed** half (properties, nickname, phone,
     * IMEI) is read once per device and never again while it stays plugged in, and the **changing**
     * half (battery, free space) is read every time.
     */
    private fun fetchDetails(
        devices: List<Device>,
        changingOnly: Boolean = false,
        force: Boolean = false,
    ) {
        detailJob?.cancel()
        val online = devices.filter { it.isOnline }
        if (online.isEmpty()) return
        detailJob = scope.launch {
            val timer = Timer()
            val gate = Semaphore(FETCH_LIMIT)
            online.map { device ->
                async {
                    gate.withPermit {
                        // the changing half also answers whether the device has finished booting,
                        // and there is no point asking a half-booted device for its properties
                        val booted = fetchChanging(device.serial)
                        if (booted && !changingOnly && (force || device.os == null)) {
                            fetchFixed(device.serial)
                        }
                    }
                }
            }.awaitAll()
            log.d("fetchDetails: ${online.size} devices in $timer")
        }
    }

    /** what a device only has to be asked once. */
    private suspend fun fetchFixed(serial: String) {
        val timer = Timer()
        val output = _transport.value.exec(serial, DeviceDetails.FIXED_BATCH).getOrNull()
        if (output == null) {
            log.w("fetchFixed: $serial: no answer")
            return
        }
        val parts = DeviceDetails.splitBatch(output, DeviceDetails.FIXED_COUNT)
        val props = parseGetProp(parts[0])
        val nickname = DeviceDetails.parseNickname(parts[1])
        // a tablet or a TV has no iphonesubinfo service at all, which is not a failure
        val phone = DeviceDetails.parsePhone(parts[2]) ?: DeviceDetails.parsePhone(parts[3])
        val imei = DeviceDetails.parseImei(parts[4])
        val custom = DeviceDetails.parseCustomProps(parts[5])

        update(serial) { device ->
            device.withProperties(props).copy(
                nickname = nickname ?: device.nickname,
                phone = phone ?: device.phone,
                imei = imei ?: device.imei,
                customProperties = custom,
            )
        }
        log.d("fetchFixed: $serial: ${props.size} props in $timer")
    }

    /** what goes stale: the battery, the disk, and whether the device has finished booting. */
    private suspend fun fetchChanging(serial: String): Boolean {
        val output = _transport.value.exec(serial, DeviceDetails.CHANGING_BATCH).getOrNull()
        if (output == null) {
            log.w("fetchChanging: $serial: no answer")
            return false
        }
        val parts = DeviceDetails.splitBatch(output, DeviceDetails.CHANGING_COUNT)
        val disk = DeviceDetails.parseDiskSpace(parts[0])
        val battery = DeviceDetails.parseBattery(parts[1])
        val bootTimeMs = DeviceDetails.parseBootTime(parts[2], DateUtils.nowMs())
        val booted = parts[3].trim() == "1"
        update(serial) { device ->
            device.copy(
                // a failed read keeps the last good value rather than blanking the cell — the number
                // was true when it was read, and an empty cell reads as "unknown" instead of "stale"
                freeSpace = disk.freeBytes ?: device.freeSpace,
                totalSpace = disk.totalBytes ?: device.totalSpace,
                batteryLevel = battery.level ?: device.batteryLevel,
                powerStatus = battery.powerStatus,
                bootTimeMs = bootTimeMs ?: device.bootTimeMs,
                isBooted = booted,
                lastUpdateMs = DateUtils.nowMs(),
            )
        }
        return booted
    }

    // ── actions on a device ─────────────────────────────────────────────────────────────────────

    /**
     * run [block] with the device marked busy and the status bar saying what is happening.
     *
     * a counter would be needed if two commands could overlap on one device; they can't — the row's
     * menu is the only way in and it closes on click.
     */
    private suspend fun <T> withStatus(
        serial: String,
        label: String,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val name = device(serial)?.displayName ?: serial
        StatusManager.post("$label $name", progress = 0)
        _busy.value = _busy.value + serial
        return try {
            block().also { result ->
                result.onSuccess { StatusManager.post("$label $name", progress = 100) }
                result.onFailure { StatusManager.post("$label $name: ${it.message}", isError = true) }
            }
        } finally {
            _busy.value = _busy.value - serial
        }
    }

    /**
     * rename a device, on the device itself.
     *
     * `settings put global device_name` is where Android's own Settings screen keeps it, so the name
     * follows the device to another desk — and to the Swing app.
     */
    suspend fun setNickname(serial: String, name: String): Result<Unit> = withStatus(serial, "Renaming") {
        log.d("setNickname: $serial: $name")
        val result = _transport.value.exec(serial, DeviceDetails.setNicknameCommand(name))
        if (result.isSuccess) update(serial) { it.copy(nickname = name.ifBlank { null }) }
        result.map { }
    }

    /**
     * set one of the labels a person types, on the device.
     *
     * kept in a properties file on the device rather than in this app's settings, the way the Swing
     * app does it: the label belongs to the phone, not to whichever machine it is plugged into. The
     * whole file is rewritten because there is no way to edit one line of it over adb.
     */
    suspend fun setCustomProperty(serial: String, key: String, value: String): Result<Unit> =
        withStatus(serial, "Saving $key on") {
            val device = device(serial)
                ?: return@withStatus Result.failure(IllegalStateException("no such device"))
            val props = device.customProperties.toMutableMap()
            if (value.isBlank()) props.remove(key) else props[key] = value
            log.d("setCustomProperty: $serial: $key")
            val result = _transport.value.exec(serial, DeviceDetails.writeCustomPropsCommand(props))
            if (result.isSuccess) update(serial) { it.copy(customProperties = props) }
            result.map { }
        }

    suspend fun reboot(serial: String): Result<Unit> = withStatus(serial, "Restarting") {
        log.i("reboot: $serial")
        _transport.value.reboot(serial)
    }

    /** drop a wireless device; a USB one can't be disconnected. */
    suspend fun disconnect(serial: String): Result<String> = withStatus(serial, "Disconnecting") {
        log.i("disconnect: $serial")
        _transport.value.disconnect(serial)
    }

    /** dial a wireless device back up, at the address its serial already is. */
    suspend fun reconnect(serial: String): Result<String> {
        val host = serial.substringBeforeLast(':', "")
        val port = serial.substringAfterLast(':', "").toIntOrNull()
        if (host.isEmpty() || port == null) {
            return Result.failure(IllegalArgumentException("$serial isn't an address"))
        }
        log.i("reconnect: $host:$port")
        return _transport.value.connect(host, port)
    }

    suspend fun connect(host: String, port: Int): Result<String> {
        log.i("connect: $host:$port")
        StatusManager.post("Connecting to $host:$port", progress = 0)
        return _transport.value.connect(host, port).also { result ->
            result.onSuccess { StatusManager.post(it, progress = 100) }
            result.onFailure { StatusManager.post(it.message ?: "connect failed", isError = true) }
        }
    }

    /** run any shell command, saying so in the status bar. */
    suspend fun runCommand(serial: String, command: String, label: String = "Running on"): Result<String> =
        withStatus(serial, label) { _transport.value.exec(serial, command) }

    /**
     * forget an offline device.
     *
     * only ever a local act: the tracker rebuilds its list from the server, so a device that is
     * still attached comes straight back — which is the right answer for a USB one.
     */
    fun remove(serial: String) {
        log.d("remove: $serial")
        _devices.value = _devices.value.filterNot { it.serial == serial }
        _selection.value = _selection.value.let { TableSelection(it.keys - serial, it.anchor, it.lead) }
    }

    /** replace one row in place, leaving the rest of the list alone. */
    private fun update(serial: String, change: (Device) -> Device) {
        _devices.value = _devices.value.map { if (it.serial == serial) change(it) else it }
    }
}
