package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.server.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.*

private val log = LoggerConfig.createLogger("ShareHost")

actual object ShareHost {
    actual val supported: Boolean = true

    private var server: ShareHttpServer? = null

    actual suspend fun start(port: Int): String? = withContext(ioDispatcher) {
        if (server != null) return@withContext null
        val next = ShareHttpServer(port, ShareApi::handle, ShareApi::socket)
        try {
            next.start()
            server = next
            null
        } catch (e: Exception) {
            next.stop()
            e.message ?: "couldn't listen on port $port"
        }
    }

    actual suspend fun stop() = withContext(ioDispatcher) {
        server?.stop()
        server = null
    }

    actual suspend fun networks(): List<LocalNetwork> = withContext(ioDispatcher) { LocalNetworks.read() }

    /**
     * the name a person gave this computer, where the OS keeps one — "Joe's MacBook Pro" rather than
     * `mac4473.lan`, since this is what a phone's server list will be labelled with.
     */
    actual suspend fun hostName(): String? = withContext(ioDispatcher) {
        when {
            isMac() -> LocalNetworks.run("scutil", "--get", "ComputerName").firstOrNull()?.trim()
            isWindows() -> System.getenv("COMPUTERNAME")
            else -> null
        }?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
    }
}

/**
 * every IPv4 address this machine has, and what kind of link each is on — the Swing app's
 * `RemoteConnectionUtils.getActiveNetworkInfo`.
 *
 * The kind is the point: a Mac on Tailscale and a corporate VPN has four addresses, and only the
 * Wi-Fi one is reachable by a phone on the same Wi-Fi. LAN addresses come first, so it is the one
 * the dialog picks for the QR code unless someone picks another.
 */
internal object LocalNetworks {
    private const val HARDWARE_PORT_KEY = "Hardware Port: "
    private const val DEVICE_KEY = "Device: "
    private const val COMMAND_TIMEOUT_SECS = 2L

    // en0 -> Wi-Fi, from `networksetup`. read once: it only changes when hardware does
    private var macPorts: Map<String, String>? = null

    fun read(): List<LocalNetwork> {
        val list = mutableListOf<LocalNetwork>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (address in iface.inetAddresses) {
                    // IPv4 only: an IPv6 address in a connection string is a support call
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    // NOTE: a reverse lookup — this is why the dialog shows "fetching" first
                    val host = address.hostName.takeIf { it != address.hostAddress }
                    list += LocalNetwork(
                        ip = address.hostAddress,
                        host = host,
                        type = typeOf(iface),
                        label = iface.name,
                        isSiteLocal = address.isSiteLocalAddress,
                    )
                }
            }
        } catch (e: Exception) {
            log.e("read: ${e.message}")
        }
        return list.sortedByDescending { it.isLocalNetwork }
    }

    /** Wi-Fi, Ethernet, VPN or Virtual — null when nothing about the interface says. */
    private fun typeOf(iface: NetworkInterface): String? {
        val name = iface.name
        // VPN clients use tunnel interfaces - point-to-point with no hardware address
        val tunnel = listOf("utun", "tun", "tap", "ppp", "ipsec", "wg").any { name.startsWith(it, ignoreCase = true) }
        if (tunnel || (iface.isPointToPoint && iface.hardwareAddress == null)) return LocalNetwork.TYPE_VPN

        // macOS names every interface enX — networksetup says which port that is
        if (isMac()) return macPort(name)

        // Windows display names are already readable ("Wi-Fi", "Intel(R) Ethernet ..");
        // linux says it in the name's prefix
        val desc = (iface.displayName ?: name).lowercase()
        fun has(vararg words: String) = words.any { it in desc }
        return when {
            has("vpn", "tunnel", "wireguard", "tailscale", "zerotier", "anyconnect", "netskope", "zscaler", "globalprotect") -> LocalNetwork.TYPE_VPN
            has("virtual", "hyper-v", "vmware", "virtualbox", "parallels", "docker") -> "Virtual"
            name.startsWith("wl") || has("wi-fi", "wifi", "wireless", "802.11") -> "Wi-Fi"
            name.startsWith("en") || name.startsWith("eth") || has("ethernet") -> "Ethernet"
            else -> null
        }
    }

    @Synchronized
    private fun macPort(name: String): String? {
        val ports = macPorts ?: buildMap {
            // the output repeats: "Hardware Port: Wi-Fi" / "Device: en0" / "Ethernet Address: .."
            var port: String? = null
            for (line in run("networksetup", "-listallhardwareports")) {
                when {
                    line.startsWith(HARDWARE_PORT_KEY) -> port = line.removePrefix(HARDWARE_PORT_KEY).trim()
                    line.startsWith(DEVICE_KEY) && port != null -> {
                        put(line.removePrefix(DEVICE_KEY).trim(), port)
                        port = null
                    }
                }
            }
        }.also { macPorts = it }
        return ports[name]
    }

    /** a command's output lines, or none — given two seconds, which these take a fraction of. */
    fun run(vararg command: String): List<String> = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(COMMAND_TIMEOUT_SECS, TimeUnit.SECONDS)) process.destroyForcibly()
        lines
    } catch (e: Exception) {
        log.d("run: ${command.first()}: ${e.message}")
        emptyList()
    }
}
