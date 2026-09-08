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
 * A long press turns the list into a picker instead. The selection itself lives in [DeviceManager],
 * the same set the desktop table multi-selects into, so an action written against it works on both.
 */
@Composable
private fun CompactLayout() {
    val devices by DeviceManager.devices.collectAsState()
    val selected by DeviceManager.selectedSerial.collectAsState()
    val selection by DeviceManager.selection.collectAsState()
    var mirroring by remember { mutableStateOf<String?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    val serials = remember(selection) { selection.keys.filterIsInstance<String>().toSet() }

    fun exitMultiSelect() {
        multiSelect = false
        menuOpen = false
        DeviceManager.select(null)
    }

    val mirrored = mirroring
    if (mirrored != null) {
        OnBack(enabled = true) { mirroring = null }
        Column(Modifier.safeInsets()) {
            AppBar(
                title = DeviceManager.nameOf(mirrored),
                subtitle = mirrored,
                onBack = { mirroring = null },
            )
            MirrorScreen(mirrored)
        }
        return
    }

    // back leaves the picker before it leaves the app, which is the one thing a mode like this owes
    // the person in it
    OnBack(enabled = multiSelect) { exitMultiSelect() }

    Column(Modifier.safeInsets()) {
        AppBar(
            title = stringResource(Res.string.devices),
            subtitle = if (multiSelect) {
                stringResource(Res.string.devices_selected, serials.size)
            } else {
                stringResource(Res.string.devices_attached, devices.size)
            },
            onBack = if (multiSelect) ({ exitMultiSelect() }) else null,
            onSettings = if (multiSelect) null else ({ settingsOpen = true }),
        )
        Box(Modifier.weight(1f)) {
            DeviceListScreen(
                devices = devices,
                selectedSerials = serials,
                multiSelect = multiSelect,
                onSelect = { device ->
                    if (multiSelect) {
                        val next = DeviceManager.selection.value.onPress(
                            key = device.serial,
                            rows = devices.map { it.serial },
                            toggle = true,
                            range = false,
                        )
                        // picking the last one off ends the mode rather than leaving an empty picker
                        if (next.isEmpty) exitMultiSelect() else DeviceManager.setSelection(next)
                    } else {
                        DeviceManager.select(device.serial)
                    }
                },
                onLongPress = { device ->
                    multiSelect = true
                    DeviceManager.select(device.serial)
                },
            )
            if (multiSelect) {
                SelectionMenu(
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    onRemove = {
                        serials.forEach(DeviceManager::remove)
                        exitMultiSelect()
                    },
                    onExit = { exitMultiSelect() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Dimens.gapLarge),
                )
            }
        }
    }

    if (settingsOpen) {
        // the phone had no way in at all before this: the adb server address lives in settings, and
        // without it a phone can only ever read the loopback server it doesn't have
        DialogContainer(
            title = stringResource(Res.string.action_settings),
            onDismiss = { settingsOpen = false },
            onEnter = null,
        ) {
            SettingsScreen(onClose = { settingsOpen = false }, modifier = Modifier.safeInsets())
        }
    }

    val openSerial = selected
    if (!multiSelect && openSerial != null) {
        DeviceSheet(
            serial = openSerial,
            onDismiss = { DeviceManager.select(null) },
            onMirror = { serial ->
                DeviceManager.select(null)
                mirroring = serial
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

/** what the picker can do, behind the one button a thumb can reach without crossing the screen. */
@Composable
private fun SelectionMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // the palette's own blue: Material's primaryContainer isn't in the scheme this app builds,
        // so the default FAB would arrive in a colour nothing else here uses
        FloatingActionButton(
            onClick = { onExpandedChange(true) },
            containerColor = Colors.accent,
            contentColor = Colors.rowSelectedText,
        ) {
            AppIcon(Res.drawable.menu, size = Dimens.iconLarge, tint = Colors.rowSelectedText)
        }
        AppPopupMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            Item(stringResource(Res.string.action_remove_device)) { onRemove() }
            Separator()
            Item(stringResource(Res.string.action_exit_select)) { onExit() }
        }
    }
}

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
