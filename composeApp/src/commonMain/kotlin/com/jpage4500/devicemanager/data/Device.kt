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
    /**
     * which connection this device was read through — a `DeviceTransport`'s id, stamped on by
     * `DeviceManager` as the list arrives.
     *
     * Empty only for a device nothing has stamped yet, which is how a transport builds one.
     */
    val connectionId: String = "",
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
    val imei: String? = null,
    val batteryLevel: Int? = null,
    val powerStatus: PowerStatus = PowerStatus.NONE,
    val freeSpace: Long? = null,
    val totalSpace: Long? = null,
    val customProperties: Map<String, String> = emptyMap(),
    /** host clock time this device booted, from `/proc/uptime`. */
    val bootTimeMs: Long? = null,
    /**
     * false while the device is still booting.
     *
     * defaults true: a device that hasn't been asked yet is assumed ready, so a row doesn't flicker
     * through "not ready" on the way to normal.
     */
    val isBooted: Boolean = true,
    /** last time anything was heard from this device. */
    val lastUpdateMs: Long? = null,
) {
    /**
     * what identifies this device to everything above the transport.
     *
     * **A serial is not unique.** Two desks can each have an `emulator-5554`, and a phone reading
     * both of them at once would otherwise select, mirror and log the wrong one — so identity is
     * *(connection, serial)*, written as a path under the connection's own URI. Everything above
     * [com.jpage4500.devicemanager.manager.DeviceManager] keys on this; everything below it is
     * handed the bare [serial], which is all adb has ever known about.
     *
     * Split back apart with [connectionOf] and [serialOf] — the connection id never contains a
     * `/` past its scheme, so the last one is the separator.
     */
    val id: String get() = if (connectionId.isEmpty()) serial else "$connectionId/$serial"

    val isOnline: Boolean get() = state == DeviceState.DEVICE

    /** online, and finished booting — the only state in which a command will do what you meant. */
    val isReady: Boolean get() = isOnline && isBooted

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
        /** the connection half of an [id]. */
        fun connectionOf(id: String): String = id.substringBeforeLast('/', "")

        /** the adb serial half of an [id] — what a transport is given. */
        fun serialOf(id: String): String = id.substringAfterLast('/')

        // user-set properties, keyed the same way the Swing app keys them so the two agree
        const val CUST_PROP_1 = "custom1"
        const val CUST_PROP_2 = "custom2"
        const val CUST_PROP_PHONE = "phone_number"
        const val CUST_PROP_IMEI = "imei"

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

/**
 * where a device's power is coming from, as `dumpsys battery` reports it.
 *
 * the *source* rather than a charging/discharging flag, which is what the Swing app tracks: a phone
 * on a dock and a phone on USB behave differently on a 45-device bench, and only [NONE] means it is
 * running the battery down.
 */
enum class PowerStatus {
    NONE,

    /**
     * charging, plug unknown.
     *
     * `dumpsys battery` says *which* plug is live on its "AC powered" / "USB powered" lines and
     * whether it is charging at all in `status`, and the two do not always agree — an OEM that words
     * those lines differently, or a dock that reports none of them, still sets `status: 2`. Reading
     * only the plug lines lost the charging state on exactly the devices most likely to be on a
     * charger permanently.
     */
    CHARGING,
    AC,
    USB,
    WIRELESS,
    DOCK,
    ;

    val isCharging: Boolean get() = this != NONE
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
