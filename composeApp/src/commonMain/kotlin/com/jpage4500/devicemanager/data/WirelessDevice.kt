package com.jpage4500.devicemanager.data

import kotlinx.serialization.*

/**
 * a wireless device the user has connected to before, remembered so they don't have to type the
 * address again.
 *
 * kept in this app's own settings rather than on the device, for the obvious reason: the point of it
 * is to reach a device that isn't connected yet.
 */
@Serializable
data class WirelessDevice(
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** the friendliest name it gave last time it was reachable. */
    val name: String? = null,
    val lastSeenMs: Long = 0,
) {
    val serial: String get() = "$host:$port"

    val displayName: String get() = name ?: serial

    companion object {
        const val DEFAULT_PORT = 5555

        /** how many are remembered; the Swing app's number. */
        const val MAX_RECENT = 10

        /** `192.168.0.100:5555` back into its parts, or null if it isn't an address. */
        fun fromSerial(serial: String, name: String? = null): WirelessDevice? {
            val host = serial.substringBeforeLast(':', "")
            val port = serial.substringAfterLast(':', "").toIntOrNull()
            if (host.isEmpty() || port == null) return null
            return WirelessDevice(host, port, name)
        }
    }
}
