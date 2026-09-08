package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
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
    val selection by DeviceManager.selection.collectAsState()
    val busy by DeviceManager.busy.collectAsState()
    val columns by DeviceTableState.visible.collectAsState()
    val widths by DeviceTableState.widths.collectAsState()
    val hidden by DeviceTableState.hidden.collectAsState()
    val sort by DeviceTableState.sort.collectAsState()
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf("") }
    var input by remember { mutableStateOf<InputRequest?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var inputFor by remember { mutableStateOf<List<String>>(emptyList()) }
    var commandsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    val installTitle = stringResource(Res.string.install_title)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val filterFocus = remember { FocusRequester() }
    val copyToClipboard = rememberClipboardCopy()
    val showTooltips by SettingsManager.getBoolAsState(AppPref.SHOW_TOOLTIPS)
    val upLabel = stringResource(Res.string.device_up)
    // hoisted rather than written at the call site, so the setting can remove it with `takeIf` —
    // DataTable reads a null tooltip as "no tooltips" and stops wrapping cells at all
    val cellTooltip: (Device, DeviceColumn, Boolean) -> String? = { row, column, clipped ->
        deviceTooltip(row, column, clipped, upLabel)
    }

    val visible = remember(devices, filter) { devices.filter { it.matches(filter) } }
    val sorted = remember(visible, sort) { DeviceTableState.sorted(visible) }

    // the first device to arrive is selected, so the toolbar has something to act on straight away.
    // only once — re-selecting on every change would fight the user
    var autoSelected by remember { mutableStateOf(false) }
    LaunchedEffect(sorted.firstOrNull()?.serial) {
        val first = sorted.firstOrNull() ?: return@LaunchedEffect
        if (autoSelected) return@LaunchedEffect
        autoSelected = true
        if (selection.isEmpty) DeviceManager.select(first.serial)
    }
    // the lead is the device the toolbar acts on; the rest of the selection is what a menu offers to
    // act on in bulk
    val device = devices.firstOrNull { it.serial == selection.lead }


    Column(
        modifier
            .fillMaxSize()
            // preview, so it reaches the filter box before the table's own key handling claims it
            .onPreviewKeyEvent { event ->
                val find = event.key == Key.F && (event.isMetaPressed || event.isCtrlPressed)
                if (event.type != KeyEventType.KeyDown || !find) return@onPreviewKeyEvent false
                runCatching { filterFocus.requestFocus() }
                true
            }
    ) {
        DeviceToolbar(
            device = device,
            filter = filter,
            onFilterChange = { filter = it },
            filterFocus = filterFocus,
            onMirror = { it?.let { d -> WindowManager.openMirror(d.serial) } },
            onScreenshot = { d -> scope.launch { saveScreenshot(d) } },
            onInput = { inputFor = DeviceManager.selectedSerials.value },
            onInstall = {
                scope.launch {
                    val files = pickLocalFiles(installTitle, extensions = listOf("apk"))
                    if (files.isNotEmpty()) InstallManager.send(DeviceManager.selectedSerials.value, files)
                }
            },
            onCommands = { commandsOpen = true },
            onSettings = { settingsOpen = true },
            onRefresh = { DeviceManager.refresh() },
        )
        HorizontalDivider(color = Colors.divider)

        Box(
            Modifier
                .weight(1f)
                // an APK installs, anything else is copied — see InstallManager
                .fileDropTarget(
                    enabled = selection.size > 0,
                    onDragChanged = { dropping = it },
                    onDrop = { files ->
                        scope.launch { InstallManager.send(DeviceManager.selectedSerials.value, files) }
                    },
                )
                .then(
                    if (dropping) Modifier.border(2.dp, Colors.accent) else Modifier
                )
        ) {
            if (sorted.isEmpty()) {
                EmptyState(
                    title = stringResource(
                        if (filter.isBlank()) Res.string.no_devices else Res.string.no_matches
                    ),
                    detail = stringResource(
                        if (filter.isBlank()) Res.string.no_devices_msg else Res.string.no_matches_msg
                    ),
                )
            } else {
                DataTable(
                    rows = sorted,
                    columns = columns,
                    widths = widths.withDefaults(columns),
                    sortedBy = sort.column,
                    sortAscending = sort.ascending,
                    selection = selection,
                    rowKey = { it.serial },
                    header = { it.label },
                    rowHeight = Dimens.tableRowHeight,
                    // typing "gal" jumps to the Galaxy, the way it does in any file manager
                    typeAheadText = { it.displayName },
                    onSelectionChange = { DeviceManager.setSelection(it) },
                    onSort = { DeviceTableState.sortBy(it) },
                    onResize = { column, width -> DeviceTableState.resize(column, width) },
                    onReorder = { from, to -> DeviceTableState.moveColumn(from, to) },
                    onActivate = { row -> WindowManager.openMirror(row.serial) },
                    tooltip = cellTooltip.takeIf { showTooltips },
                    rowMenu = { row, column ->
                        DeviceMenu(
                            device = row,
                            column = column,
                            onCopy = copyToClipboard,
                            onEdit = { input = it },
                            onAction = { action -> scope.launch { action() } },
                        )
                    },
                    headerMenu = { column ->
                        ColumnMenu(
                            column = column,
                            hidden = hidden,
                            onSizeToFit = { target ->
                                DeviceTableState.sizeToFit(target, widestIn(sorted, target, measurer, density))
                            },
                            onSizeAllToFit = {
                                columns.forEach { each ->
                                    DeviceTableState.sizeToFit(each, widestIn(sorted, each, measurer, density))
                                }
                            },
                        )
                    },
                ) { row, column ->
                    DeviceCell(row, column, busy = row.serial in busy)
                }
            }
        }

        HorizontalDivider(color = Colors.divider)
        DeviceStatusBar(
            total = devices.size,
            shown = sorted.size,
            selected = selection.size,
            onHistory = { historyOpen = true },
        )
    }

    InputDialog(input) { input = null }
    InputDialog(serials = inputFor, onDismiss = { inputFor = emptyList() })
    StatusHistoryDialog(open = historyOpen, onDismiss = { historyOpen = false })
    CommandDialog(open = commandsOpen, onDismiss = { commandsOpen = false })
    if (settingsOpen) SettingsDialog(onDismiss = { settingsOpen = false })
}

