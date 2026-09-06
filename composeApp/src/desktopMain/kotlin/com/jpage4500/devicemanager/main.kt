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
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.stringResource

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
    }

    // the device list is app-wide: every window is a reader of it, so it starts before any of them
    DeviceManager.start()
    WindowManager.openWindow(WindowContent.DeviceList)

    application {
        val windows by WindowManager.windows.collectAsState()

        // closing the last window is quitting, the way it is for a single-window app
        LaunchedEffect(windows.isEmpty()) {
            if (windows.isEmpty()) {
                log.d("main: no windows left - exiting")
                exitApplication()
            }
        }

        windows.forEach { appWindow ->
            key(appWindow.id) { DeviceWindow(appWindow) }
        }
    }
}

/** one window, and everything the platform decides about it: where it is and what its menus say. */
@Composable
private fun ApplicationScope.DeviceWindow(appWindow: AppWindow) {
    val saved = appWindow.bounds
    val windowState = rememberWindowState(
        size = DpSize(
            (saved?.width ?: Dimens.windowWidth.value.toInt()).dp,
            (saved?.height ?: Dimens.windowHeight.value.toInt()).dp,
        ),
        // a saved position from a monitor that isn't attached any more comes through as -1, and is
        // centred rather than placed off screen
        position = if (saved != null && saved.x >= 0 && saved.y >= 0) {
            WindowPosition(saved.x.dp, saved.y.dp)
        } else {
            WindowPosition(Alignment.Center)
        },
    )

    val title = when (val content = appWindow.content) {
        is WindowContent.DeviceList -> Branding.appName
        is WindowContent.Mirror ->
            DeviceManager.device(content.serial)?.displayName ?: content.serial
    }

    Window(
        onCloseRequest = { WindowManager.close(appWindow.id) },
        state = windowState,
        title = title,
    ) {
        // the position is recorded as it moves so a new window can cascade off this one; nothing is
        // written to disk here, since this fires on every frame of a drag
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.position to windowState.size }
                .collect { (position, size) ->
                    if (position is WindowPosition.Absolute) {
                        WindowManager.recordBounds(
                            appWindow.id,
                            WindowBounds(
                                position.x.value.toInt(),
                                position.y.value.toInt(),
                                size.width.value.toInt(),
                                size.height.value.toInt(),
                            ),
                        )
                    }
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
            is WindowContent.Mirror -> AppTheme { MirrorScreen(content.serial) }
        }
    }
}

/** the menu bar. a mirror window gets the same one, so a shortcut works wherever focus is. */
@Composable
private fun FrameWindowScope.AppMenuBar(appWindow: AppWindow) {
    MenuBar {
        Menu(stringResource(Res.string.menu_file), mnemonic = 'F') {
            Item(stringResource(Res.string.menu_new_window), shortcut = KeyShortcut(Key.N, meta = isMac(), ctrl = !isMac())) {
                WindowManager.openWindow(
                    WindowContent.DeviceList,
                    WindowManager.boundsOf(appWindow.id)?.copy(),
                )
            }
            Item(stringResource(Res.string.menu_mirror_device), shortcut = KeyShortcut(Key.M, meta = isMac(), ctrl = !isMac())) {
                DeviceManager.selectedSerial.value?.let { WindowManager.openMirror(it, appWindow.id) }
            }
            Separator()
            Item(stringResource(Res.string.menu_close_window), shortcut = KeyShortcut(Key.W, meta = isMac(), ctrl = !isMac())) {
                WindowManager.close(appWindow.id)
            }
        }
    }
}
