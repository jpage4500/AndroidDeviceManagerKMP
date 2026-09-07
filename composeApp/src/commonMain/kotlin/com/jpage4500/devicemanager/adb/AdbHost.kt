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
suspend fun adbConnect(host: String, port: Int): AdbOutcome<String> =
    hostCommand("host:connect:$host:$port")

/** `adb disconnect <serial>`. */
suspend fun adbDisconnect(serial: String): AdbOutcome<String> =
    hostCommand("host:disconnect:$serial")

private suspend fun hostCommand(command: String): AdbOutcome<String> {
    val connection = AdbConnection.openOrNull()
        ?: return AdbOutcome.Err(AdbError.NO_SERVER, "no adb server on ${AdbConnection.host}")
    return connection.use {
        when (val outcome = it.request(command)) {
            is AdbOutcome.Err -> outcome
            is AdbOutcome.Ok -> {
                val reply = runCatching { it.readFrame() }.getOrDefault("").trim()
                log.d("hostCommand: $command -> $reply")
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
suspend fun adbReboot(serial: String): AdbOutcome<Unit> {
    val opened = AdbConnection.toDevice(serial)
    val connection = when (opened) {
        is AdbOutcome.Err -> return opened
        is AdbOutcome.Ok -> opened.value
    }
    return connection.use { it.request("reboot:") }
}
