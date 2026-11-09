package com.jpage4500.devicemanager

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop

private val log = LoggerConfig.createLogger("MainKt")

/**
 * the desktop app.
 *
 * More than one window is the point of this entry point: [WindowManager] holds the list and this
 * draws one real OS window per entry, so a device torn off into its own mirror window is a state
 * change rather than a special case. Ported from FileExplorerKMP.
 */
fun main() {
    initApp()

    if (isMac()) {
        // the app name in the macOS menu bar — without this it reads "MainKt"
        runCatching { System.setProperty("apple.awt.application.name", Branding.appName) }
        // the traffic lights, the screen menu bar and the AWT file dialogs are drawn by AppKit
        // rather than by us, and this is the only supported way to make them follow the system
        // appearance. it follows the *OS*, not our theme
        runCatching { System.setProperty("apple.awt.application.appearance", "system") }
        // the menu-bar icon as a template image: macOS keeps only its alpha and colours it to suit
        // the bar, light or dark. read when the tray icon is created, so it's set before anything
        runCatching { System.setProperty("apple.awt.enableTemplateImages", "true") }
        // Settings… in the app menu, with the ⌘, every mac app answers to; the File menu carries it
        // everywhere else
        runCatching { Desktop.getDesktop().setPreferencesHandler { PageManager.openSettings() } }
            .onFailure { log.e("main: preferences handler: ${it.message}") }
    }

    // the device list is app-wide: every window is a reader of it, so it starts before any of them
    DeviceManager.start()
    WindowManager.openWindow(WindowContent.DeviceList)

    application {
        val windows by WindowManager.windows.collectAsState()
        val toTray by SettingsManager.getBoolAsState(AppPref.MINIMIZE_TO_TRAY)

        // closing the last window is quitting, the way it is for a single-window app — unless the
        // tray is on, in which case the app is still there and the icon is how it comes back
        LaunchedEffect(windows.isEmpty(), toTray) {
            if (windows.isEmpty() && !toTray) {
                log.d("main: no windows left - exiting")
                exitApplication()
            }
        }

        AppTray(onQuit = ::exitApplication)

        windows.forEach { appWindow ->
            key(appWindow.id) { DeviceWindow(appWindow) }
        }
    }
}

/** one window, and everything the platform decides about it: where it is and what its menus say. */
@Composable
private fun ApplicationScope.DeviceWindow(appWindow: AppWindow) {
    // the size is its kind's — the one a window of this kind was last left at — and the position is
    // wherever WindowManager cascaded it to. A negative x means "nothing to cascade off", and is
    // centred rather than placed at a guess
    val saved = appWindow.bounds
    val windowState = rememberWindowState(
        size = DpSize(saved.width.dp, saved.height.dp),
        position = if (saved.x >= 0 && saved.y >= 0) {
            WindowPosition(saved.x.dp, saved.y.dp)
        } else {
            WindowPosition(Alignment.Center)
        },
    )

    val title = when (val content = appWindow.content) {
        is WindowContent.DeviceList -> Branding.appName
        is WindowContent.Mirror -> DeviceManager.nameOf(content.deviceId)
        is WindowContent.Connect -> stringResource(Res.string.connect_window_title)
        is WindowContent.Message -> "Message Viewer"
    }

    val alwaysOnTop by SettingsManager.getBoolAsState(AppPref.ALWAYS_ON_TOP)

    // every window keeps an ordinary title bar. The toolbar was in it for a while (macOS
    // `fullWindowContent`), which looked closer to Finder — but the window then has nothing to drag
    // by except the toolbar's own gaps, and a title says which device a window is for
    Window(
        onCloseRequest = { WindowManager.close(appWindow.id) },
        state = windowState,
        alwaysOnTop = alwaysOnTop,
        title = title,
    ) {
        // reported as it moves and resizes: the position so a new window can cascade off this one,
        // the size so the next window of this kind opens at it. The write to disk is debounced in
        // WindowManager, because this fires on every frame of a drag.
        //
        // the size is reported even while the position is still `Aligned` — a centred window's
        // position never becomes absolute unless someone drags it, and gating the whole thing on
        // absolute meant a window nobody moved never remembered how big it was
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.position to windowState.size }
                .collect { (position, size) ->
                    val at = position as? WindowPosition.Absolute
                    WindowManager.recordBounds(
                        appWindow.id,
                        WindowBounds(
                            at?.x?.value?.toInt() ?: -1,
                            at?.y?.value?.toInt() ?: -1,
                            size.width.value.toInt(),
                            size.height.value.toInt(),
                        ),
                    )
                }
        }

        // the tray acts from outside every window, so it asks for its window to come forward
        LaunchedEffect(window) {
            WindowManager.raise.filter { it == appWindow.id }.collect {
                if (window.isMinimized) window.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }

        LaunchedEffect(window) {
            window.minimumSize = java.awt.Dimension(
                Dimens.minWindowWidth.value.toInt(),
                Dimens.minWindowHeight.value.toInt(),
            )
        }

        AppMenuBar(appWindow)

        when (val content = appWindow.content) {
            is WindowContent.DeviceList -> App(compact = false)
            is WindowContent.Mirror -> AppTheme { MirrorScreen(content.deviceId) }
            is WindowContent.Connect -> AppTheme {
                ConnectScreen(onClose = { WindowManager.close(appWindow.id) })
            }
            is WindowContent.Message -> AppTheme { MessageScreen(content.text) }
        }
    }
}