/** free-text filter: any field, case-insensitive — the Swing toolbar's search box. */
private fun Device.matches(filter: String): Boolean {
    if (filter.isBlank()) return true
    val text = filter.trim()
    return DeviceColumn.entries.any { column -> column.text(this).containsIgnoreCase(text) }
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
private fun DeviceCell(device: Device, column: DeviceColumn, busy: Boolean) {
    when (column) {
        DeviceColumn.NAME -> Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                res = if (device.isWireless) Res.drawable.device_remote else Res.drawable.android,
                size = Dimens.icon,
                tint = device.stateColor(busy),
            )
            Spacer(Modifier.width(Dimens.gap / 2))
            TableText(device.displayName)
        }

        DeviceColumn.BATTERY -> BatteryLevel(
            level = device.batteryLevel,
            charging = device.powerStatus.isCharging,
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

/** the device icon's colour: busy and not-ready both outrank the connection state. */
@Composable
private fun Device.stateColor(busy: Boolean): Color = when {
    busy -> Colors.busy
    isOnline && !isBooted -> Colors.unauthorized
    state == DeviceState.DEVICE -> Colors.online
    state == DeviceState.UNAUTHORIZED || state == DeviceState.AUTHORIZING -> Colors.unauthorized
    state == DeviceState.OFFLINE || state == DeviceState.DETACHED -> Colors.offline
    else -> Colors.error
}

/** the toolbar. everything acting on one device is disabled until one is selected. */
@Composable
private fun DeviceToolbar(
    device: Device?,
    filter: String,
    onFilterChange: (String) -> Unit,
    filterFocus: FocusRequester,
    onMirror: (Device?) -> Unit,
    onScreenshot: (Device) -> Unit,
    onInput: () -> Unit,
    onInstall: () -> Unit,
    onCommands: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val hasDevice = device != null && device.isOnline
    val iconTint = Colors.toolbarIcon
    // deliberately NOT horizontally scrollable: a weighted spacer is what pins the trailing group
    // to the right edge, and inside a scrolling Row the weight would be measured against an
    // infinite width. a narrow window clips the middle of the toolbar, which is what the Swing app
    // does too
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.toolbarHeight + Dimens.gap)
            .background(Colors.toolbarBg)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(
            res = Res.drawable.add,
            label = stringResource(Res.string.action_connect),
            onClick = { WindowManager.openConnect() },
            tint = iconTint,
        )
        ToolbarSeparator()

        ToolbarButton(
            res = Res.drawable.browse,
            label = stringResource(Res.string.action_browse),
            onClick = { device?.let { WindowManager.openFiles(it.serial) } },
            enabled = hasDevice,
            tint = iconTint,
        )
        ToolbarButton(
            res = Res.drawable.logs,
            label = stringResource(Res.string.action_view_logs),
            onClick = { device?.let { WindowManager.openLogs(it.serial) } },
            enabled = hasDevice,
            tint = iconTint,
        )
        ToolbarButton(
            res = Res.drawable.keyboard,
            label = stringResource(Res.string.action_input),
            onClick = onInput,
            enabled = hasDevice,
            tint = iconTint,
        )
        ToolbarSeparator()

        ToolbarButton(
            res = Res.drawable.mirror,
            label = stringResource(Res.string.action_mirror),
            onClick = { onMirror(device) },
            enabled = hasDevice,
            tint = iconTint,
        )
        ToolbarButton(
            res = Res.drawable.screenshot,
            label = stringResource(Res.string.action_screenshot),
            onClick = { device?.let(onScreenshot) },
            enabled = hasDevice,
            tint = iconTint,
        )
        ToolbarButton(
            res = Res.drawable.download,
            label = stringResource(Res.string.action_install),
            onClick = onInstall,
            enabled = hasDevice,
            tint = iconTint,
        )
        ToolbarSeparator()

        ToolbarButton(Res.drawable.chart, stringResource(Res.string.action_stats), onClick = {}, enabled = false, tint = iconTint)
        ToolbarButton(
            res = Res.drawable.adb,
            label = stringResource(Res.string.action_adb),
            onClick = onCommands,
            tint = iconTint,
        )

        Spacer(Modifier.weight(1f))

        FilterField(
            value = filter,
            onValueChange = onFilterChange,
            hint = stringResource(Res.string.filter_devices),
            focusRequester = filterFocus,
            modifier = Modifier
                .widthIn(min = 110.dp, max = 180.dp)
                .clip(RoundedCornerShape(Dimens.corner))
                .background(Colors.listBg)
                .padding(horizontal = Dimens.gap, vertical = 5.dp),
        )
        Spacer(Modifier.width(Dimens.gap))

        ToolbarButton(Res.drawable.refresh, stringResource(Res.string.action_refresh), onClick = onRefresh, tint = iconTint)
        ToolbarButton(Res.drawable.share_off, stringResource(Res.string.action_share), onClick = {}, enabled = false, tint = iconTint)
        ToolbarButton(
            res = Res.drawable.settings,
            label = stringResource(Res.string.action_settings),
            onClick = onSettings,
            tint = iconTint,
        )
    }
}

