package com.jpage4500.devicemanager.ui.shell

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/**
 * the desktop window: a sidebar, and the page it has picked.
 *
 * Logs and Files were windows of their own, one per device; they are pages here, with a tab per
 * device, and this window's [ShellState] owns their streams — see [PageManager] and
 * docs/REDESIGN.md. Type text and Commands were dialogs; they are pages too. Mirror, device details,
 * Connect and the message viewer are still windows.
 */
@Composable
fun AppShell() {
    val scope = rememberCoroutineScope()
    val shell = remember { ShellState(scope) }
    DisposableEffect(shell) {
        PageManager.register(shell)
        onDispose {
            PageManager.unregister(shell)
            shell.dispose()
        }
    }
    // a request from the tray or a menu goes to the window last in front
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) PageManager.activate(shell) }

    val width = remember { SidebarWidth() }

    Row(
        Modifier
            .fillMaxSize()
            .sidebarEdge(width)
            // ⌘C on Devices opens Connect — the Swing app's shortcut. Bubbled rather than previewed
            // so a text field copies first, and only on Devices: on Logs and Files ⌘C means copy
            .onKeyEvent { event ->
                val connect = event.type == KeyEventType.KeyDown && event.key == Key.C &&
                    (event.isMetaPressed || event.isCtrlPressed) && shell.page == Page.DEVICES
                if (connect) WindowManager.openConnect()
                connect
            }
    ) {
        Sidebar(shell = shell, width = width, onShare = { shell.shareOpen = true }, onSettings = { shell.settingsOpen = true })
        Box(Modifier.weight(1f).fillMaxHeight().background(Colors.listBg)) {
            when (shell.page) {
                Page.DEVICES -> DeviceScreen(detailsOpen = shell.detailsOpen, onDetailsOpen = { shell.detailsOpen = it })
                Page.LOGS -> LogsPage(shell, scope)
                Page.FILES -> FilesPage(shell)
                Page.INPUT -> InputScreen()
                Page.COMMANDS -> CommandsScreen()
                Page.SCRIPTS -> ScriptsScreen()
            }
        }
    }

    if (shell.settingsOpen) {
        SettingsDialog(
            initialTab = shell.settingsTab,
            onDismiss = {
                shell.settingsOpen = false
                shell.settingsTab = null
            },
        )
    }
    if (shell.shareOpen) ShareDialog(onDismiss = { shell.shareOpen = false })
    ScrcpyPromptDialog(deviceIds = shell.scrcpyFor, onDismiss = { shell.scrcpyFor = emptyList() })
}

/**
 * a tabbed page's header: the title, then its tabs on the same line.
 *
 * Everything on these pages belongs to one tab, so the title doesn't earn a row of its own — it
 * sits where the strip starts, centred on the open tab, and the strip's rule runs under both.
 */
@Composable
private fun TabbedPageHeader(title: String, tabs: @Composable RowScope.() -> Unit) {
    val border = Colors.sidebarBorder
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Dimens.gap)
            .height(Dimens.tabStripHeight)
            .drawBehind {
                val line = Dimens.hairline.toPx()
                val y = size.height - line / 2
                drawLine(border, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
            }
            .padding(start = Dimens.pageGutter),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.height(Dimens.tabHeightOpen), contentAlignment = Alignment.CenterStart) {
            Text(title, fontSize = Dimens.pageTitleSize, fontWeight = FontWeight.Bold, color = Colors.primaryText)
        }
        Spacer(Modifier.width(Dimens.pageGutter))
        tabs()
    }
}

/** one tab per device's logcat; each keeps running while another is showing. */
@Composable
private fun LogsPage(shell: ShellState, scope: CoroutineScope) {
    val devices by DeviceManager.devices.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TabbedPageHeader(stringResource(Res.string.page_logs)) {
            DeviceTabStrip(
                deviceIds = shell.logTabs.map { it.deviceId },
                active = shell.activeLogs,
                onSelect = shell::showLogs,
                onClose = shell::closeLogs,
                onPick = { shell.openLogs(listOf(it)) },
                recent = RecentDevices.logs,
                openGround = Colors.toolbarBg,
                // live, paused, offline — or not capturing, when the stream ended with the device
                // still there
                status = { id ->
                    val tab = shell.logTabs.firstOrNull { it.deviceId == id }
                    val running = tab?.source?.running?.collectAsState()?.value ?: false
                    val paused = tab?.source?.paused?.collectAsState()?.value ?: false
                    val online = devices.firstOrNull { it.id == id }?.isOnline == true
                    when {
                        !online -> TabStatus(Colors.offline, stringResource(Res.string.tab_offline))
                        !running -> TabStatus(Colors.offline, stringResource(Res.string.tab_stopped))
                        paused -> TabStatus(Colors.busy, stringResource(Res.string.tab_paused))
                        else -> TabStatus(Colors.online, stringResource(Res.string.tab_live))
                    }
                },
                pickerTitle = stringResource(Res.string.logs_capture_from),
                modifier = Modifier.weight(1f).padding(end = Dimens.pageGutter),
            )
        }
        val tab = shell.logTabs.firstOrNull { it.deviceId == shell.activeLogs }
        if (tab == null) {
            EmptyState(
                title = stringResource(Res.string.logs_page_empty),
                detail = stringResource(Res.string.logs_page_empty_msg),
            )
        } else {
            // keyed, and handed the tab's own view, so its filters, search and scroll are that
            // device's — and are still there when the tab is opened again
            key(tab.deviceId) {
                LogsScreen(tab.deviceId, owned = tab.source, ownerScope = scope, view = tab.view)
            }
        }
    }
}

/** one tab per device's filesystem; each keeps the folder it was left in. */
@Composable
private fun FilesPage(shell: ShellState) {
    Column(Modifier.fillMaxSize()) {
        TabbedPageHeader(stringResource(Res.string.page_files)) {
            DeviceTabStrip(
                deviceIds = shell.fileTabs.map { it.deviceId },
                active = shell.activeFiles,
                onSelect = shell::showFiles,
                onClose = shell::closeFiles,
                onPick = shell::openFiles,
                recent = RecentDevices.files,
                openGround = Colors.toolbarBg,
                pickerTitle = stringResource(Res.string.files_browse_on),
                modifier = Modifier.weight(1f).padding(end = Dimens.pageGutter),
            )
        }
        val tab = shell.fileTabs.firstOrNull { it.deviceId == shell.activeFiles }
        if (tab == null) {
            EmptyState(
                title = stringResource(Res.string.files_page_empty),
                detail = stringResource(Res.string.files_page_empty_msg),
            )
        } else {
            key(tab.deviceId) {
                ExploreScreen(tab.deviceId, owned = tab.browser)
            }
        }
    }
}
