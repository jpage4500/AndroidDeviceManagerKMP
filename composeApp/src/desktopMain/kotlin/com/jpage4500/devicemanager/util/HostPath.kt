package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*
import java.io.File

/**
 * `PATH` as a program started from this app should see it — scripts and scrcpy both.
 *
 * an app started from the Dock or Finder gets launchd's bare `PATH`, which has no Android SDK or
 * Homebrew on it — a script that works in a terminal would fail here with "adb: not found", and
 * scrcpy, which runs adb itself, the same way.
 */
object HostPath {
    /** the usual homes of adb and scrcpy that exist on this machine, most specific first. */
    private fun extraDirs(): List<String> {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            System.getenv("ANDROID_HOME")?.let { "$it/platform-tools" },
            System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools" },
            "$home/Library/Android/sdk/platform-tools",
            "$home/Android/Sdk/platform-tools",
            "/opt/homebrew/bin",
            "/usr/local/bin",
        ).filter { File(it).isDirectory }
    }

    private fun currentDirs(env: Map<String, String>): List<String> {
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        return env[key].orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() }
    }

    /**
     * [env] with the extra directories in front of `PATH`, and [server] passed on so adb — run by
     * a script or by scrcpy — talks to the same server the device table comes from.
     */
    fun apply(env: MutableMap<String, String>, server: AdbServer?) {
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val current = currentDirs(env)
        env[key] = (extraDirs().filterNot { it in current } + current).joinToString(File.pathSeparator)
        if (server != null && !server.isLoopback) env["ANDROID_ADB_SERVER_ADDRESS"] = server.host
        if (server != null && server.port != AdbServer.LOCAL.port) env["ANDROID_ADB_SERVER_PORT"] = server.port.toString()
    }

    /** the first [name] on the extended `PATH` (with `.exe` on Windows), or null. */
    fun find(name: String): String? {
        val fileName = if (isWindows()) "$name.exe" else name
        return (extraDirs() + currentDirs(System.getenv()))
            .map { File(it, fileName) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }
}
