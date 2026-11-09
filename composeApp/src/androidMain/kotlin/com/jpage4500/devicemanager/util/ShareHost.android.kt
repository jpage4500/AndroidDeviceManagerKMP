package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.manager.*

// the phone is the client of Share Devices, not a server — the button doesn't appear
actual object ShareHost {
    actual val supported: Boolean = false
    actual suspend fun start(port: Int): String? = "not supported"
    actual suspend fun stop() = Unit
    actual suspend fun networks(): List<LocalNetwork> = emptyList()
    actual suspend fun hostName(): String? = null
}
