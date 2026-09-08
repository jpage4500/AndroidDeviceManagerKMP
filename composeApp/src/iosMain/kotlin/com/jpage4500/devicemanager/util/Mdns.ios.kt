package com.jpage4500.devicemanager.util

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.*

private val log = LoggerConfig.createLogger("Mdns")

/**
 * `NSNetServiceBrowser`, which is Bonjour — the protocol iOS invented and still runs.
 *
 * Deprecated by Apple in favour of `NWBrowser`, and kept anyway: `NWBrowser`'s Kotlin/Native binding
 * needs a dispatch queue and a set of C blocks where this needs a delegate, and this is a screen
 * that browses for one service for a few seconds.
 *
 * A browse only carries a name; the address needs a resolve, and the resolve hands back raw
 * `sockaddr` bytes rather than a string — an adb pairing port is random, so both halves matter.
 *
 * Must be called on the main thread: `NSNetServiceBrowser` schedules itself on the current run loop,
 * and a background thread's run loop is not running.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun browseMdns(serviceType: String, onFound: (MdnsService) -> Unit): MdnsBrowse {
    val delegate = BonjourDelegate(onFound)
    val browser = NSNetServiceBrowser()
    browser.delegate = delegate
    browser.searchForServicesOfType(serviceType, inDomain = "local.")
    return MdnsBrowse {
        browser.stop()
        browser.delegate = null
        delegate.release()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class BonjourDelegate(
    private val onFound: (MdnsService) -> Unit,
) : NSObject(), NSNetServiceBrowserDelegateProtocol, NSNetServiceDelegateProtocol {

    // a resolving NSNetService is not retained by its browser, so dropping the reference here is
    // enough to have the resolve never call back
    private val resolving = mutableListOf<NSNetService>()

    fun release() {
        resolving.forEach { it.stop() }
        resolving.clear()
    }

    override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean,
    ) {
        log.d("didFindService: ${didFindService.name}")
        didFindService.delegate = this
        resolving += didFindService
        didFindService.resolveWithTimeout(RESOLVE_SECONDS)
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
        val address = sender.addresses.orEmpty()
            .filterIsInstance<NSData>()
            .firstNotNullOfOrNull { it.ipv4() }
        log.d("netServiceDidResolveAddress: ${sender.name} at $address:${sender.port}")
        resolving -= sender
        if (address != null) onFound(MdnsService(sender.name, address, sender.port.toInt()))
    }

    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
        log.w("didNotResolve: ${sender.name}: $didNotResolve")
        resolving -= sender
    }
}

// a companion object isn't allowed on a subclass of an ObjC type, so this lives outside the class
private const val RESOLVE_SECONDS = 5.0

/**
 * the dotted-quad inside a `sockaddr_in`.
 *
 * IPv4 only on purpose: an adb server is handed this address as text and dials it, and a link-local
 * IPv6 address carries a scope id that means nothing on the machine it is sent to.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.ipv4(): String? = memScoped {
    val size = length.toInt()
    if (size < sizeOf<sockaddr_in>()) return@memScoped null
    val copy = allocArray<ByteVar>(size)
    memcpy(copy, bytes, length)
    if (copy.reinterpret<sockaddr>().pointed.sa_family.toInt() != AF_INET) return@memScoped null
    // read the four bytes out of the struct rather than the 32-bit field: s_addr is in network
    // order, so going through the integer would need a byte swap that depends on the host
    val octets = copy.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>()
    (0..3).joinToString(".") { octets[it].toUByte().toInt().toString() }
}
