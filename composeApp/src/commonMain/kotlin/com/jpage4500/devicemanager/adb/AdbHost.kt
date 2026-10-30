package com.jpage4500.devicemanager.adb

import com.jpage4500.devicemanager.util.*

private val log = LoggerConfig.createLogger("AdbHost")

/**
 * the adb **server's** own commands — the ones that act on the server rather than on a device.
 *
 * each answers with a length-prefixed sentence after the OKAY, and that sentence is what says
 * whether it worked: `host:connect` returns OKAY even for "failed to connect to ...".
 */

/** `adb connect host:port`. @return what the server said, or an error */
suspend fun adbConnect(server: AdbServer, host: String, port: Int): AdbOutcome<String> =
    hostCommand(server, "host:connect:$host:$port")

/** `adb version` — the cheapest question the server answers, so the one that asks if it is there. */
suspend fun adbVersion(server: AdbServer): AdbOutcome<String> = hostCommand(server, "host:version")

/** `adb disconnect <serial>`. */
suspend fun adbDisconnect(server: AdbServer, serial: String): AdbOutcome<String> =
    hostCommand(server, "host:disconnect:$serial")

/**
 * `adb pair <host>:<port> <code>` — Android 11+ wireless pairing.
 *
 * [port] is the **pairing** port the device advertises over mDNS while its pairing dialog is up, not
 * the 5555 you connect to afterwards; the two are different and the pairing one is random.
 *
 * The whole point of going through the server is that it does the cryptography: pairing is a SPAKE2
 * exchange followed by a TLS 1.3 session with client certificates, and there is no pure-Kotlin
 * implementation of the first half. The server already holds the key material and speaks both.
 * See docs/MOBILE-UI.md.
 */
suspend fun adbPair(server: AdbServer, host: String, port: Int, code: String): AdbOutcome<String> =
    when (val outcome = hostCommand(server, "host:pair:$code:$host:$port")) {
        is AdbOutcome.Err -> outcome
        // the server answers OKAY either way and says so in the sentence, as it does for connect —
        // but here the failure wording is "Failed: ..." rather than "failed to ..."
        is AdbOutcome.Ok ->
            if (outcome.value.contains("successfully paired", ignoreCase = true)) {
                outcome
            } else {
                AdbOutcome.Err(AdbError.CONNECT_FAILED, outcome.value.ifBlank { "pairing was refused" })
            }
    }

/**
 * `adb mdns services` — every adb service the server's own mDNS browse has seen.
 *
 * One line per service, `<name>\t<type>\t<host>:<port>`. This is how the desktop browses at all: the
 * JVM has no mDNS, and the adb server it already runs has one — see `Mdns.desktop.kt`. Not logged,
 * because the pairing dialog asks once a second for as long as it is open.
 */
suspend fun adbMdnsServices(server: AdbServer): AdbOutcome<String> =
    hostCommand(server, "host:mdns:services", logReply = false)

private suspend fun hostCommand(
    server: AdbServer,
    command: String,
    logReply: Boolean = true,
): AdbOutcome<String> {
    val connection = AdbConnection.openOrNull(server)
        ?: return AdbOutcome.Err(AdbError.NO_SERVER, "no adb server on ${server.host}")
    return connection.use {
        when (val outcome = it.request(command)) {
            is AdbOutcome.Err -> outcome
            is AdbOutcome.Ok -> {
                val reply = runCatching { it.readFrame() }.getOrDefault("").trim()
                if (logReply) log.d("hostCommand: $command -> $reply")
                // the server says OKAY either way; the sentence is the real answer
                if (reply.startsWith("failed", ignoreCase = true) ||
                    reply.startsWith("unable", ignoreCase = true)
                ) {
                    AdbOutcome.Err(AdbError.CONNECT_FAILED, reply)
                } else {
                    AdbOutcome.Ok(reply)
                }
            }
        }
    }
}

/**
 * `adb reboot`, which is a device service rather than a shell command.
 *
 * `exec:reboot` would also work on most devices, but only because the shell user happens to hold
 * the permission — this is the route `adb reboot` itself takes.
 */
suspend fun adbReboot(server: AdbServer, serial: String): AdbOutcome<Unit> {
    val opened = AdbConnection.toDevice(server, serial)
    val connection = when (opened) {
        is AdbOutcome.Err -> return opened
        is AdbOutcome.Ok -> opened.value
    }
    return connection.use { it.request("reboot:") }
}
