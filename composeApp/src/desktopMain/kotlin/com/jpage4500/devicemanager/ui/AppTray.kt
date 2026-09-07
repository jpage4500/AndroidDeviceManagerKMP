package com.jpage4500.devicemanager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.painterResource

/**
 * the menu-bar icon, and every device under it.
 *
 * Ported from the Swing app's tray menu. **A submenu per device rather than a row of buttons**: that
 * app draws its own rows through dorkbox's Swing tray (`TrayUiFactory`), and had to force a
 * Swing-drawn tray on macOS to get icons at all. Compose's `Tray` takes an ordinary menu — text items
 * and separators — so an action is one more click here than there. The alternative is an undecorated
 * always-on-top window positioned under the icon, which trades the platform's own menu behaviour for
 * a row of buttons; worth doing only if the extra click turns out to matter.
 *
 * Only shown while [AppPref.MINIMIZE_TO_TRAY] is on, because a tray icon that can't be dismissed is
 * a tray icon someone has to look at forever.
 */
@Composable
fun ApplicationScope.AppTray(onQuit: () -> Unit) {
    val enabled by SettingsManager.getBoolAsState(AppPref.MINIMIZE_TO_TRAY)
    if (!enabled) return

    val devices by DeviceManager.devices.collectAsState()
    val state = rememberTrayState()

    Tray(
        state = state,
        icon = painterResource(Res.drawable.tray_icon),
        // the count can't go on the icon: Compose takes a Painter, not a bitmap to draw a badge onto
        tooltip = "${devices.size} device(s)",
        onAction = { WindowManager.openWindow(WindowContent.DeviceList) },
    ) {
        Item("Open") { WindowManager.openWindow(WindowContent.DeviceList) }
        Separator()
        devices.forEach { device ->
            Menu(device.displayName) {
                Item("Mirror", enabled = device.isReady) { WindowManager.openMirror(device.serial) }
                Item("Browse", enabled = device.isReady) { WindowManager.openFiles(device.serial) }
                Item("Logs", enabled = device.isReady) { WindowManager.openLogs(device.serial) }
                Item("Details") { WindowManager.openInfo(device.serial) }
            }
        }
        if (devices.isNotEmpty()) Separator()
        Item("Quit", onClick = onQuit)
    }
}
