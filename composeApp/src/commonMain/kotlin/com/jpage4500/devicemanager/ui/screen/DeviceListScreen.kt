package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*

/**
 * every attached device, one per row.
 *
 * the same list on all three platforms; only the row height and what a tap does differ, which is
 * what [compact] carries. A phone gets a bigger target and opens the device; the desktop selects it
 * and fills the pane beside it.
 */
@Composable
fun DeviceListScreen(
    devices: List<Device>,
    selectedSerial: String?,
    compact: Boolean,
    onSelect: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) {
        EmptyState(
            title = "No devices",
            detail = "Attach a device over USB, or start an adb server for this app to read.\n" +
                "Nothing here starts one for you.",
            modifier = modifier,
        )
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        items(devices, key = { it.serial }) { device ->
            DeviceRow(
                device = device,
                selected = device.serial == selectedSerial,
                compact = compact,
                onClick = { onSelect(device) },
            )
            HorizontalDivider(thickness = Dimens.listSpacing)
        }
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Unspecified
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(background) else Modifier)
            .clickable(onClick = onClick)
            .height(if (compact) Dimens.touchRowHeight else Dimens.rowHeight)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(device.state)
        Spacer(Modifier.width(Dimens.gap + 2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                buildString {
                    append(device.serial)
                    device.os?.let { append("  ·  Android $it") }
                    if (device.isWireless) append("  ·  wifi")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        device.batteryLevel?.let { level ->
            Chip("$level%")
            Spacer(Modifier.width(Dimens.gap))
        }
        if (device.state != DeviceState.DEVICE) Chip(device.state.label)
    }
}