/** the menu bar. a mirror window gets the same one, so a shortcut works wherever focus is. */
@Composable
private fun FrameWindowScope.AppMenuBar(appWindow: AppWindow) {
    val scope = rememberCoroutineScope()
    val devices by DeviceManager.devices.collectAsState()
    val selection by DeviceManager.selection.collectAsState()
    val lead by DeviceManager.selectedId.collectAsState()
    // what the Device menu acts on: a mirror window's own device, else the device list's selection —
    // the ready ones, as the selection bar does
    val mirrored = (appWindow.content as? WindowContent.Mirror)?.deviceId
    val targets = devices.filter { it.isReady && if (mirrored != null) it.id == mirrored else it.id in selection.keys }
    val ids = targets.map { it.id }
    // Files opens one tab: the keyboard cursor's device when it's one of them
    val filesFor = ids.firstOrNull { it == lead } ?: ids.firstOrNull()
    fun shortcut(key: Key) = KeyShortcut(key, meta = isMac(), ctrl = !isMac())
    // ⌘S belongs to the page showing: a screenshot on Devices (and in a mirror window, of its own
    // device), the log's save dialog on Logs, nothing anywhere else. A menu item holding a shortcut
    // swallows it on every page, so each item only holds it while its page is up
    val page = if (appWindow.content is WindowContent.DeviceList) PageManager.activePage else null
    val onLogs = page == Page.LOGS
    val screenshotKey = if (mirrored != null || page == Page.DEVICES) shortcut(Key.S) else null

    MenuBar {
        Menu(stringResource(Res.string.menu_file), mnemonic = 'F') {
            Item(stringResource(Res.string.menu_new_window), shortcut = KeyShortcut(Key.N, meta = isMac(), ctrl = !isMac())) {
                // no bounds passed: WindowManager places it at the device list's own remembered
                // size, cascaded off the last device-list window so it doesn't land on top of it
                WindowManager.openWindow(WindowContent.DeviceList)
            }
            Item(stringResource(Res.string.menu_mirror_device), shortcut = KeyShortcut(Key.M, meta = isMac(), ctrl = !isMac())) {
                DeviceManager.selectedId.value?.let { MirrorLauncher.mirror(it) }
            }
            if (!isMac()) {
                Item(stringResource(Res.string.menu_settings), shortcut = KeyShortcut(Key.Comma, ctrl = true)) {
                    PageManager.openSettings()
                }
            }
            Separator()
            Item(stringResource(Res.string.menu_save_log), enabled = onLogs, shortcut = if (onLogs) shortcut(Key.S) else null) {
                PageManager.saveActiveLog()
            }
            Separator()
            Item(stringResource(Res.string.menu_close_window), shortcut = KeyShortcut(Key.W, meta = isMac(), ctrl = !isMac())) {
                // a device-list window closes its open Logs or Files tab first, as a browser does;
                // it's the window last in front, since this shortcut only reaches the focused one
                val closedTab = appWindow.content is WindowContent.DeviceList && PageManager.closeActiveTab()
                if (!closedTab) WindowManager.close(appWindow.id)
            }
        }
        Menu(stringResource(Res.string.menu_view), mnemonic = 'V') {
            Item(stringResource(Res.string.devices), shortcut = shortcut(Key.D)) {
                PageManager.openDevices()
            }
            Separator()
            // the Devices page's List / Icons toggle; picking one also brings that page up
            Item(stringResource(Res.string.menu_view_list)) {
                SettingsManager.setString(AppPref.DEVICES_VIEW, DeviceView.LIST.name)
                PageManager.openDevices()
            }
            Item(stringResource(Res.string.menu_view_icons)) {
                SettingsManager.setString(AppPref.DEVICES_VIEW, DeviceView.ICONS.name)
                PageManager.openDevices()
            }
        }
        Menu(stringResource(Res.string.menu_device), mnemonic = 'D') {
            Item(stringResource(Res.string.menu_logs), enabled = ids.isNotEmpty(), shortcut = shortcut(Key.L)) {
                PageManager.openLogs(ids)
            }
            Item(stringResource(Res.string.menu_files), enabled = filesFor != null, shortcut = shortcut(Key.E)) {
                filesFor?.let(PageManager::openFiles)
            }
            Item(stringResource(Res.string.menu_screenshot), enabled = ids.isNotEmpty(), shortcut = screenshotKey) {
                scope.launch { targets.forEach { DeviceManager.saveScreenshot(it) } }
            }
            Item(stringResource(Res.string.menu_type_text), enabled = ids.isNotEmpty(), shortcut = shortcut(Key.I)) {
                PageManager.openInput(ids)
            }
        }
    }
}
