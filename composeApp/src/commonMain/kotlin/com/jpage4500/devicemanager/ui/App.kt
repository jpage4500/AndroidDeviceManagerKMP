package com.jpage4500.devicemanager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.screen.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * the root of every platform.
 *
 * One composable and one layout decision: wide enough for two panes and the list sits beside the
 * device; narrower than that and the list *is* the screen, with the device pushed on top of it. The
 * phone app is the second case and nothing else about it differs.
 */
@Composable
fun App(compact: Boolean = false) {
    AppTheme {
        val devices by DeviceManager.devices.collectAsState()
        val selected by DeviceManager.selectedSerial.collectAsState()

        LaunchedEffect(Unit) { DeviceManager.start() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (compact) CompactLayout(devices, selected) else WideLayout(devices, selected)
        }
    }
}

/** phone: one pane at a time. */
@Composable
private fun CompactLayout(devices: List<Device>, selected: String?) {
    val device = devices.firstOrNull { it.serial == selected }
    if (device == null) {
        Column {
            AppBar(title = "Devices", subtitle = "${devices.size} attached")
            DeviceListScreen(devices, selected, compact = true, onSelect = {
                DeviceManager.select(it.serial)
            })
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

/** desktop: the list on the left, whatever is selected on the right. */
@Composable
private fun WideLayout(devices: List<Device>, selected: String?) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(340.dp)) {
            AppBar(title = "Devices", subtitle = "${devices.size} attached")
            DeviceListScreen(devices, selected, compact = false, onSelect = {
                DeviceManager.select(it.serial)
            })
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val device = devices.firstOrNull { it.serial == selected }
            if (device == null) {
                EmptyState(
                    "Nothing selected",
                    "Pick a device on the left to mirror it.",
                )
            } else {
                MirrorScreen(device.serial)
            }
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
            TextButton(onClick = onBack) { Text("Back") }
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
