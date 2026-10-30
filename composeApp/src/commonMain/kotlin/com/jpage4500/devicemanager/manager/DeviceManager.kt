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
 * the device list, and every connection it is read through.
 *
 * The Swing app's `DeviceManager` is a singleton holding a jadb connection, a scheduled refresh and
 * per-device logging state. This is the same shape with the transport pulled out in front of it, so
 * the desktop can read a local adb server while a phone reads the same code over the network.
 *
 * **Several at once.** The list is the *union* of what every connection reports: a desk at the
 * office, a desk at home and a device reached directly are three [DeviceTransport]s and one table.
 * The desktop is unaffected by that — one connection behaves exactly as the single transport it had
 * before — and it is what a phone needs, since a phone collects desks where a desk has one adb
 * server and that is the whole story. See docs/MOBILE-UI.md.
 *
 * Two things follow from it, and both are load-bearing:
 *
 * - **A device is identified by [Device.id], not by its serial.** Two desks can each have an
 *   `emulator-5554`. Everything above this object keys on the id; everything below it is handed the
 *   bare serial, which is all adb has ever known about.
 * - **Nothing is global below here.** An adb server's address rides on [AdbServer] rather than on a
 *   var somewhere, so two of them can't overwrite each other between a call and its reply.
 *
 * Nothing here touches the UI thread: every call is a suspend function and the flows are collected
 * on whatever scope the caller owns.
 */
object DeviceManager {
    private val log = LoggerConfig.createLogger("DeviceManager")

    /**
     * how many devices are asked for their details at once, per connection.
     *
     * unbounded would open 45 sockets to the adb server and run 45 `dumpsys` on the host at the same
     * moment; one at a time makes a fleet take the sum of every device's latency. 12 keeps the whole
     * bench under a couple of seconds without a stampede. Per connection rather than shared, because
     * the limit is about one server's host and two servers are two machines.
     */
    private const val FETCH_LIMIT = 12

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** every way in the app is currently reading, in the order they were added. */
    private val _connections = MutableStateFlow<List<DeviceTransport>>(emptyList())
    val connections: StateFlow<List<DeviceTransport>> = _connections.asStateFlow()

    /** which connection the settings describe — replaced, not added to, when they change. */
    private val _settingsId = MutableStateFlow<String?>(null)

