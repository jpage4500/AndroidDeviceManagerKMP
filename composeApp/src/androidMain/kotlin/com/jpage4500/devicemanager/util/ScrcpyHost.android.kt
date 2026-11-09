package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*

// a phone has no scrcpy to run; the Settings tab and the Mirror choice don't appear
actual object ScrcpyHost {
    actual val supported: Boolean = false
    actual fun resolve(configured: String?): String? = null
    actual suspend fun version(path: String): String? = null
    actual suspend fun launch(path: String, args: List<String>, server: AdbServer): String? = "not supported"
}
