package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/**
 * one device at a glance, beside the table: what it is, how it's connected, its battery and storage,
 * and the actions you'd want for just this one.
 *
 * it follows the table's lead selection while it's open, so clicking down the rows reads them one
 * after another without opening and closing anything. Everything else about a device — every
 * property it reports — is still the Device Details window, which the footer opens.
 */
@Composable
fun DeviceDetailsPanel(
    device: Device,
    busy: Boolean,
    onClose: () -> Unit,
    onScreenshot: (Device) -> Unit,
    onInstall: () -> Unit,
    onInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirmRestart by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.cardCorner)
    val ready = device.isReady

    Column(
        modifier
            .width(Dimens.detailsPanelWidth)
            .fillMaxHeight()
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.listBg),
    ) {
        // who it is
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.gapLarge, end = Dimens.gap, top = Dimens.rowPadding, bottom = Dimens.rowPadding),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = Dimens.liveDot)
                    .size(Dimens.stateDot)
                    .clip(CircleShape)
                    .background(device.stateColor(busy))
            )
            Spacer(Modifier.width(Dimens.navGap))
            Column(Modifier.weight(1f)) {
                Text(
                    device.displayName,
                    fontSize = Dimens.detailsTitleSize,
                    fontWeight = FontWeight.Bold,
                    color = Colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(device.model, device.os?.let { stringResource(Res.string.details_android, it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val closeLabel = stringResource(Res.string.close)
            Box(
                Modifier.size(Dimens.tabAdd).clip(RoundedCornerShape(Dimens.navCorner)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Res.drawable.close, size = Dimens.tabCloseIcon, tint = Colors.secondaryText, contentDescription = closeLabel)
            }
        }
        HorizontalDivider(color = Colors.sidebarBorder)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dimens.gapLarge),
            verticalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
        ) {
            if (ready) ScreenPreview(device.id)

            // the six things done to one device most often
            val actions = listOf(
                QuickAction(Res.drawable.mirror, stringResource(Res.string.action_mirror)) { WindowManager.openMirror(device.id) },
                QuickAction(Res.drawable.screenshot, stringResource(Res.string.action_screenshot)) { onScreenshot(device) },
                QuickAction(Res.drawable.folder, stringResource(Res.string.sel_files)) { WindowManager.openFiles(device.id) },
                QuickAction(Res.drawable.logs, stringResource(Res.string.sel_logs)) { WindowManager.openLogs(device.id) },
                QuickAction(Res.drawable.download, stringResource(Res.string.action_install), onInstall),
                QuickAction(Res.drawable.keyboard, stringResource(Res.string.sel_type_text), onInput),
            )
            actions.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot)) {
                    row.forEach { action -> QuickActionTile(action, enabled = ready, modifier = Modifier.weight(1f)) }
                }
            }

            // what it is, and how it's reached
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.navGap)) {
                Fact(stringResource(Res.string.details_serial), device.serial)
                Fact(
                    stringResource(Res.string.details_connection),
                    stringResource(if (device.isWireless) Res.string.details_wifi else Res.string.details_usb),
                )
                device.sdk?.let { Fact(stringResource(Res.string.details_sdk), it) }
                device.batteryLevel?.let { level ->
                    Fact(
                        stringResource(Res.string.details_battery),
                        if (device.powerStatus.isCharging) stringResource(Res.string.details_battery_charging, level) else "$level%",
                    )
                }
                device.carrier?.let { Fact(stringResource(Res.string.details_carrier), it) }
                device.phone?.let { Fact(stringResource(Res.string.details_phone), it) }
                device.customProperty(Device.CUST_PROP_1)?.let { Fact(stringResource(Res.string.details_custom_1), it) }
            }

            StorageBar(device.freeSpace, device.totalSpace)
        }

        HorizontalDivider(color = Colors.sidebarBorder)
        Row(
            Modifier.fillMaxWidth().padding(Dimens.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            OutlinedAction(stringResource(Res.string.details_restart), Modifier.weight(1f)) { if (ready) confirmRestart = true }
            if (device.isWireless) {
                OutlinedAction(stringResource(Res.string.details_disconnect), Modifier.weight(1f)) {
                    scope.launch { DeviceManager.disconnect(device.id) }
                }
            }
            OutlinedAction(stringResource(Res.string.details_all), Modifier.weight(1f)) { WindowManager.openInfo(device.id) }
        }
    }

    if (confirmRestart) {
        ConfirmationDialog(
            title = stringResource(Res.string.details_restart_title),
            message = stringResource(Res.string.details_restart_msg, device.displayName),
            icon = Res.drawable.restart,
            onDismiss = { confirmRestart = false },
            onConfirm = { scope.launch { DeviceManager.reboot(device.id) } },
        )
    }
}

