package com.jpage4500.devicemanager.ui.shell

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
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
 * docs/REDESIGN.md. Mirror, device details, Connect and the message viewer are still windows.
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

    var settingsOpen by remember { mutableStateOf(false) }
    val width = remember { SidebarWidth() }

    Row(Modifier.fillMaxSize().sidebarEdge(width)) {
        Sidebar(shell = shell, width = width, onSettings = { settingsOpen = true })
        Box(Modifier.weight(1f).fillMaxHeight().background(Colors.listBg)) {
            when (shell.page) {
                Page.DEVICES -> DeviceScreen()
                Page.LOGS -> LogsPage(shell, scope)
                Page.FILES -> FilesPage(shell)
            }
        }
    }

    if (settingsOpen) SettingsDialog(onDismiss = { settingsOpen = false })
}

/** a page's title row. */
@Composable
private fun PageHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.pageHeaderHeight).padding(horizontal = Dimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = Dimens.pageTitleSize, fontWeight = FontWeight.Bold, color = Colors.primaryText)
    }
}

/** one tab per device's logcat; each keeps running while another is showing. */
@Composable
private fun LogsPage(shell: ShellState, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(stringResource(Res.string.page_logs))
        DeviceTabStrip(
            deviceIds = shell.logTabs.map { it.deviceId },
            active = shell.activeLogs,
            onSelect = shell::showLogs,
            onClose = shell::closeLogs,
            onPick = { shell.openLogs(listOf(it)) },
            openGround = Colors.toolbarBg,
            // errors that arrived while the tab wasn't the one showing
            badge = { id ->
                val tab = shell.logTabs.firstOrNull { it.deviceId == id }
                val errors = tab?.source?.errors?.collectAsState()?.value ?: 0
                if (tab == null || id == shell.activeLogs) null else errors - tab.errorsSeen
            },
            modifier = Modifier.padding(horizontal = Dimens.pageGutter),
        )
        val tab = shell.logTabs.firstOrNull { it.deviceId == shell.activeLogs }
        // the open tab has seen everything; leaving it keeps that count as where its badge starts
        if (tab != null) {
            val errors by tab.source.errors.collectAsState()
            LaunchedEffect(tab, errors) { tab.errorsSeen = errors }
        }
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
        PageHeader(stringResource(Res.string.page_files))
        DeviceTabStrip(
            deviceIds = shell.fileTabs.map { it.deviceId },
            active = shell.activeFiles,
            onSelect = shell::showFiles,
            onClose = shell::closeFiles,
            onPick = shell::openFiles,
            openGround = Colors.toolbarBg,
            modifier = Modifier.padding(horizontal = Dimens.pageGutter),
        )
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
