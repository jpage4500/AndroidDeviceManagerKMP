package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.adb.*

// a phone has no adb to run
actual object AdbServerHost {
    actual suspend fun start(server: AdbServer): Boolean = false
}
