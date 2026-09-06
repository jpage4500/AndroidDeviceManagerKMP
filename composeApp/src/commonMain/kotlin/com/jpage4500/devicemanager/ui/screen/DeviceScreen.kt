package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * the desktop app's main screen: a toolbar, every device as a row in a table, and a status bar.
 *
 * Deliberately the same shape as the Swing app's `DeviceScreen` rather than a two-pane list — this
 * is a screen for watching 45 devices at once, and a split view spends half the window on one of
 * them. Mirroring opens a window of its own (see [WindowManager]).
 */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier) {
    val devices by DeviceManager.devices.collectAsState()
    val selected by DeviceManager.selectedSerial.collectAsState()
    val columns by DeviceTableState.visible.collectAsState()
    val widths by DeviceTableState.widths.collectAsState()
    val hidden by DeviceTableState.hidden.collectAsState()
    val sort by DeviceTableState.sort.collectAsState()
    val scope = rememberCoroutineScope()

    val sorted = remember(devices, sort) { DeviceTableState.sorted(devices) }
    val device = devices.firstOrNull { it.serial == selected }

    var columnMenuFor by remember { mutableStateOf<DeviceColumn?>(null) }

    Column(modifier.fillMaxSize()) {
        DeviceToolbar(
            device = device,
            onMirror = { it?.let { d -> WindowManager.openMirror(d.serial) } },
            onScreenshot = { d -> scope.launch { saveScreenshot(d) } },
            onRefresh = { DeviceManager.refresh() },
        )
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            if (sorted.isEmpty()) {
                EmptyState(
                    title = stringResource(Res.string.no_devices),
                    detail = stringResource(Res.string.no_devices_msg),
                )
            } else {
                DataTable(
                    rows = sorted,
                    columns = columns,
                    widths = widths.withDefaults(columns),
                    sortedBy = sort.column,
                    sortAscending = sort.ascending,
                    rowKey = { it.serial },
                    header = { it.label },
                    selectedKey = selected,
                    rowHeight = Dimens.tableRowHeight,
                    onSort = { DeviceTableState.sortBy(it) },
                    onResize = { column, width -> DeviceTableState.resize(column, width) },
                    onRowClick = { DeviceManager.select(it.serial) },
                    onRowDoubleClick = { WindowManager.openMirror(it.serial) },
                    onHeaderContextClick = { columnMenuFor = it },
                ) { row, column ->
                    DeviceCell(row, column)
                }
            }

            ColumnMenu(
                openFor = columnMenuFor,
                hidden = hidden,
                onDismiss = { columnMenuFor = null },
            )
        }

        HorizontalDivider()
        DeviceStatusBar(total = devices.size)
    }
}

/** widths the user hasn't touched fall back to the column's own default. */
private fun Map<DeviceColumn, Dp>.withDefaults(columns: List<DeviceColumn>): Map<DeviceColumn, Dp> =
    columns.associateWith { this[it] ?: it.defaultWidth.dp }

/**
 * one cell.
 *
 * a `when` rather than a renderer per column, because only three of them draw anything but text and
 * a lookup table of one-line composables is harder to read than the list of exceptions.
 */
@Composable
private fun DeviceCell(device: Device, column: DeviceColumn) {
    when (column) {
        DeviceColumn.NAME -> Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                res = if (device.isWireless) Res.drawable.device_remote else Res.drawable.android,
                size = Dimens.icon,
                tint = device.stateColor(),
            )
            Spacer(Modifier.width(Dimens.gap / 2))
            TableText(device.displayName)
        }

        DeviceColumn.BATTERY -> BatteryLevel(
            level = device.batteryLevel,
            charging = device.powerStatus == PowerStatus.CHARGING || device.powerStatus == PowerStatus.FULL,
        )

        // greyed rather than hidden when a device is offline: the number was true when it was read,
        // and a blank cell reads as "unknown" instead of "stale"
        DeviceColumn.FREE -> TableText(
            text = column.text(device),
            color = if (device.isOnline) Color.Unspecified else Colors.offline,
        )

        else -> TableText(column.text(device))
    }
}

@Composable
private fun Device.stateColor(): Color = when (state) {
    DeviceState.DEVICE -> Colors.online
    DeviceState.UNAUTHORIZED, DeviceState.AUTHORIZING -> Colors.unauthorized
    DeviceState.OFFLINE, DeviceState.DETACHED -> Colors.offline
    else -> Colors.error
}

