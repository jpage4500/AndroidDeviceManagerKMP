package com.jpage4500.devicemanager.util

/**
 * the desktop doesn't browse.
 *
 * The JVM has no mDNS of its own — the Swing app carries JmDNS for this, which is a dependency this
 * project would be taking on for one screen it doesn't need. A desktop running Device Manager is
 * already running an adb server, and `adb pair` from a terminal reaches the same place. If a desktop
 * pairing screen is ever wanted, this is the file that grows a JmDNS actual.
 */
actual fun browseMdns(serviceType: String, onFound: (MdnsService) -> Unit): MdnsBrowse {
    LoggerConfig.createLogger("Mdns").d("browseMdns: $serviceType: not supported on desktop")
    return MdnsBrowse {}
}
