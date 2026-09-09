package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
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
 * **A device belonging to a computer is drawn inside it**, in a [ServerGroup] that expands the way
 * the connect panel's routes do. Which desk a device came through is otherwise invisible until the
 * sheet is open, and a remembered computer that isn't connected has nowhere else to be. Several
 * computers can be connected at once, so several groups can be live — the list is the union of what
 * all of them report, and a row is placed by the connection it arrived on.
 */
@Composable
fun DeviceListScreen(
    devices: List<Device>,
    onSelect: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val servers by ServerManager.servers.collectAsState()
    val connectedKeys by ServerManager.connectedKeys.collectAsState()

    // a device is placed by the connection it came in on, so two desks put their rows in two groups
    val byConnection = remember(devices) { devices.groupBy { it.connectionId } }
    // whatever no remembered computer owns: a desk's adb server, or the test fleet
    val ungrouped = remember(devices, servers) {
        val owned = servers.mapTo(mutableSetOf()) { it.connectionId }
        devices.filterNot { it.connectionId in owned }
    }
    // a connected computer opens itself and the rest stay shut; a tap wins from then on
    val toggled = remember { mutableStateMapOf<String, Boolean>() }
    fun isOpen(server: SavedServer): Boolean = toggled[server.key] ?: (server.key in connectedKeys)

    if (devices.isEmpty() && servers.isEmpty()) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                title = stringResource(Res.string.connect_device),
                detail = stringResource(Res.string.connect_device_msg),
            )
            ConnectPanel()
        }
        return
    }

    // newest first, the order the connect panel listed them in
    val ordered = servers.asReversed()

    LazyColumn(modifier.fillMaxSize()) {
        itemsIndexed(ordered, key = { _, server -> "server:${server.key}" }) { index, server ->
            // a rule above every section but the first, whatever state the sections are in. it only
            // ever falls above a computer's *header*, never between that header and the rows it
            // opened, so it separates one computer from the last one's devices rather than cutting
            // either off from its own
            if (index > 0) {
                HorizontalDivider(color = Colors.divider)
            }
            ServerGroup(
                server = server,
                devices = byConnection[server.connectionId].orEmpty(),
                active = server.key in connectedKeys,
                open = isOpen(server),
                onToggle = { toggled[server.key] = !isOpen(server) },
                onSelect = onSelect,
            )
        }

        // whatever no computer owns: a desk's adb server, or the invented fleet. one section, so
        // the section rule goes above the first row and the rows themselves are only spaced apart
        itemsIndexed(ungrouped, key = { _, device -> "device:${device.id}" }) { index, device ->
            if (index == 0) {
                if (ordered.isNotEmpty()) HorizontalDivider(color = Colors.divider)
            } else {
                HorizontalDivider(thickness = Dimens.listSpacing)
            }
            DeviceRow(device = device, onClick = { onSelect(device) })
        }

        // the same panel, collapsed — the last item rather than a floating button, so it scrolls
        // away with the list it belongs to. keyed, because a keyless item is keyed by its index: a
        // connection made from the form the panel opens adds a row above it, and the panel would
        // otherwise be remounted — closing the form that was still showing what it just did
        item(key = "connect") {
            // always something above it: the screen with nothing at all to show is the empty state
            HorizontalDivider(color = Colors.divider)
            ConnectPanel()
        }
    }
}

/**
 * one device, on two lines.
 *
 * Drawn the same inside a [ServerGroup] as outside one, so a list that groups half its rows still
 * reads as one list; the group insets it far enough to leave its own accent stripe showing.
 */
@Composable
fun DeviceRow(device: Device, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.touchRowHeight)
            .background(MaterialTheme.colorScheme.surface)
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
