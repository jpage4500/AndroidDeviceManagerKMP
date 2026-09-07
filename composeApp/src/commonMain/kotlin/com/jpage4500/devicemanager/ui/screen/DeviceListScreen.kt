package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.stringResource

/**
 * the phone's device list.
 *
 * Two lines a row, not ten columns: a phone has one column of usable width and a finger instead of a
 * mouse, and its list is short — a phone reaches devices over the network, so it never sees the
 * desk's whole USB fleet unless it is pointed at that desk. See docs/UI-PORT.md.
 *
 * Tapping a row *opens* the device rather than selecting it, because there is no second pane to
 * fill. Connecting is the primary action, since this list starts empty where the desktop's starts
 * full.
 */
@Composable
fun DeviceListScreen(
    devices: List<Device>,
    selectedSerial: String?,
    onSelect: (Device) -> Unit,
    modifier: Modifier = Modifier,
    onConnect: (() -> Unit)? = null,
) {
    if (devices.isEmpty()) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            EmptyState(
                title = stringResource(Res.string.connect_device),
                detail = stringResource(Res.string.connect_device_msg),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onConnect != null) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(Dimens.gapLarge),
                ) {
                    AppIcon(Res.drawable.add, size = Dimens.icon, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(Dimens.gap))
                    Text(stringResource(Res.string.action_connect))
                }
            }
        }
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        items(devices, key = { it.serial }) { device ->
            DeviceRow(
                device = device,
                selected = device.serial == selectedSerial,
                onClick = { onSelect(device) },
            )
            HorizontalDivider(thickness = Dimens.listSpacing)
        }
    }
}

@Composable
private fun DeviceRow(device: Device, selected: Boolean, onClick: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.touchRowHeight)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            res = if (device.isWireless) Res.drawable.device_remote else Res.drawable.android,
            size = Dimens.iconLarge,
            tint = when (device.state) {
                DeviceState.DEVICE -> Colors.online
                DeviceState.UNAUTHORIZED, DeviceState.AUTHORIZING -> Colors.unauthorized
                DeviceState.OFFLINE, DeviceState.DETACHED -> Colors.offline
                else -> Colors.error
            },
        )
        Spacer(Modifier.width(Dimens.rowPadding))

        Column(Modifier.weight(1f)) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // the second line is whatever this device actually knows, in the order it's worth
            // reading — a device that hasn't answered a getprop yet still shows its serial
            Text(
                text = listOfNotNull(
                    device.serial.takeIf { it != device.displayName },
                    device.os?.let { "Android $it" },
                    Utils.bytesToDisplayString(device.freeSpace).takeIf { device.freeSpace != null },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (device.batteryLevel != null) {
            Spacer(Modifier.width(Dimens.gap))
            BatteryLevel(
                level = device.batteryLevel,
                charging = device.powerStatus.isCharging,
            )
        }
    }
}
