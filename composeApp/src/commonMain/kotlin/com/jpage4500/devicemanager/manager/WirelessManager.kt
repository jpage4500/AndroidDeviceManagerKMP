package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

/**
 * the wireless devices this app has seen, so the connect dialog opens with a list rather than an
 * empty field.
 *
 * a device is added the moment it appears in the device list with a `host:port` serial, so the list
 * builds itself — the Swing app does the same from `ConnectDialog.addWirelessDevice`.
 */
object WirelessManager {
    private val log = LoggerConfig.createLogger("WirelessManager")

    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<WirelessDevice>> = _recent.asStateFlow()

    // Demo Mode: the list is the demo devices' alone and lives in memory, so neither list leaks
    // into the other — see [setDemo]
    private var demo = false

    // per connection, the serials online the last time its list arrived, so a device coming back is
    // stamped with a new "last connected" and one that simply stayed connected isn't re-saved on
    // every push
    private val online = mutableMapOf<String, Set<String>>()

    /**
     * note the wireless devices one connection has connected right now, keeping their names for
     * when they aren't.
     */
    fun remember(connectionId: String, devices: List<Device>) {
        val now = devices.filter { it.isWireless && it.isOnline }
        val wasOnline = online[connectionId].orEmpty()
        online[connectionId] = now.mapTo(mutableSetOf()) { it.serial }
        var list = _recent.value
        now.forEach { device ->
            val entry = WirelessDevice.fromSerial(device.serial, device.displayName) ?: return@forEach
            val existing = list.firstOrNull { it.serial == entry.serial }
            val arrived = device.serial !in wasOnline
            if (!arrived && existing?.name == entry.name) return@forEach
            val stamp = if (arrived || existing == null) DateUtils.nowMs() else existing.lastSeenMs
            list = list.filterNot { it.serial == entry.serial } + entry.copy(lastSeenMs = stamp)
        }
        if (list != _recent.value) save(list)
    }

    fun add(host: String, port: Int) {
        val entry = WirelessDevice(host, port, lastSeenMs = DateUtils.nowMs())
        if (_recent.value.any { it.serial == entry.serial }) return
        log.d("add: ${entry.serial}")
        save(_recent.value + entry)
    }

    fun forget(serial: String) {
        log.d("forget: $serial")
        save(_recent.value.filterNot { it.serial == serial })
    }

    /** the address to put in the dialog's field: whatever was connected to last. */
    fun lastAddress(): WirelessDevice? = _recent.value.maxByOrNull { it.lastSeenMs }

    /**
     * pair with a device showing a six-digit code — Wireless debugging ▸ Pair device with pairing code.
     *
     * The code dialog shows its own **pairing** port, a random one that is not the port the device
     * is connected on afterwards. The adb server has usually already seen it advertised, so it is
     * looked up first and [port] — whatever was typed — is only the fallback, which is the Swing
     * app's order and why the field can be left alone.
     */
    suspend fun pair(host: String, port: Int?, code: String): Result<String> {
        val transport = DeviceManager.pairer()
            ?: return Result.failure(IllegalStateException("no adb server to pair through"))
        val pairingPort = findPort(MDNS_ADB_PAIRING, host, PAIR_LOOKUP_MS) ?: port
            ?: return Result.failure(IllegalStateException("pairing port not found"))
        log.i("pair: $host:$pairingPort")
        return transport.pair(host, pairingPort, code)
    }

    /**
     * connect to a device that has just been paired.
     *
     * Pairing attaches nothing: the device is reached afterwards on the port it advertises as
     * `_adb-tls-connect._tcp`, so that is tried first. Some devices advertise the pairing port and
     * never the connect one, so after that come [port] and every port this address was last
     * connected on — the Swing app's `connectPairedDevice`.
     */
    suspend fun connectPaired(host: String, port: Int?): Result<String> {
        val transport = DeviceManager.dialler()
            ?: return Result.failure(IllegalStateException("no adb server to connect through"))
        val advertised = findPort(MDNS_ADB_CONNECT_TLS, host, CONNECT_LOOKUP_MS)
        val recentPorts = _recent.value.filter { it.host == host }.map { it.port }
        val ports = (listOfNotNull(advertised, port) + recentPorts).distinct()
        var failure: Throwable? = null
        for (candidate in ports) {
            log.d("connectPaired: trying $host:$candidate")
            val result = transport.connect(host, candidate)
            if (result.isSuccess) {
                add(host, candidate)
                return result
            }
            failure = result.exceptionOrNull()
        }
        return Result.failure(failure ?: IllegalStateException("wireless debugging port not found for $host"))
    }

    /** the port [host] advertises [serviceType] on, or null if nothing turns up in [timeoutMs]. */
    private suspend fun findPort(serviceType: String, host: String, timeoutMs: Long): Int? {
        val found = Channel<Int>(Channel.CONFLATED)
        // started on the main thread, as PairingSession does: iOS browses on the caller's run loop
        val browse = withContext(Dispatchers.Main) {
            browseMdns(serviceType) { if (it.host == host) found.trySend(it.port) }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { found.receive() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { browse.close() }
        }
    }

    /**
     * Demo Mode on or off: on starts an empty list that only the demo devices fill and nothing
     * saves, off reads the saved one back — so a screenshot of Connect shows no real address, and
     * the invented ones never end up in the real list.
     */
    fun setDemo(on: Boolean) {
        if (on == demo) return
        demo = on
        online.clear()
        _recent.value = if (on) emptyList() else load()
    }

    private fun save(list: List<WirelessDevice>) {
        // newest last, oldest dropped
        val trimmed = list.takeLast(WirelessDevice.MAX_RECENT)
        _recent.value = trimmed
        if (!demo) SettingsManager.setString(AppPref.RECENT_WIRELESS, JsonUtils.toJson(trimmed))
    }

    private fun load(): List<WirelessDevice> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.RECENT_WIRELESS))

    // the adb server browses continuously, so a code dialog that is up has usually been seen already
    private const val PAIR_LOOKUP_MS = 3_000L

    // a device re-advertises its connect port right after pairing, but not instantly
    private const val CONNECT_LOOKUP_MS = 10_000L
}
