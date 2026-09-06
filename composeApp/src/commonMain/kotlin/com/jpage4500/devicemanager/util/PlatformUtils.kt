package com.jpage4500.devicemanager.util

enum class PlatformType {
    ANDROID,
    IOS,
    DESKTOP,
}

/** which desktop this is, for the handful of places macOS and windows need different treatment. */
enum class DesktopOS {
    MAC,
    WINDOWS,
    LINUX,
}

/**
 * memory available to *this app*, not to the machine.
 *
 * the JVM heap on desktop and android, the process footprint against physical RAM on iOS. the app's
 * own headroom is the number that matters here: 45 devices on hubs means 45 screencap buffers, and
 * it's this app that runs out, not the laptop.
 */
data class MemoryInfo(val freeBytes: Long, val totalBytes: Long) {
    // ie: "2.1G/7.6G"
    override fun toString(): String =
        "${Utils.bytesToDisplayString(freeBytes)}/${Utils.bytesToDisplayString(totalBytes)}"
}

/**
 * what the platform is, for the log header and the few places behaviour has to differ.
 *
 * deliberately small. anything a *device* can tell us belongs on [com.jpage4500.devicemanager.data.Device]
 * and comes back from `getprop`; this is only ever about the machine the app itself is running on.
 * ported from DashboardWeatherKMP, trimmed to what a device manager uses.
 */
expect object PlatformUtils {
    fun getPlatform(): PlatformType

    /** "macOS" / "Windows" / "Linux" / "Android" / "iOS" — an OS family, never a number. */
    fun getPlatformName(): String

    /** the human version: "15" / "18.2" / "26.5.1". */
    fun getPlatformVersion(): String

    /** the hardware: "SM-S942U1" / "iPhone17,2" / the CPU arch on desktop. */
    fun getDeviceModel(): String

    /** how many cores the machine has — what makes a CPU number in someone else's log readable. */
    fun getCoreCount(): Int

    fun getMemoryInfo(): MemoryInfo

    fun isTablet(): Boolean

    /**
     * true only on a build compiled and run locally — every desktop run from Gradle, a debuggable
     * android install, an iOS debug binary. false in anything that ships.
     *
     * the gate for debug-only affordances that must never reach a shipped build, where
     * [com.jpage4500.devicemanager.data.AppPref.DEBUG_MODE] isn't enough on its own: that pref is a
     * switch any user can find.
     */
    fun isDebugBuild(): Boolean

    /**
     * open a URL in the default browser, or a local path in the platform's file viewer.
     * @return true if the platform took it
     */
    fun openUrl(url: String): Boolean
}
