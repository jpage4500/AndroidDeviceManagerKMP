package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.setting.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * one computer running Device Manager, with its devices inside it.
 *
 * An [ExpandableBlock]: rows that simply follow a header read as more list rather than as that
 * header's contents, and the point of the group is to say *these* devices came through *that* desk.
 * **Its ground is a shade off the list's**, so a computer doesn't read as one of its own devices —
 * the device rows inside keep painting the plain surface, which is what makes them the flat thing
 * and the group around them the lifted one.
 *
 * Any number of them can be connected at once — each is a connection of its own in [DeviceManager]
 * — so a group is live when *this* computer is one of them, and the rest open onto a Connect button
 * instead of rows. A computer that isn't connected has no devices to show: they are the desk's to
 * know, not this app's.
 *
 * **The row itself only opens and shuts.** Disconnect and Forget live behind the info button, not
 * as links beside the name: two of them plus a Connect made a row wider than a phone, and Forget
 * one tap away from a row someone is trying to expand is a mistake waiting to happen.
 */
@Composable
fun ServerGroup(
    server: SavedServer,
    devices: List<Device>,
    active: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onSelect: (Device) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }

    fun connect() {
        if (busy) return
        busy = true
        scope.launch {
            ServerManager.connect(server).onFailure {
                ToastManager.showToast(it.message ?: "couldn't connect", success = false)
            }
            busy = false
        }
    }

    ExpandableBlock(
        open = open,
        // full width rather than inside the block's inset, so the shading reads as a band across
        // the list and shows as a frame around the device rows rather than as a card behind them
        modifier = Modifier.background(Colors.groupBackground),
        header = {
            OptionRow(
                icon = Res.drawable.server,
                title = server.displayName,
                // the address as well as the name, because a desk at home and a desk at the office
                // are both "imac", and then what it is doing for us right now
                subtitle = listOf(
                    server.key,
                    if (active) {
                        stringResource(Res.string.devices_attached, devices.size)
                    } else {
                        stringResource(Res.string.server_not_connected)
                    },
                ).joinToString("  ·  "),
                open = open,
                // green when this is the computer being read, grey when it is only remembered — the
                // same two colours a device row's icon uses for the same question
                iconTint = if (active) Colors.online else Colors.offline,
                onClick = onToggle,
                trailing = {
                    IconButton(onClick = { info = true }) {
                        AppIcon(Res.drawable.about, size = Dimens.iconLarge, tint = Colors.secondaryText)
                    }
                },
            )
        },
        body = {
            // inset past the block's own accent stripe, so the stripe still ties the rows to the
            // computer they came from rather than being painted over by the first one
            Column(Modifier.fillMaxWidth().padding(start = Dimens.gap, bottom = Dimens.gap / 2)) {
                when {
                    !active -> ServerNote(
                        text = stringResource(Res.string.server_offline_msg),
                        action = stringResource(Res.string.connect),
                        enabled = !busy,
                        onAction = ::connect,
                    )
                    devices.isEmpty() -> ServerNote(text = stringResource(Res.string.server_none))
                    else -> devices.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider(thickness = Dimens.listSpacing)
                        DeviceRow(device = device, onClick = { onSelect(device) })
                    }
                }
            }
        },
    )

    if (info) {
        ServerDialog(server = server, active = active, onDismiss = { info = false })
    }
}

/** what an open computer says when it has no rows to show — never an empty block. */
@Composable
private fun ServerNote(
    text: String,
    action: String? = null,
    enabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Dimens.gap))
            Button(onClick = onAction, enabled = enabled) { Text(action) }
        }
    }
}

/**
 * what can be done to a computer, behind the info button on its row.
 *
 * Disconnect stops reading this computer and takes its devices out of the list, leaving every other
 * connection where it is; Forget also throws the auth token away, which is why it is the one that
 * reads as destructive. A forgotten computer is one QR scan from coming back.
 */
@Composable
private fun ServerDialog(server: SavedServer, active: Boolean, onDismiss: () -> Unit) {
    BaseDialog(
        title = server.displayName,
        icon = Res.drawable.server,
        onDismiss = onDismiss,
    ) {
        DialogMessage(server.key)
        Spacer(Modifier.height(Dimens.gap))
        if (active) {
            SettingItem(
                icon = Res.drawable.close,
                labelRes = Res.string.disconnect,
                onClick = {
                    ServerManager.disconnect(server.key)
                    onDismiss()
                },
            )
        }
        SettingItem(
            icon = Res.drawable.trash,
            labelRes = Res.string.connect_forget,
            labelColor = Colors.error,
            iconColor = Colors.error,
            showDivider = false,
            onClick = {
                ServerManager.forget(server.key)
                onDismiss()
            },
        )
    }
}