/** the toolbar. everything acting on one device is disabled until one is selected. */
@Composable
private fun DeviceToolbar(
    device: Device?,
    onMirror: (Device?) -> Unit,
    onScreenshot: (Device) -> Unit,
    onRefresh: () -> Unit,
) {
    val hasDevice = device != null && device.isOnline
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.toolbarHeight + Dimens.gap)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(Res.drawable.add, stringResource(Res.string.action_connect), onClick = {}, enabled = false)
        ToolbarSeparator()

        ToolbarButton(Res.drawable.browse, stringResource(Res.string.action_browse), onClick = {}, enabled = false)
        ToolbarButton(Res.drawable.logs, stringResource(Res.string.action_view_logs), onClick = {}, enabled = false)
        ToolbarButton(Res.drawable.keyboard, stringResource(Res.string.action_input), onClick = {}, enabled = false)
        ToolbarSeparator()

        ToolbarButton(
            res = Res.drawable.mirror,
            label = stringResource(Res.string.action_mirror),
            onClick = { onMirror(device) },
            enabled = hasDevice,
        )
        ToolbarButton(
            res = Res.drawable.screenshot,
            label = stringResource(Res.string.action_screenshot),
            onClick = { device?.let(onScreenshot) },
            enabled = hasDevice,
        )
        ToolbarButton(Res.drawable.download, stringResource(Res.string.action_install), onClick = {}, enabled = false)
        ToolbarSeparator()

        ToolbarButton(Res.drawable.chart, stringResource(Res.string.action_stats), onClick = {}, enabled = false)
        ToolbarButton(Res.drawable.adb, stringResource(Res.string.action_adb), onClick = {}, enabled = false)

        Spacer(Modifier.weight(1f).widthIn(min = Dimens.gapLarge))

        ToolbarButton(Res.drawable.refresh, stringResource(Res.string.action_refresh), onClick = onRefresh)
        ToolbarButton(Res.drawable.share_off, stringResource(Res.string.action_share), onClick = {}, enabled = false)
        ToolbarButton(Res.drawable.settings, stringResource(Res.string.action_settings), onClick = {}, enabled = false)
    }
}

/** the right-click menu on a column header: hide this one, or put every column back. */
@Composable
private fun ColumnMenu(
    openFor: DeviceColumn?,
    hidden: Set<DeviceColumn>,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = openFor != null, onDismissRequest = onDismiss) {
        openFor?.let { column ->
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.hide_column, column.label)) },
                onClick = {
                    DeviceTableState.toggleHidden(column)
                    onDismiss()
                },
            )
        }
        HorizontalDivider()
        // every hidden column, so one can be put back without a settings dialog
        DeviceColumn.entries.filter { it in hidden }.forEach { column ->
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.show_column, column.label)) },
                onClick = {
                    DeviceTableState.toggleHidden(column)
                    onDismiss()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.reset_columns)) },
            onClick = {
                DeviceTableState.resetColumns()
                onDismiss()
            },
        )
    }
}

/** version and memory on the left, the device count on the right — the Swing app's status bar. */
@Composable
private fun DeviceStatusBar(total: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Res.drawable.refresh, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        StatusText("v$APP_VERSION")
        Spacer(Modifier.width(Dimens.gapLarge))
        AppIcon(Res.drawable.memory, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        // the app's own heap, not the machine's — 45 screencap buffers is what runs it out
        StatusText(PlatformUtils.getMemoryInfo().let { Utils.bytesToDisplayString(it.totalBytes - it.freeBytes) })

        Spacer(Modifier.weight(1f))
        StatusText(stringResource(Res.string.device_total, total))
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** a screenshot, straight to the desktop's home dir. */
private suspend fun saveScreenshot(device: Device) {
    val log = LoggerConfig.createLogger("DeviceScreen")
    DeviceManager.screenshot(device.serial)
        .onSuccess { bytes ->
            val name = "${device.displayName}-${DateUtils.formatDate("yyyyMMdd-HHmmss")}.png"
            val ok = StorageManager.writeBytes(PATH_SCREENSHOTS, name, bytes)
            log.d("saveScreenshot: $name, ${bytes.size} bytes, ok:$ok")
            ToastManager.showToast(if (ok) name else "Couldn't save the screenshot", success = ok)
        }
        .onFailure {
            log.e("saveScreenshot: ${it.message}")
            ToastManager.showToast(it.message ?: "Screenshot failed", success = false)
        }
}

const val PATH_SCREENSHOTS = "screenshots"
