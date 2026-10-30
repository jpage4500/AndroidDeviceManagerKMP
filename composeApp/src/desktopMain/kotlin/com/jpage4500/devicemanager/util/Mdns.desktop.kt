package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*
import kotlinx.coroutines.*

private val log = LoggerConfig.createLogger("Mdns")

// how often the adb server is asked. its own browse runs continuously, so this only decides how soon
// a device that has just appeared is noticed — the Swing app's pairing dialog is no quicker
private const val POLL_MS = 1_000L

/**
 * the desktop browses through the adb server rather than by itself.
 *
 * The JVM has no mDNS of its own, and the Swing app carries JmDNS for this — a dependency for one
 * dialog. The adb server the desktop already runs has a browse of its own and answers
 * `host:mdns:services` with everything it has seen, which is what the Swing app reads to find a
 * pairing port anyway. So this asks it once a second and reports each service the first time it
 * shows up; reporting it again would have [com.jpage4500.devicemanager.manager.PairingSession]
 * retry a failed pairing every second on its own.
 *
 * Always the local server: a desktop's adb is on this machine, and its browse sees the network this
 * machine is on.
 */
actual fun browseMdns(serviceType: String, onFound: (MdnsService) -> Unit): MdnsBrowse {
    val type = serviceType.normalType()
    val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    scope.launch {
        log.d("browseMdns: $type")
        val seen = mutableSetOf<MdnsService>()
        while (isActive) {
            val outcome = adbMdnsServices(AdbServer.LOCAL)
            if (outcome is AdbOutcome.Ok) {
                parseServices(outcome.value)
                    .filter { it.first == type && seen.add(it.second) }
                    .forEach { onFound(it.second) }
            }
            delay(POLL_MS)
        }
    }
    return MdnsBrowse { scope.cancel() }
}

/** `adb-XXXX\t_adb-tls-pairing._tcp\t192.168.0.5:37123`, one per line, into (type, service). */
private fun parseServices(text: String): List<Pair<String, MdnsService>> =
    text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split('\t')
        if (parts.size < 3) return@mapNotNull null
        val address = parts[2].trim()
        val host = address.substringBeforeLast(':', "")
        val port = address.substringAfterLast(':', "").toIntOrNull()
        if (host.isEmpty() || port == null) return@mapNotNull null
        parts[1].normalType() to MdnsService(parts[0].trim(), host, port)
    }.toList()

// adb has printed the type both with and without a trailing dot, and Bonjour adds `.local.`
private fun String.normalType(): String = trim().removeSuffix(".").removeSuffix(".local").removeSuffix(".")
