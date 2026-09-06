package com.jpage4500.devicemanager

import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.ui.*

/**
 * what Xcode calls into.
 *
 * iOS is a phone, so it is always the compact layout: a list of devices and one of them mirrored.
 * It reaches them over the network — there is no USB host access on iOS, ever — which means an adb
 * server or a Device Manager instance on the LAN. See docs/ADB-TRANSPORTS.md.
 */
fun MainViewController() = ComposeUIViewController {
    initApp()
    App(compact = true)
}
