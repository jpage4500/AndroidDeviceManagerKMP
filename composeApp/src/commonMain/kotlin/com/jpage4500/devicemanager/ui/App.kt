package com.jpage4500.devicemanager.ui

import androidx.compose.foundation.layout.*
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
 * screen at two widths — see docs/UI-PORT.md. The desktop gets [DeviceScreen]: a toolbar, a table of
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

/** phone: the list, or the one device that was tapped. */
@Composable
private fun CompactLayout() {
    val devices by DeviceManager.devices.collectAsState()
    val selected by DeviceManager.selectedSerial.collectAsState()
    val device = devices.firstOrNull { it.serial == selected }

    if (device == null) {
        Column {
            AppBar(
                title = stringResource(Res.string.devices),
                subtitle = stringResource(Res.string.devices_attached, devices.size),
            )
            DeviceListScreen(
                devices = devices,
                selectedSerial = selected,
                onSelect = { DeviceManager.select(it.serial) },
                // nothing to open yet — the connect sheet is the next phase, see docs/UI-PORT.md
                onConnect = null,
            )
        }
    } else {
        Column {
            AppBar(
                title = device.displayName,
                subtitle = device.serial,
                onBack = { DeviceManager.select(null) },
            )
            MirrorScreen(device.serial)
        }
    }
}

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
