package com.jpage4500.devicemanager.manager

/**
 * refused, and not for want of a way to do it.
 *
 * A desktop already has a real adb, and a **USB device is claimed exclusively by whichever server is
 * running** — so a second one started by this app would fight the first for all 45 devices on the
 * hubs, which is the workload the whole project is built to protect. The desktop's loopback server
 * can already pair, so there is nothing here to gain either.
 */
actual object AdbDaemon {
    actual val isSupported: Boolean get() = false

    actual val isBundled: Boolean get() = false

    actual val isAlive: Boolean get() = false

    actual fun launch(): Boolean = false

    actual fun kill() {}
}
