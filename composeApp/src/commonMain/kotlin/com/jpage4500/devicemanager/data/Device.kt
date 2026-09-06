package com.jpage4500.devicemanager.data

import com.jpage4500.devicemanager.util.*

/**
 * one attached device.
 *
 * ported from the Swing app's `data/Device.java`, minus everything that was a handle to something
 * live: the jadb device, the busy counter and the remote connection are all held by the transport
 * now, so this is a value and can cross a coroutine boundary or a serializer without ceremony.
 */
data class Device(
    val serial: String,
    /** what the adb server says the transport is doing — see [DeviceState]. */
    val state: DeviceState = DeviceState.UNKNOWN,
    /** the long-form fields `host:devices-l` carries, so a name is known before any getprop. */
    val model: String? = null,
    val product: String? = null,
    val deviceName: String? = null,
    val transportId: String? = null,

    // --- filled in by a properties fetch, not by the device list ---
    val nickname: String? = null,
    val os: String? = null,
    val sdk: String? = null,
    val carrier: String? = null,
    val timezone: String? = null,
    val phone: String? = null,
    val batteryLevel: Int? = null,
    val powerStatus: PowerStatus = PowerStatus.NONE,
    val freeSpace: Long? = null,
    val totalSpace: Long? = null,
    val customProperties: Map<String, String> = emptyMap(),
    /** last time anything was heard from this device. */
    val lastUpdateMs: Long? = null,
) {
    val isOnline: Boolean get() = state == DeviceState.DEVICE

    /** a tcp serial is `host:port`; a usb one never contains a colon followed by digits. */
    val isWireless: Boolean
        get() = serial.substringAfterLast(':', "").toIntOrNull() != null ||
            serial.contains("_adb-tls-connect._tcp")

    /** what to put in a list row — the friendliest name this device has given up so far. */
    val displayName: String
        get() = TextUtils.firstValid(nickname, model, deviceName, product) ?: serial

    fun customProperty(key: String): String? = customProperties[key]

    val storageUsedPercent: Int?
        get() {
            val free = freeSpace ?: return null
            val total = totalSpace ?: return null
            // free > total means one of them was mis-parsed; no bar beats a wrong one
            if (total <= 0 || free > total) return null
            return (((total - free).toDouble() / total) * 100).toInt()
        }

    companion object {
        // user-set properties, keyed the same way the Swing app keys them so the two agree
        const val CUST_PROP_1 = "custom1"
        const val CUST_PROP_2 = "custom2"
        const val CUST_PROP_PHONE = "phone_number"
        const val CUST_PROP_IMEI = "imei"
        const val CUST_PROP_BOOTED = "booted"

        // the getprop keys the properties fetch asks for
        const val PROP_SDK = "ro.build.version.sdk"
        const val PROP_MODEL = "ro.product.model"
        const val PROP_OS = "ro.build.version.release"
        const val PROP_CARRIER = "gsm.sim.operator.alpha"
        const val PROP_CARRIER_2 = "gsm.operator.alpha"
        const val PROP_BRAND = "ro.product.brand"
        const val PROP_NAME = "ro.product.name"
        const val PROP_TIMEZONE = "persist.sys.timezone"
    }
}

enum class PowerStatus {
    NONE,
    CHARGING,
    FULL,
    DISCHARGING,
}

/**
 * every state `atransport::connection_state_name()` can print.
 *
 * the full set matters: the long-form device list is found by matching the state **by name**, since
 * a serial can contain spaces and there is no separator to cut on. See docs/ADB-TRANSPORTS.md.
 */
enum class DeviceState(val label: String) {
    DEVICE("device"),
    OFFLINE("offline"),
    UNAUTHORIZED("unauthorized"),
    AUTHORIZING("authorizing"),
    CONNECTING("connecting"),
    DETACHED("detached"),
    BOOTLOADER("bootloader"),
    RECOVERY("recovery"),
    RESCUE("rescue"),
    SIDELOAD("sideload"),
    HOST("host"),
    NO_PERMISSIONS("no permissions"),
    UNKNOWN("unknown");

    companion object {
        /** longest label first, so `no permissions` isn't shadowed by a shorter match. */
        val byLength = entries.sortedByDescending { it.label.length }

        fun fromLabel(label: String): DeviceState =
            entries.firstOrNull { it.label == label } ?: UNKNOWN
    }
}
