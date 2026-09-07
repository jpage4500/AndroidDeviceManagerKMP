package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
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

    /** note a device that is currently connected, keeping its name for when it isn't. */
    fun remember(device: Device) {
        val entry = WirelessDevice.fromSerial(device.serial, device.displayName) ?: return
        val existing = _recent.value.firstOrNull { it.serial == entry.serial }
        if (existing?.name == entry.name) return
        save(_recent.value.filterNot { it.serial == entry.serial } + entry.copy(lastSeenMs = DateUtils.nowMs()))
    }

    fun add(host: String, port: Int) {
        val entry = WirelessDevice(host, port)
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

    private fun save(list: List<WirelessDevice>) {
        // newest last, oldest dropped
        val trimmed = list.takeLast(WirelessDevice.MAX_RECENT)
        _recent.value = trimmed
        SettingsManager.setString(AppPref.RECENT_WIRELESS, JsonUtils.toJson(trimmed))
    }

    private fun load(): List<WirelessDevice> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.RECENT_WIRELESS))
}
