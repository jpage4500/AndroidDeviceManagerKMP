package com.jpage4500.devicemanager.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerConfig.createLogger("Mdns")

/**
 * `NsdManager`, which is Android's front end to the same mDNS responder the OS already runs.
 *
 * Two things about it shape this code:
 *
 * - **A discovery only carries a name.** The host and port need a second call, and that is the one
 *   that matters here — an adb pairing port is random, so a name alone is useless.
 * - **`resolveService` handles one at a time.** Older releases fail the second concurrent resolve
 *   with `FAILURE_ALREADY_ACTIVE` rather than queuing it, so this queues instead.
 */
actual fun browseMdns(serviceType: String, onFound: (MdnsService) -> Unit): MdnsBrowse {
    val nsd = AndroidContextProvider.context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        ?: return MdnsBrowse {}

    val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
    val resolving = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)

    fun resolveNext() {
        if (stopped.get()) return
        if (!resolving.compareAndSet(false, true)) return
        val next = queue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                log.w("onResolveFailed: ${info.serviceName}: $errorCode")
                resolving.set(false)
                resolveNext()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val address = info.host?.hostAddress
                log.d("onServiceResolved: ${info.serviceName} at $address:${info.port}")
                if (address != null && !stopped.get()) {
                    onFound(MdnsService(info.serviceName.orEmpty(), address, info.port))
                }
                resolving.set(false)
                resolveNext()
            }
        })
    }

    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = log.d("onDiscoveryStarted: $type")

        override fun onStartDiscoveryFailed(type: String, errorCode: Int) =
            log.w("onStartDiscoveryFailed: $type: $errorCode")

        override fun onStopDiscoveryFailed(type: String, errorCode: Int) =
            log.w("onStopDiscoveryFailed: $type: $errorCode")

        override fun onDiscoveryStopped(type: String) = log.d("onDiscoveryStopped: $type")

        override fun onServiceFound(info: NsdServiceInfo) {
            log.d("onServiceFound: ${info.serviceName}")
            queue.add(info)
            resolveNext()
        }

        override fun onServiceLost(info: NsdServiceInfo) = log.d("onServiceLost: ${info.serviceName}")
    }

    // the type has to end in a dot for NsdManager, where iOS and the spec are happy without one
    nsd.discoverServices("$serviceType.", NsdManager.PROTOCOL_DNS_SD, listener)
    return MdnsBrowse {
        stopped.set(true)
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}
