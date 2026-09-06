package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _transport = MutableStateFlow<DeviceTransport>(AdbServerTransport())
    val transport: StateFlow<DeviceTransport> = _transport.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /** which serial the UI is looking at, kept here so every window agrees. */
    private val _selectedSerial = MutableStateFlow<String?>(null)
    val selectedSerial: StateFlow<String?> = _selectedSerial.asStateFlow()

    private var trackJob: Job? = null
    private var propertyJob: Job? = null

    /** start reading whatever transport is set. safe to call more than once. */
    fun start() {
        if (trackJob != null) return
        log.d("start: ${_transport.value.label}")
        trackJob = scope.launch {
            _transport.collectLatest { transport ->
                transport.devices().collect { devices ->
                    // the long-form list already carries a model, so a row can be drawn before any
                    // getprop has run — the details are filled in behind it
                    _devices.value = merge(devices)
                    fillInProperties(devices)
                }
            }
        }
    }

    /** point the app at a different way in — see [DeviceTransport]. */
    fun useTransport(transport: DeviceTransport) {
        log.d("useTransport: ${transport.label}")
        val previous = _transport.value
        _transport.value = transport
        _devices.value = emptyList()
        if (previous !== transport) previous.close()
    }

    fun select(serial: String?) {
        _selectedSerial.value = serial
    }

    fun device(serial: String): Device? = _devices.value.firstOrNull { it.serial == serial }

    suspend fun exec(serial: String, command: String): Result<String> =
        _transport.value.exec(serial, command)

    suspend fun screenshot(serial: String): Result<ByteArray> =
        _transport.value.screenshot(serial)

    suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> =
        _transport.value.listFiles(serial, path)

    /** one input event on the mirrored screen. */
    suspend fun sendInput(serial: String, command: String) {
        _transport.value.exec(serial, command)
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
                batteryLevel = existing.batteryLevel,
                powerStatus = existing.powerStatus,
                freeSpace = existing.freeSpace,
                totalSpace = existing.totalSpace,
                customProperties = existing.customProperties,
            )
        }
    }

    /**
     * fill in what the device list doesn't carry, behind the rows already on screen.
     *
     * One `getprop` per device that hasn't answered yet — never for one that has, since none of
     * these change while a phone is plugged in. Cancelled and restarted whenever the set changes,
     * so unplugging during a fetch doesn't leave it running.
     */
    private fun fillInProperties(devices: List<Device>) {
        propertyJob?.cancel()
        val pending = devices.filter { it.isOnline && it.os == null }
        if (pending.isEmpty()) return
        propertyJob = scope.launch {
            pending.forEach { device ->
                val props = _transport.value.properties(device.serial).getOrNull() ?: return@forEach
                _devices.value = _devices.value.map { row ->
                    if (row.serial == device.serial) row.withProperties(props) else row
                }
            }
        }
    }
}
