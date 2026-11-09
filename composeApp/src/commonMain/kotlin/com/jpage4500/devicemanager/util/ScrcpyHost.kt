package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*

/**
 * scrcpy, run as a program on this machine.
 *
 * Desktop only, like [ScriptHost]: a phone has no scrcpy to run and no adb for it to run through, so
 * [supported] is false there and neither the Settings tab nor the Mirror choice appears.
 */
expect object ScrcpyHost {
    val supported: Boolean

    /** the scrcpy to run: [configured] if it exists, else the first one on `PATH`; null if none. */
    fun resolve(configured: String?): String?

    /** `scrcpy --version`'s first line ("scrcpy 3.1 <https://…>" → "scrcpy 3.1"), or null. */
    suspend fun version(path: String): String?

    /**
     * start [path] with [args] against [server] and leave it running — scrcpy is its own window.
     *
     * @return null once it has started, or why it couldn't
     */
    suspend fun launch(path: String, args: List<String>, server: AdbServer): String?
}
