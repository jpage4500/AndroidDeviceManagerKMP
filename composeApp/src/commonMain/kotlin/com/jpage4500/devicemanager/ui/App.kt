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

    val serials = remember(selection) { selection.keys.filterIsInstance<String>().toSet() }

    fun exitMultiSelect() {
        multiSelect = false
        menuOpen = false
        DeviceManager.select(null)
    }

    val mirrored = mirroring
    if (mirrored != null) {
        OnBack(enabled = true) { mirroring = null }
        Column(Modifier.imeInset()) {
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

    Column(Modifier.imeInset()) {
        AppBar(
            title = stringResource(Res.string.devices),
            subtitle = if (multiSelect) {
                stringResource(Res.string.devices_selected, serials.size)
            } else {
                stringResource(Res.string.devices_attached, devices.size)
            },
            onBack = if (multiSelect) ({ exitMultiSelect() }) else null,
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
 * leave room for the soft keyboard.
 *
 * the phone's whole layout shrinks rather than the field moving, which is what lets the scroll under
 * it reach a field the keyboard would otherwise be sitting on — Compose scrolls a focused field into
 * view on its own, but only into a viewport that knows it got shorter. A no-op on the desktop, where
 * `WindowInsets.ime` is always zero.
 */
private fun Modifier.imeInset(): Modifier = imePadding()

@Composable
private fun AppBar(title: String, subtitle: String, onBack: (() -> Unit)? = null) {
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
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
