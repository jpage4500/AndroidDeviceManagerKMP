package com.jpage4500.devicemanager.manager

/**
 * refused, permanently.
 *
 * iOS executes nothing an app brought with it — there is no `fork`/`exec` available to a sandboxed
 * app and no way to mark a bundled file executable. So the pairing route that Android gets by
 * shipping Google's binary has no iOS equivalent at all, and iOS pairs only through an adb server
 * somewhere else. See docs/ADB-TRANSPORTS.md.
 */
actual object AdbDaemon {
    actual val isSupported: Boolean get() = false

    actual val isBundled: Boolean get() = false

    actual val isAlive: Boolean get() = false

    actual fun launch(): Boolean = false

    actual fun kill() {}
}