/**
 * what a right-click on a device row offers.
 *
 * the same shape as the Swing app's: edit what can be edited, copy, then the actions, then the
 * wireless-only ones. An offline device gets Reconnect and Remove and nothing else — everything
 * above needs a device to talk to.
 *
 * [column] is the one the click landed on, which is what "Copy Field" means by *field* — the Swing
 * menu copies the cell you right-clicked, not the row's name.
 */
@Composable
private fun AppMenuScope.DeviceMenu(
    device: Device,
    column: DeviceColumn?,
    onCopy: (String) -> Unit,
    onEdit: (InputRequest) -> Unit,
    onAction: (suspend () -> Unit) -> Unit,
) {
    val serial = device.serial
    val installMenuTitle = stringResource(Res.string.install_title)

    if (!device.isOnline) {
        if (device.isWireless) {
            Item(stringResource(Res.string.reconnect)) {
                onAction { DeviceManager.reconnect(serial).toast() }
            }
        }
        Item(stringResource(Res.string.remove)) { DeviceManager.remove(serial) }
        return
    }

    val editName = stringResource(Res.string.edit_name)
    val editPhone = stringResource(Res.string.edit_phone)
    val editCustom1 = stringResource(Res.string.edit_custom_1)
    val editCustom2 = stringResource(Res.string.edit_custom_2)

    Item(editName) {
        onEdit(InputRequest(editName, device.nickname.orEmpty()) { name ->
            onAction { DeviceManager.setNickname(serial, name).toast() }
        })
    }
    Item(editPhone) {
        onEdit(InputRequest(editPhone, device.customProperty(Device.CUST_PROP_PHONE).orEmpty()) { value ->
            onAction { DeviceManager.setCustomProperty(serial, Device.CUST_PROP_PHONE, value).toast() }
        })
    }
    Item(editCustom1) {
        onEdit(InputRequest(editCustom1, device.customProperty(Device.CUST_PROP_1).orEmpty()) { value ->
            onAction { DeviceManager.setCustomProperty(serial, Device.CUST_PROP_1, value).toast() }
        })
    }
    Item(editCustom2) {
        onEdit(InputRequest(editCustom2, device.customProperty(Device.CUST_PROP_2).orEmpty()) { value ->
            onAction { DeviceManager.setCustomProperty(serial, Device.CUST_PROP_2, value).toast() }
        })
    }
    Separator()

    Item(stringResource(Res.string.copy_field)) { onCopy(column?.text(device) ?: device.displayName) }
    Item(stringResource(Res.string.copy_line)) { onCopy(device.asLine()) }
    Separator()

    Item(stringResource(Res.string.device_details)) { WindowManager.openInfo(serial) }
    Item(stringResource(Res.string.action_browse)) { WindowManager.openFiles(serial) }
    Item(stringResource(Res.string.action_view_logs)) { WindowManager.openLogs(serial) }
    Item(stringResource(Res.string.action_mirror)) { WindowManager.openMirror(serial) }
    Item(stringResource(Res.string.action_screenshot)) { onAction { saveScreenshot(device) } }
    Item(stringResource(Res.string.action_install)) {
        onAction {
            val files = pickLocalFiles(installMenuTitle, extensions = listOf("apk"))
            if (files.isNotEmpty()) InstallManager.send(listOf(serial), files)
        }
    }
    Item(stringResource(Res.string.restart)) { onAction { DeviceManager.reboot(serial).toast() } }

    val commands by CommandStore.commands.collectAsState()
    if (commands.isNotEmpty()) {
        Separator()
        commands.forEach { entry ->
            Item(entry.name) {
                onAction { DeviceManager.runCommand(serial, entry.command, entry.name + " on").toast() }
            }
        }
    }

    if (device.isWireless) {
        Separator()
        Item(stringResource(Res.string.disconnect)) {
            onAction { DeviceManager.disconnect(serial).toast() }
        }
    }
}