private class QuickAction(val icon: DrawableResource, val label: String, val onClick: () -> Unit)

@Composable
private fun QuickActionTile(action: QuickAction, enabled: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    val alpha = if (enabled) 1f else 0.4f
    Column(
        modifier
            .height(Dimens.quickActionHeight)
            .clip(shape)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .background(Colors.sidebarBg)
            .clickable(enabled = enabled, onClick = action.onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(action.icon, size = Dimens.icon, tint = Colors.toolbarIcon.copy(alpha = alpha))
        Spacer(Modifier.height(Dimens.tabGap))
        Text(
            action.label,
            style = MaterialTheme.typography.labelSmall,
            color = Colors.primaryText.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            modifier = Modifier.width(Dimens.factLabelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * a still of the device's screen, refreshed on a slow timer while the panel shows it; a click opens
 * the mirror.
 *
 * a one-shot `screencap`, not the mirror's stream: a stream is a poll that would keep running for a
 * panel someone has only glanced at. Every [PREVIEW_REFRESH_MS] is one PNG for one device, which
 * the 45-device rig doesn't notice. Nothing shows until the first still arrives, and nothing at all
 * if the device can't take one.
 */
@Composable
private fun ScreenPreview(deviceId: String) {
    var image by remember(deviceId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(deviceId) {
        while (true) {
            val bytes = DeviceManager.screenshot(deviceId).getOrNull()
            val decoded = bytes?.let { withContext(ioDispatcher) { decodeImage(it) } }
            if (decoded != null) image = decoded
            delay(PREVIEW_REFRESH_MS)
        }
    }
    val still = image ?: return
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = still,
            contentDescription = stringResource(Res.string.details_preview),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(Dimens.previewHeight)
                .aspectRatio(still.width.toFloat() / still.height.coerceAtLeast(1))
                .clip(shape)
                .border(Dimens.hairline, Colors.sidebarBorder, shape)
                .clickable { WindowManager.openMirror(deviceId) },
        )
    }
}

// a glance, not a mirror: often enough to show which app is up, rarely enough to cost nothing
private const val PREVIEW_REFRESH_MS = 10_000L

/** a tick box drawn the redesign's way — a green square when checked. Used in the device rows. */
/** storage as a bar, with "N free of M" over it — how full it is matters more than the number. */
@Composable
fun StorageBar(free: Long?, total: Long?) {
    if (free == null || total == null || total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.liveDot)) {
        Row {
            Text(
                stringResource(Res.string.details_storage),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(
                    Res.string.details_storage_free,
                    Utils.bytesToDisplayString(free),
                    Utils.bytesToDisplayString(total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
            )
        }
        val used = (1f - free.toFloat() / total).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(Dimens.liveDot).clip(CircleShape).background(Colors.tableRule)) {
            Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(Colors.toolbarIcon))
        }
    }
}

@Composable
fun RowCheckbox(checked: Boolean, onToggle: () -> Unit, label: String, partial: Boolean = false) {
    val shape = RoundedCornerShape(Dimens.checkboxCorner)
    val filled = checked || partial
    Box(
        Modifier
            .size(Dimens.checkbox)
            .clip(shape)
            .background(if (filled) Colors.tabAccent else Colors.listBg)
            .border(Dimens.hairline, if (filled) Colors.tabAccent else Colors.secondaryText, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        when {
            checked -> AppIcon(Res.drawable.success, size = Dimens.checkboxTick, tint = Colors.onAccent, contentDescription = label)
            // some but not all — a dash, the way every select-all box says it
            partial -> Box(Modifier.size(Dimens.checkboxTick, Dimens.hairline * 2).background(Colors.onAccent))
        }
    }
}
