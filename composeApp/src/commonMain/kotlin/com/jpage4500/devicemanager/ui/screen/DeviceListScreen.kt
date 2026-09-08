package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
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
 * full — so [ConnectPanel] is here in both of its placements: expanded as the empty state, and
 * collapsed under the rows once there is something to show.
 *
 * **Except in [multiSelect]**, which a long press starts: there a tap toggles the row instead of
 * opening it, and the check on the right is the only thing that changes about the layout — a list
 * that grows a column of checkboxes when a mode turns on is a list that jumps.
 */
@Composable
fun DeviceListScreen(
    devices: List<Device>,
    selectedSerials: Set<String>,
    multiSelect: Boolean,
    onSelect: (Device) -> Unit,
    onLongPress: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (devices.isEmpty()) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                title = stringResource(Res.string.connect_device),
                detail = stringResource(Res.string.connect_device_msg),
            )
            ConnectPanel(startExpanded = true)
        }
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        items(devices, key = { it.serial }) { device ->
            DeviceRow(
                device = device,
                selected = device.serial in selectedSerials,
                multiSelect = multiSelect,
                onClick = { onSelect(device) },
                onLongClick = { onLongPress(device) },
            )
            HorizontalDivider(thickness = Dimens.listSpacing)
        }
        // the same panel, collapsed — the last item rather than a floating button, so it scrolls
        // away with the list it belongs to. Out of the way while rows are being picked
        if (!multiSelect) item { ConnectPanel() }
    }
}

@Composable
private fun DeviceRow(
    device: Device,
    selected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.touchRowHeight)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
                text = device.nameWithModel(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // no serial here on purpose. A wireless one is
            // "adb-RFCX20NB5TY-DBjlg4._adb-tls-connect._tcp" and eats the whole line to say nothing
            // anyone reads; it is one tap away in the sheet. These three are what identifies a
            // device across a bench
            val detail = listOfNotNull(
                device.phone,
                device.os?.let { "Android $it" },
                Utils.bytesToDisplayString(device.freeSpace).takeIf { device.freeSpace != null },
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (device.batteryLevel != null) {
            Spacer(Modifier.width(Dimens.gap))
            BatteryGauge(
                level = device.batteryLevel,
                charging = device.powerStatus.isCharging,
            )
        }

        if (multiSelect) {
            Spacer(Modifier.width(Dimens.gap))
            // the mark AppCheckbox draws, at a phone's size and with no click target of its own —
            // the row is the target, and a second one inside it is a way to miss
            Box(
                Modifier
                    .size(Dimens.iconLarge)
                    .clip(CircleShape)
                    .background(if (selected) Colors.accent else Color.Transparent)
                    .border(Dp.Hairline, if (selected) Colors.accent else Colors.secondaryText, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    AppIcon(Res.drawable.success, size = Dimens.icon, tint = Colors.rowSelectedText)
                }
            }
        }
    }
}

/**
 * the name, and the model after it when they aren't already the same thing.
 *
 * [Device.displayName] is the nickname when there is one and the model when there isn't, so a device
 * nobody has renamed would otherwise read "Pixel 10a (Pixel 10a)". The model is dimmed: the name is
 * what someone is looking for and the model is what tells two of them apart.
 */
@Composable
private fun Device.nameWithModel(): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(displayName) }
    val model = model
    if (model != null && model != displayName) {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append("  (")
            append(model)
            append(")")
        }
    }
}