/**
 * what a cell says when the pointer rests on it.
 *
 * Two columns answer whatever their width, because the cell cannot say it: a battery bar carries no
 * number, and Booted shows *when* a device came up rather than how long it has been up. Everything
 * else answers only when its value didn't fit, which is what makes a tooltip worth leaving on — the
 * Swing table's `getTextIfTruncated`, and the same two exceptions its `DeviceScreen` makes.
 */
private fun deviceTooltip(
    device: Device,
    column: DeviceColumn,
    clipped: Boolean,
    upLabel: String,
): String? = when (column) {
    DeviceColumn.BATTERY -> device.batteryLevel?.let { level ->
        val power = device.powerStatus.takeIf { it != PowerStatus.NONE }
        if (power != null) "$level% ($power)" else "$level%"
    }

    DeviceColumn.BOOTED -> device.bootTimeMs
        ?.let { "$upLabel ${Utils.formatDuration(DateUtils.nowMs() - it)}" }

    else -> column.text(device)?.takeIf { clipped }
}

/**
 * how wide [column] would have to be to show every value in full.
 *
 * measured against the rows actually loaded, header included — a column sized to fit and then
 * clipping its own title reads as broken.
 */
private fun widestIn(
    rows: List<Device>,
    column: DeviceColumn,
    measurer: TextMeasurer,
    density: Density,
): Dp {
    val style = TextStyle(fontFamily = Fonts.table, fontSize = Dimens.tableTextSize)
    val widest = (rows.mapNotNull { column.text(it) } + column.label)
        .maxOfOrNull { measurer.measure(it, style).size.width }
        ?: 0
    return with(density) { widest.toDp() }
}

/** every visible column's value, tab-separated — what "Copy Line" puts on the clipboard. */
private fun Device.asLine(): String =
    DeviceTableState.visible.value.joinToString("\t") { it.text(this).orEmpty() }

/** say what happened, since none of these actions change anything on screen by themselves. */
private fun <T> Result<T>.toast() {
    onFailure { ToastManager.showToast(it.message ?: "Command failed", success = false) }
}

