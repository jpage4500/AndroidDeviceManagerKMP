package com.jpage4500.devicemanager.util

/** one service answering on the local network. */
data class MdnsService(val name: String, val host: String, val port: Int)

/** a browse in progress; closing it stops it and hands the platform's handle back. */
fun interface MdnsBrowse {
    fun close()
}

/**
 * browse the local network for a Bonjour service type, e.g. `_adb-tls-pairing._tcp`.
 *
 * Platform code on every target because there is no socket library that does this — ktor-network
 * has no mDNS, and rolling multicast DNS by hand is a protocol, not a helper. Android has
 * `NsdManager` and iOS has `NSNetServiceBrowser`; the desktop has neither without a dependency, and
 * doesn't need one — see the desktop actual.
 *
 * [onFound] is called once per resolved service, possibly more than once for the same one, and on
 * whichever thread the platform resolves on. Callers should be ready for both.
 */
expect fun browseMdns(serviceType: String, onFound: (MdnsService) -> Unit): MdnsBrowse

/** how a device advertises itself while its "Pair device with QR code" screen is open. */
const val MDNS_ADB_PAIRING = "_adb-tls-pairing._tcp"

/**
 * how a device advertises the plain, un-encrypted port `adb tcpip` opens.
 *
 * The one adb service type [com.jpage4500.devicemanager.transport.DirectDeviceTransport] can
 * actually reach, which is why it is the one browsed for. A device with wireless debugging switched
 * on advertises `_adb-tls-connect._tcp` instead, and that port wants a TLS client certificate from
 * pairing — so finding it would only offer a connection that can't be made.
 */
const val MDNS_ADB_CONNECT = "_adb._tcp"