    /** the connection Settings ▸ Connection is talking about, once it is up. */
    val settingsConnection: StateFlow<DeviceTransport?> =
        combine(_connections, _settingsId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /**
     * which devices the UI is acting on, kept here so every window agrees.
     *
     * a set rather than one id: the desktop table multi-selects, and an action on five devices at
     * once is the whole point of an app built for 45 of them.
     */
    private val _selection = MutableStateFlow(TableSelection<Any?>())
    val selection: StateFlow<TableSelection<Any?>> = _selection.asStateFlow()

    /** the keyboard cursor — what a single-device caller (the menu bar, the phone) means. */
    val selectedId: StateFlow<String?> = _selection
        .map { it.lead as? String }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** every selected device id, in the order they were selected. */
    val selectedIds: StateFlow<List<String>> = _selection
        .map { selection -> selection.keys.filterIsInstance<String>() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** device ids with a command in flight; the row draws them differently, as the Swing app does. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    // one tracker and one detail fetch per connection, so a slow desk can't cancel a fast one's work
    private val trackJobs = mutableMapOf<String, Job>()
    private val detailJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private var started = false

    /**
     * the transport the settings describe.
     *
     * a remote URL wins when one is set: it is a deliberate choice, where the adb host always has a
     * default. Read once at startup and again whenever Settings changes it.
     */
    fun transportFromSettings(): DeviceTransport {
        val remote = SettingsManager.getString(AppPref.REMOTE_URL)?.takeIf { it.isNotBlank() }
            // an explicit "adb server" ignores a remote URL that's still filled in, rather than
            // making the user clear the field to get back to adb
            ?.takeIf { SettingsManager.getEnum<TransportChoice>(AppPref.TRANSPORT) != TransportChoice.ADB_SERVER }
        return if (remote != null) {
            RemoteHttpTransport(remote, SettingsManager.getStringOrEmpty(AppPref.REMOTE_TOKEN))
        } else {
            AdbServerTransport(
                host = SettingsManager.getStringOrEmpty(AppPref.ADB_SERVER_HOST).ifBlank { "127.0.0.1" },
                port = SettingsManager.getInt(AppPref.ADB_SERVER_PORT),
            )
        }
    }

    /** start reading everything this install is pointed at. safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        log.d("start")
        startRefreshTimer()
        applySettings()
        // a saved Device Manager instance is a connection in its own right, so the ones that were
        // connected when the app last closed come back the same way the settings one does
        ServerManager.restore()
    }

    /**
     * point the settings' connection at whatever the settings now say, leaving the rest alone.
     *
     * The old one is dropped even when the address hasn't changed, because a *token* can change
     * under the same URL — a duplicate id is otherwise left where it is, which would keep reading
     * with the credentials that were just replaced.
     */
    fun applySettings() {
        val next = transportFromSettings()
        _settingsId.value?.let { removeConnection(it) }
        _settingsId.value = next.id
        addConnection(next)
    }

    /**
     * read [transport] as well as everything already attached.
     *
     * A connection already reading the same address is left alone and the new one is closed: an id
     * is derived from where a transport goes, so "the same server twice" is a duplicate rather than
     * a second desk — which is also what keeps a saved server and a remote URL in Settings from
     * showing every device twice.
     */
    fun addConnection(transport: DeviceTransport) {
        if (_connections.value.any { it.id == transport.id }) {
            log.d("addConnection: ${transport.id} is already attached")
            transport.close()
            return
        }
        log.i("addConnection: ${transport.id}")
        _connections.value = _connections.value + transport
        track(transport)
    }

    /** stop reading one connection, and drop the rows that came from it. */
    fun removeConnection(id: String) {
        val transport = _connections.value.firstOrNull { it.id == id } ?: return
        log.i("removeConnection: $id")
        trackJobs.remove(id)?.cancel()
        detailJobs.remove(id)?.cancel()
        _connections.value = _connections.value.filterNot { it.id == id }
        _devices.update { list -> list.filterNot { it.connectionId == id } }
        dropSelection { key -> key is String && Device.connectionOf(key) == id }
        transport.close()
    }

    /** the connection a device id names, or null when it has been disconnected since. */
    fun connectionFor(deviceId: String): DeviceTransport? {
        val connectionId = Device.connectionOf(deviceId)
        val attached = _connections.value
        // an id carrying no connection is a bare serial, which can only be unambiguous when there
        // is exactly one connection to have meant
        if (connectionId.isEmpty()) return attached.singleOrNull()
        return attached.firstOrNull { it.id == connectionId }
    }

    /**
     * re-read every device list now.
     *
     * the transports push changes on their own — `host:track-devices-l` on an adb server, a poll on
     * the HTTP one — so this exists for the case a tracker can't see: an adb server that was
     * restarted under us, where the socket is dead but nothing errored. restarting the collection
     * opens a new one.
     */
    fun refresh() {
        log.d("refresh: restarting ${_connections.value.size} trackers")
        // nothing is cleared first: the merge in [setDevices] keeps what is already known, where
        // blanking the table would cost a frame of empty rows
        _connections.value.forEach { track(it) }
    }

    /**
     * collect one connection's device list for as long as it is attached *and* on screen.
     *
     * [whileForeground] is what closes the tracker when a phone is backgrounded and opens it again
     * on the way back — a screen that went off otherwise left the `/api/info` poll dialling a desk
     * nobody was looking at. Cancelling the collection is the whole of the teardown: the adb
     * tracker's socket is closed by `AdbDevices.track`'s own `finally`, and the HTTP one's poll loop
     * simply stops. Coming back re-enters this and opens a new one, which is exactly what [refresh]
     * does for an adb server that was restarted underneath us.
     *
     * The rows are deliberately *not* cleared on the way out: a device list nobody can see does not
     * need blanking, and keeping it means coming back to the table that was there rather than to a
     * frame of nothing.
     */
    private fun track(transport: DeviceTransport) {
        trackJobs.remove(transport.id)?.cancel()
        trackJobs[transport.id] = scope.launch {
            whileForeground {
                transport.devices().collect { devices ->
                    // the connection is stamped on here and nowhere else, so a transport never has
                    // to know its own devices' ids
                    val stamped = devices.map { it.copy(connectionId = transport.id) }
                    setDevices(transport, stamped)
                    // remember any wireless device we can see, so the connect dialog opens with a
                    // list rather than an empty field
                    WirelessManager.remember(transport.id, stamped)
                    fetchDetails(transport, stamped)
                }
            }
        }
    }

    /**
     * replace one connection's rows, leaving every other connection's alone.
     *
     * Rows follow the order connections were added, so a desk coming up doesn't reshuffle a list
     * somebody is reading — and a connection that has gone drops out of the table by not being in
     * [connections] any more.
     */
    private fun setDevices(transport: DeviceTransport, incoming: List<Device>) {
        _devices.update { current ->
            // the long-form list already carries a model, so a row can be drawn before any getprop
            // has run — the details are filled in behind it. A transport that carries the details
            // itself is taken as it comes: merging would hold the first battery reading forever,
            // since merge exists to protect values this list has no way of knowing
            val mine = if (transport.hasDeviceDetails) incoming else merge(current, transport.id, incoming)
            val byConnection = current.groupBy { it.connectionId } + (transport.id to mine)
            _connections.value.flatMap { byConnection[it.id].orEmpty() }
        }
    }

    fun select(deviceId: String?) {
        _selection.value =
            if (deviceId == null) TableSelection() else TableSelection<Any?>().selectOnly(deviceId)
    }

    /** what the table hands back after a click or an arrow key. */
    fun setSelection(selection: TableSelection<Any?>) {
        _selection.value = selection
    }

    /** the devices an action should run on: the whole selection, or nothing. */
    fun selectedDevices(): List<Device> {
        val selected = _selection.value.keys
        return _devices.value.filter { it.id in selected }
    }

    fun device(deviceId: String): Device? = _devices.value.firstOrNull { it.id == deviceId }

    /** the friendliest name for a device id, for a window title that outlives the device. */
    fun nameOf(deviceId: String): String = device(deviceId)?.displayName ?: Device.serialOf(deviceId)

    suspend fun exec(deviceId: String, command: String): Result<String> =
        on(deviceId) { transport, serial -> transport.exec(serial, command) }

    suspend fun screenshot(deviceId: String): Result<ByteArray> =
        on(deviceId) { transport, serial -> transport.screenshot(serial) }

    /**
     * a screenshot saved to the app's `screenshots` folder, with a toast naming the file — the
     * selection bar's Screenshot, a row's menu and ⌘S all come here.
     */
    suspend fun saveScreenshot(device: Device) {
        screenshot(device.id)
            .onSuccess { bytes ->
                val name = "${device.displayName}-${DateUtils.formatDate("yyyyMMdd-HHmmss")}.png"
                val ok = StorageManager.writeBytes(PATH_SCREENSHOTS, name, bytes)
                log.d("saveScreenshot: $name, ${bytes.size} bytes, ok:$ok")
                ToastManager.showToast(if (ok) name else "Couldn't save the screenshot", success = ok)
            }
            .onFailure {
                log.e("saveScreenshot: ${it.message}")
                ToastManager.showToast(it.message ?: "Screenshot failed", success = false)
            }
    }

    /** the selected devices an action can run on now — the selection bar's rule, for the menu bar too. */
    fun readySelection(): List<Device> = selectedDevices().filter { it.isReady }

    suspend fun listFiles(deviceId: String, path: String): Result<List<DeviceFile>> =
        on(deviceId) { transport, serial -> transport.listFiles(serial, path) }

    suspend fun pullFile(deviceId: String, path: String): Result<ByteArray> =
        on(deviceId) { transport, serial -> transport.pullFile(serial, path) }

    suspend fun pushFile(deviceId: String, bytes: ByteArray, path: String): Result<Unit> =
        on(deviceId) { transport, serial -> transport.pushFile(serial, bytes, path) }

    /**
     * one device's logcat, off the connection that device came in on.
     *
     * routed here rather than opened by the log window itself: with two desks attached, a stream
     * that found its own way to an adb server would happily read the other one's `emulator-5554`.
     */
    fun logcat(deviceId: String): Flow<LogBatch> =
        connectionFor(deviceId)?.logcat(Device.serialOf(deviceId)) ?: emptyFlow()

    /** whether [logcat] will produce anything for this device. */
    fun canStreamLogs(deviceId: String): Boolean = connectionFor(deviceId)?.canStreamLogs == true

    /** whether [logcat] brings process names with it — see [DeviceTransport.hasProcessNames]. */
    fun hasProcessNames(deviceId: String): Boolean =
        connectionFor(deviceId)?.hasProcessNames == true

    /**
     * a mirrored screen pushed off the connection this device came in on, or null when the only way
     * to see one is to ask for a frame at a time — see [MirrorSource], which does both.
     */
    fun screenStream(deviceId: String, intervalMs: Long): ScreenStream? =
        connectionFor(deviceId)?.screenStream(Device.serialOf(deviceId), intervalMs)

    /**
     * one input event, as a shell command.
     *
     * The route of last resort for a gesture: a connection with a [ScreenStream] carries its own
     * input, and this is what an adb server has instead. Nothing to show for it, but a refusal is
     * worth saying.
     */
    suspend fun sendInput(deviceId: String, event: DeviceInput.Event) {
        val command = DeviceInput.shell(event)
        exec(deviceId, command).onFailure { log.w("sendInput: $command: ${it.message}") }
    }

    /** run [block] against the connection [deviceId] belongs to, with the bare serial. */
    private suspend fun <T> on(
        deviceId: String,
        block: suspend (DeviceTransport, String) -> Result<T>,
    ): Result<T> {
        val transport = connectionFor(deviceId)
            ?: return Result.failure(IllegalStateException("${Device.connectionOf(deviceId)} isn't connected"))
        return block(transport, Device.serialOf(deviceId))
    }

    /**
     * battery and free space go stale on their own — nothing pushes a change — so they are re-read
     * on a timer. everything else about a device is fixed while it is plugged in.
     *
     * Stopped while the app is in the background, like the trackers: two shell commands per device
     * per minute, for a battery percentage on a screen nobody is looking at, is the clearest waste
     * of somebody's phone in the app. The elapsed count lives inside [whileForeground] so a session
     * starts its slow full-refresh cadence from the moment the app comes back.
     */
    private fun startRefreshTimer() {
        if (refreshJob != null) return
        refreshJob = scope.launch {
            whileForeground {
                var elapsedSecs = 0
                // the collection's own context, not the launch's: this body is cancelled on the way
                // to the background while the job it was launched from stays active
                while (currentCoroutineContext().isActive) {
                    val everySecs =
                        SettingsManager.getInt(AppPref.DETAILS_REFRESH_SECS).coerceAtLeast(5)
                    delay(everySecs * 1000L)
                    elapsedSecs += everySecs

                    // every so often re-read the fixed half too: a device that was still booting
                    // when it was first asked has no properties at all, and nothing else would ever
                    // go back
                    val fullMins = SettingsManager.getInt(AppPref.FULL_REFRESH_MINS)
                    val full = fullMins > 0 && elapsedSecs >= fullMins * 60
                    if (full) elapsedSecs = 0

                    _connections.value.forEach { transport ->
                        val online = _devices.value.filter {
                            it.connectionId == transport.id && it.isOnline
                        }
                        if (online.isNotEmpty()) {
                            fetchDetails(transport, online, changingOnly = !full, force = full)
                        }
                    }
                }
            }
        }
    }

    /**
     * keep what an earlier refresh learned, for one connection's rows.
     *
     * the tracker only ever knows what `host:devices-l` carries, so a straight replace would throw
     * away the battery level and the OS version every time a device was plugged in anywhere.
     */
    private fun merge(current: List<Device>, connectionId: String, incoming: List<Device>): List<Device> {
        val known = current.filter { it.connectionId == connectionId }.associateBy { it.serial }
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
     * fill in what a device list doesn't carry, behind the rows already on screen.
     *
     * The tracker only ever knows what `host:devices-l` says, so everything in the table past the
     * model comes from here — see [DeviceDetails], which owns the commands and their parsers.
     *
     * Two things make this fast enough for 45 devices, and both were measured on the way:
     *
     * - **One round trip per phase, not one per command.** Every `exec:` opens a socket to the adb
     *   server, so five commands across 45 devices was 225 of them. Batched, it is 45.
     * - **Devices in parallel**, bounded by [FETCH_LIMIT].
     *
     * Split in two, as the Swing app splits it: the **fixed** half (properties, nickname, phone,
     * IMEI) is read once per device and never again while it stays plugged in, and the **changing**
     * half (battery, free space) is read every time.
     */
    private fun fetchDetails(
        transport: DeviceTransport,
        devices: List<Device>,
        changingOnly: Boolean = false,
        force: Boolean = false,
    ) {
        // nothing to fetch when the list already said it — see DeviceTransport.hasDeviceDetails
        if (transport.hasDeviceDetails) return
        detailJobs.remove(transport.id)?.cancel()
        val online = devices.filter { it.isOnline }
        if (online.isEmpty()) return
        detailJobs[transport.id] = scope.launch {
            val timer = Timer()
            val gate = Semaphore(FETCH_LIMIT)
            online.map { device ->
                async {
                    gate.withPermit {
                        // the changing half also answers whether the device has finished booting,
                        // and there is no point asking a half-booted device for its properties
                        val booted = fetchChanging(transport, device)
                        if (booted && !changingOnly && (force || device.os == null)) {
                            fetchFixed(transport, device)
                        }
                    }
                }
            }.awaitAll()
            log.d("fetchDetails: ${transport.id}: ${online.size} devices in $timer")
        }
    }

    /** what a device only has to be asked once. */
    private suspend fun fetchFixed(transport: DeviceTransport, device: Device) {
        val timer = Timer()
        val output = transport.exec(device.serial, DeviceDetails.FIXED_BATCH).getOrNull()
        if (output == null) {
            log.w("fetchFixed: ${device.id}: no answer")
            return
        }
        val parts = DeviceDetails.splitBatch(output, DeviceDetails.FIXED_COUNT)
        val props = parseGetProp(parts[0])
        val nickname = DeviceDetails.parseNickname(parts[1])
        // a tablet or a TV has no iphonesubinfo service at all, which is not a failure
        val phone = DeviceDetails.parsePhone(parts[2]) ?: DeviceDetails.parsePhone(parts[3])
        val imei = DeviceDetails.parseImei(parts[4])
        val custom = DeviceDetails.parseCustomProps(parts[5])

        update(device.id) { row ->
            row.withProperties(props).copy(
                nickname = nickname ?: row.nickname,
                phone = phone ?: row.phone,
                imei = imei ?: row.imei,
                customProperties = custom,
            )
        }
        log.d("fetchFixed: ${device.id}: ${props.size} props in $timer")
    }

    /** what goes stale: the battery, the disk, and whether the device has finished booting. */
    private suspend fun fetchChanging(transport: DeviceTransport, device: Device): Boolean {
        val output = transport.exec(device.serial, DeviceDetails.CHANGING_BATCH).getOrNull()
        if (output == null) {
            log.w("fetchChanging: ${device.id}: no answer")
            return false
        }
        val parts = DeviceDetails.splitBatch(output, DeviceDetails.CHANGING_COUNT)
        val disk = DeviceDetails.parseDiskSpace(parts[0])
        val battery = DeviceDetails.parseBattery(parts[1])
        val bootTimeMs = DeviceDetails.parseBootTime(parts[2], DateUtils.nowMs())
        val booted = parts[3].trim() == "1"
        update(device.id) { row ->
            row.copy(
                // a failed read keeps the last good value rather than blanking the cell — the number
                // was true when it was read, and an empty cell reads as "unknown" instead of "stale"
                freeSpace = disk.freeBytes ?: row.freeSpace,
                totalSpace = disk.totalBytes ?: row.totalSpace,
                batteryLevel = battery.level ?: row.batteryLevel,
                powerStatus = battery.powerStatus,
                bootTimeMs = bootTimeMs ?: row.bootTimeMs,
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
        deviceId: String,
        label: String,
        block: suspend () -> Result<T>,
    ): Result<T> {
        val name = nameOf(deviceId)
        StatusManager.post("$label $name", progress = 0)
        _busy.value = _busy.value + deviceId
        return try {
            block().also { result ->
                result.onSuccess { StatusManager.post("$label $name", progress = 100) }
                result.onFailure { StatusManager.post("$label $name: ${it.message}", isError = true) }
            }
        } finally {
            _busy.value = _busy.value - deviceId
        }
    }

    /**
     * rename a device, on the device itself.
     *
     * `settings put global device_name` is where Android's own Settings screen keeps it, so the name
     * follows the device to another desk — and to the Swing app.
     */
    suspend fun setNickname(deviceId: String, name: String): Result<Unit> =
        withStatus(deviceId, "Renaming") {
            log.d("setNickname: $deviceId: $name")
            val result = exec(deviceId, DeviceDetails.setNicknameCommand(name))
            if (result.isSuccess) update(deviceId) { it.copy(nickname = name.ifBlank { null }) }
            result.map { }
        }

    /**
     * set one of the labels a person types, on the device.
     *
     * kept in a properties file on the device rather than in this app's settings, the way the Swing
     * app does it: the label belongs to the phone, not to whichever machine it is plugged into. The
     * whole file is rewritten because there is no way to edit one line of it over adb.
     */
    suspend fun setCustomProperty(deviceId: String, key: String, value: String): Result<Unit> =
        withStatus(deviceId, "Saving $key on") {
            val device = device(deviceId)
                ?: return@withStatus Result.failure(IllegalStateException("no such device"))
            val props = device.customProperties.toMutableMap()
            if (value.isBlank()) props.remove(key) else props[key] = value
            log.d("setCustomProperty: $deviceId: $key")
            val result = exec(deviceId, DeviceDetails.writeCustomPropsCommand(props))
            if (result.isSuccess) update(deviceId) { it.copy(customProperties = props) }
            result.map { }
        }

    suspend fun reboot(deviceId: String): Result<Unit> = withStatus(deviceId, "Restarting") {
        log.i("reboot: $deviceId")
        on(deviceId) { transport, serial -> transport.reboot(serial) }
    }

    /** drop a wireless device; a USB one can't be disconnected. */
    suspend fun disconnect(deviceId: String): Result<String> = withStatus(deviceId, "Disconnecting") {
        log.i("disconnect: $deviceId")
        on(deviceId) { transport, serial -> transport.disconnect(serial) }
    }

    /**
     * dial a wireless device back up, at the address its serial already is.
     *
     * back through the same connection it was lost from: the desk that had it is the one that can
     * get it again, and a phone with two desks attached would otherwise dial from whichever
     * happened to be first.
     */
    suspend fun reconnect(deviceId: String): Result<String> {
        val serial = Device.serialOf(deviceId)
        val host = serial.substringBeforeLast(':', "")
        val port = serial.substringAfterLast(':', "").toIntOrNull()
        if (host.isEmpty() || port == null) {
            return Result.failure(IllegalArgumentException("$serial isn't an address"))
        }
        log.i("reconnect: $host:$port")
        return on(deviceId) { transport, _ -> transport.connect(host, port) }
    }

    /**
     * reach a device at an address, through a connection that can dial one.
     *
     * only an adb server can: it holds the RSA key and does the handshake, where a Device Manager
     * instance manages its own devices and has no endpoint for it.
     */
    suspend fun connect(host: String, port: Int): Result<String> {
        // 0.0.0.0 loads the invented fleet instead — see FakeTransport for why it is a route rather
        // than a debug menu. intercepted here so both Connect fields reach it
        if (host == FakeTransport.HOST) {
            addConnection(FakeTransport())
            return Result.success("showing test devices")
        }
        val transport = dialler()
            ?: return Result.failure(IllegalStateException("no adb server to connect through"))
        log.i("connect: $host:$port through ${transport.id}")
        StatusManager.post("Connecting to $host:$port", progress = 0)
        return transport.connect(host, port).also { result ->
            result.onSuccess { StatusManager.post(it, progress = 100) }
            result.onFailure { StatusManager.post(it.message ?: "connect failed", isError = true) }
        }
    }

    /** the connection an address-based action goes through: the first that can dial a device. */
    fun dialler(): DeviceTransport? = _connections.value.firstOrNull { it.canConnect }

    /** the connection that can pair — what the pairing screen asks before it shows a QR. */
    fun pairer(): DeviceTransport? = _connections.value.firstOrNull { it.canPair }

    /** run any shell command, saying so in the status bar. */
    suspend fun runCommand(
        deviceId: String,
        command: String,
        label: String = "Running on",
    ): Result<String> = withStatus(deviceId, label) { exec(deviceId, command) }

    /**
     * forget an offline device.
     *
     * only ever a local act: the tracker rebuilds its list from the server, so a device that is
     * still attached comes straight back — which is the right answer for a USB one.
     */
    fun remove(deviceId: String) {
        log.d("remove: $deviceId")
        _devices.update { list -> list.filterNot { it.id == deviceId } }
        dropSelection { it == deviceId }
    }

    /** replace one row in place, leaving the rest of the list alone. */
    private fun update(deviceId: String, change: (Device) -> Device) {
        // update rather than a read-modify-write: a fleet's detail fetches land from
        // [FETCH_LIMIT] coroutines at once, and a plain assignment loses whichever arrives second
        _devices.update { list -> list.map { if (it.id == deviceId) change(it) else it } }
    }

    /** drop whatever the selection is holding that no longer exists. */
    private fun dropSelection(gone: (Any?) -> Boolean) {
        _selection.update { current ->
            val keys = current.keys.filterNot(gone).toSet()
            if (keys.size == current.keys.size) {
                current
            } else {
                TableSelection(
                    keys = keys,
                    anchor = current.anchor?.takeUnless(gone),
                    lead = current.lead?.takeUnless(gone),
                )
            }
        }
    }
}

const val PATH_SCREENSHOTS = "screenshots"