/** the right-click menu on a column header: hide this one, put a hidden one back, or reset. */
@Composable
private fun AppMenuScope.ColumnMenu(
    column: DeviceColumn,
    hidden: Set<DeviceColumn>,
    onSizeToFit: (DeviceColumn) -> Unit,
    onSizeAllToFit: () -> Unit,
) {
    Item(stringResource(Res.string.size_to_fit)) { onSizeToFit(column) }
    Item(stringResource(Res.string.size_all_to_fit)) { onSizeAllToFit() }
    Separator()
    Item(
        text = stringResource(Res.string.hide_column, column.label),
        onClick = { DeviceTableState.toggleHidden(column) },
    )
    if (hidden.isNotEmpty()) {
        Separator()
        // every hidden column, so one can be put back without a settings dialog
        DeviceColumn.entries.filter { it in hidden }.forEach { hiddenColumn ->
            Item(
                text = stringResource(Res.string.show_column, hiddenColumn.label),
                onClick = { DeviceTableState.toggleHidden(hiddenColumn) },
            )
        }
    }
    Separator()
    Item(
        text = stringResource(Res.string.reset_columns),
        onClick = { DeviceTableState.resetColumns() },
    )
}

/** version and memory on the left, the device count on the right — the Swing app's status bar. */
@Composable
private fun DeviceStatusBar(total: Int, shown: Int, selected: Int, onHistory: () -> Unit) {
    val status by StatusManager.current.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight)
            .background(Colors.statusBarBg)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the version is the handle for "where are this app's own logs?" — same corner the Swing
        // status bar puts them behind
        Row(
            Modifier
                .plainClickable {
                    StorageManager.getAbsolutePath(PATH_LOGS, FILE_LOG)?.let(PlatformUtils::openUrl)
                }
                .padding(end = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Res.drawable.file_logs, size = 12.dp, tint = Colors.secondaryText)
            Spacer(Modifier.width(4.dp))
            StatusText("v$APP_VERSION")
        }
        Spacer(Modifier.width(Dimens.gapLarge))
        AppIcon(Res.drawable.memory, size = 12.dp, tint = Colors.secondaryText)
        Spacer(Modifier.width(4.dp))
        // the app's own heap, not the machine's — 45 screencap buffers is what runs it out
        StatusText(PlatformUtils.getMemoryInfo().let { Utils.bytesToDisplayString(it.totalBytes - it.freeBytes) })

        Spacer(Modifier.weight(1f))
        // whatever the app is doing right now, or "History" when it is idle — the Swing status bar
        Row(
            Modifier.plainClickable(onClick = onHistory).padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Res.drawable.logs, size = 12.dp, tint = Colors.secondaryText)
            Spacer(Modifier.width(4.dp))
            Text(
                text = status?.label ?: stringResource(Res.string.history),
                style = MaterialTheme.typography.labelSmall,
                color = if (status?.isError == true) Colors.error else Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // "2 of 7" while a selection is live, so a bulk action says what it will run on; while a
        // filter is on, what is being hidden
        StatusText(
            when {
                selected > 1 -> stringResource(Res.string.device_selected_of, selected, total)
                shown != total -> stringResource(Res.string.device_selected_of, shown, total)
                else -> stringResource(Res.string.device_total, total)
            }
        )
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Colors.secondaryText,
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

/**
 * what the app has been doing, newest first.
 *
 * the status bar only ever shows the latest line and then goes quiet; this is where "what did that
 * command say?" is answered after the fact.
 */
@Composable
private fun StatusHistoryDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val history by StatusManager.history.collectAsState()
    AppDialog(
        title = stringResource(Res.string.history),
        onDismiss = onDismiss,
        extraButton = {
            DialogButton(stringResource(Res.string.logs_clear)) { StatusManager.clearHistory() }
        },
        modifier = Modifier.width(520.dp),
    ) {
            if (history.isEmpty()) {
                Text(
                    text = stringResource(Res.string.history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                )
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    history.forEach { event ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                text = DateUtils.formatDate(DateUtils.timeToDate(event.timeMs), "HH:mm:ss"),
                                style = MaterialTheme.typography.labelSmall,
                                color = Colors.secondaryText,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                text = event.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (event.isError) Colors.error else Colors.primaryText,
                            )
                        }
                    }
                }
            }
    }
}
