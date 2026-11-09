package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*

/**
 * the `adb` program on this machine, run to start a server that isn't running.
 *
 * Desktop only, like [ScrcpyHost]: a phone has no adb to run, so [start] does nothing there. The adb
 * client itself never starts anything (see `AdbStream.openOrNull`) — this is the one place that does,
 * and only `AdbServerTransport` calls it, once each time it starts reading a loopback server.
 */
expect object AdbServerHost {
    /** `adb -P <port> start-server`; true if it ran and exited cleanly, false if there's no adb. */
    suspend fun start(server: AdbServer): Boolean
}
