package com.jpage4500.devicemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.ui.backhandler.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * the root of every platform.
 *
 * One layout decision, and the two sides of it are genuinely different screens rather than one
 * screen at two widths — see docs/MOBILE-UI.md. The desktop gets [DeviceScreen]: a toolbar, a table of
 * every device, a status bar, and mirroring in a window of its own. The phone gets a list it taps
 * into, because it has one column of width and no fleet.
 */
@Composable
fun App(compact: Boolean = false) {
    // published so a setting row or a dialog deep in the tree can size itself for a thumb or a
    // mouse without every caller threading the flag through — see Dimens' setting metrics
    CompositionLocalProvider(LocalCompactUi provides compact) {
        AppContent(compact)
    }
}

@Composable
private fun AppContent(compact: Boolean) {
    AppTheme {
        LaunchedEffect(Unit) { DeviceManager.start() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // the same faint tile the Swing app draws behind its window; the table paints it again
            // behind its own rows, since a row's background would otherwise cover it
            Box(Modifier.fillMaxSize().watermark()) {
                if (compact) CompactLayout() else DeviceScreen()
            }
        }
        // one host for the whole app: a toast is raised from managers that have no composable of
        // their own — a failed command, a crash report going up
        ToastHost()
    }
}

/**
 * phone: the list, the sheet over it, or one device mirrored full-screen.
 *
 * Two levels and no navigation library: tapping a row opens [DeviceSheet] over the list, and only an
 * action inside the sheet pushes a screen of its own. See docs/MOBILE-UI.md for why the tap doesn't
 * go straight to the mirror it used to.
 *
 * A tap is the only gesture the list has. Picking several rows at once — and Remove, the one action
 * that needed it — was the desktop's reason for existing rather than the phone's.
 */
@Composable
private fun CompactLayout() {
    val devices by DeviceManager.devices.collectAsState()
    val selected by DeviceManager.selectedId.collectAsState()
    var mirroring by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    val mirrored = mirroring
    if (mirrored != null) {
        OnBack(enabled = true) { mirroring = null }
        // the dialog's own title bar rather than the list's: a mirror is a thing opened over the
        // app the way a dialog is, and the whole band closes it — it just fills the screen instead
        // of sitting in a card
        Column(Modifier.safeInsets()) {
            DialogTitle(
                title = DeviceManager.nameOf(mirrored),
                subtitle = Device.serialOf(mirrored),
                icon = Res.drawable.mirror,
                onDismiss = { mirroring = null },
            )
            MirrorScreen(mirrored)
        }
        return
    }

    Column(Modifier.safeInsets()) {
        AppBar(
            title = stringResource(Res.string.devices),
            subtitle = stringResource(Res.string.devices_attached, devices.size),
            onSettings = { settingsOpen = true },
        )
        DeviceListScreen(
            devices = devices,
            onSelect = { device -> DeviceManager.select(device.id) },
            modifier = Modifier.weight(1f),
        )
    }

    // the phone had no way in at all before this: the adb server address lives in settings, and
    // without it a phone can only ever read the loopback server it doesn't have
    if (settingsOpen) SettingsDialog(onDismiss = { settingsOpen = false })

    val openDevice = selected
    if (openDevice != null) {
        DeviceSheet(
            deviceId = openDevice,
            onDismiss = { DeviceManager.select(null) },
            onMirror = { deviceId ->
                DeviceManager.select(null)
                mirroring = deviceId
            },
        )
    }
}

/**
 * answer the system back gesture — android's key, and iOS's edge swipe.
 *
 * Compose 1.11 marks `BackHandler` experimental *and* deprecates it in favour of
 * `NavigationEventHandler`, which lives in a dependency this project doesn't carry. Wrapped here so
 * that is one line to change rather than one per call site, the same way [rememberClipboardCopy]
 * holds the clipboard deprecation.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OnBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/**
 * keep the content out from under the system.
 *
 * `MainActivity` calls `enableEdgeToEdge()`, so the window really does extend behind the status bar
 * and the navigation bar — which is what lets the watermark bleed to the edges, and what put the
 * title under the clock until this existed. `safeDrawing` is the union of the system bars, the
 * display cutout **and** the keyboard, so this is also what makes the connect fields reachable: the
 * layout shrinks rather than the field moving, and Compose only scrolls a focused field into a
 * viewport that knows it got shorter.
 *
 * Applied to the content and not to the `Box` above it, so the background still runs edge to edge.
 * A no-op on the desktop, where every one of those insets is zero.
 */
private fun Modifier.safeInsets(): Modifier = safeDrawingPadding()

@Composable
private fun AppBar(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.toolbarHeight)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) }
            Spacer(Modifier.width(Dimens.gap))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                AppIcon(Res.drawable.settings, size = Dimens.iconLarge, tint = Colors.toolbarIcon)
            }
        }
    }
    HorizontalDivider()
}
