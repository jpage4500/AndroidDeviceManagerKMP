package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
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
import androidx.compose.foundation.text.selection.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * the Devices page: a header with filter chips, a bar of actions for the selection, every device as
 * a row in a table — or as a tile in [DeviceGrid] — and a status bar. See docs/REDESIGN.md.
 *
 * Still a table rather than a two-pane list — this is a screen for watching 45 devices at once, and
 * a split view spends half the window on one of them. The toolbar it used to have is gone: Connect
 * and refresh are in the header, Settings in the sidebar, and everything that acts on a device is in
 * the selection bar, the row's menu, or both. Mirroring opens a window of its own (see
 * [WindowManager]).
 */
@Composable
fun DeviceScreen(
    detailsOpen: Boolean,
    onDetailsOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val devices by DeviceManager.devices.collectAsState()
    val selection by DeviceManager.selection.collectAsState()
    val busy by DeviceManager.busy.collectAsState()
    val columns by DeviceTableState.visible.collectAsState()
    val widths by DeviceTableState.widths.collectAsState()
    val hidden by DeviceTableState.hidden.collectAsState()
    val sort by DeviceTableState.sort.collectAsState()
    val scope = rememberCoroutineScope()

    // a TextFieldValue rather than a String: typing on the page writes into it from outside the
    // field, and the caret has to follow that text or the next key lands in front of it
    var filter by remember { mutableStateOf(TextFieldValue()) }
    val search = filter.text.trim()
    var searchFocused by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf<InputRequest?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf<DeviceFilter>(DeviceChip.ALL) }
    val compact by SettingsManager.getBoolAsState(AppPref.DEVICES_COMPACT)
    // two-line rows already show the model under the name, so a Model column would say it twice
    val tableColumns = remember(columns, compact) {
        if (compact) columns else columns.filterNot { it == DeviceColumn.MODEL }
    }
    val viewName by SettingsManager.getAsState(AppPref.DEVICES_VIEW)
    val view = DeviceView.of(viewName)
    // the icon view's size: 0 in the pref means Fit, and the grid reports what it fitted to so the
    // slider can show it. A drag is held here until release, then saved — which ends Fit
    val savedIconSize by SettingsManager.getIntAsState(AppPref.DEVICES_ICON_SIZE)
    var draggedIconSize by remember { mutableStateOf<Int?>(null) }
    var fittedIconSize by remember { mutableStateOf(MIN_DEVICE_ICON) }
    val iconSize: Int? = draggedIconSize ?: savedIconSize.takeIf { it > 0 }
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

    // the search highlights rather than filters — a device with nothing matching stays in its place,
    // so the rows don't jump about as it's typed. The chips are what narrow the list
    val staleBeforeMs = rememberStaleBeforeMs()
    // only Not responding moves with the clock; any other chip shouldn't re-filter on every tick
    val staleKey = if (chip == DeviceChip.STALE) staleBeforeMs else 0L
    val visible = remember(devices, chip, staleKey) { devices.filter { chip.matches(it, staleBeforeMs) } }
    val sorted = remember(visible, sort) { DeviceTableState.sorted(visible) }

    // the first device to arrive is selected, so the toolbar has something to act on straight away.
    // only once — re-selecting on every change would fight the user
    var autoSelected by remember { mutableStateOf(false) }
    LaunchedEffect(sorted.firstOrNull()?.id) {
        val first = sorted.firstOrNull() ?: return@LaunchedEffect
        if (autoSelected) return@LaunchedEffect
        autoSelected = true
        if (selection.isEmpty) DeviceManager.select(first.id)
    }
    // the lead is the device the toolbar acts on; the rest of the selection is what a menu offers to
    // act on in bulk
    val device = devices.firstOrNull { it.id == selection.lead }
    val selected = remember(devices, selection) { devices.filter { it.id in selection.keys } }

    // a file dragged in from another app: where it is, and the row under it
    var dropAt by remember { mutableStateOf<Offset?>(null) }
    var dropRow by remember { mutableStateOf<Device?>(null) }
    // a selection of several is what a drop goes to wherever it lands. With one or none, it goes to
    // the row under the pointer — or, over no row, to the one that's selected
    val hovered = dropRow
    val dropIds = when {
        selection.size > 1 || hovered == null -> selected.map { it.id }
        else -> listOf(hovered.id)
    }

    CompositionLocalProvider(LocalSearch provides search) {
    Column(
        modifier
            .fillMaxSize()
            // preview, so it reaches the search box before the table's own key handling claims it
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val meta = event.isMetaPressed || event.isCtrlPressed
                when {
                    event.key == Key.F && meta -> runCatching { filterFocus.requestFocus() }
                    // Esc clears the search first; only an empty one lets it through to clear the
                    // selection
                    event.key == Key.Escape && filter.text.isNotEmpty() -> filter = TextFieldValue()
                    meta || event.isAltPressed || searchFocused -> return@onPreviewKeyEvent false
                    else -> {
                        // a printable key anywhere else on the page starts a search with it — keys
                        // with no character report 0xFFFF. A leading space would search for nothing
                        val codePoint = event.utf16CodePoint
                        if (codePoint == 0xFFFF || codePoint < 0x20 || codePoint in 0x7F..0x9F) return@onPreviewKeyEvent false
                        if (codePoint == 0x20 && filter.text.isEmpty()) return@onPreviewKeyEvent false
                        val text = filter.text + codePoint.toChar()
                        filter = TextFieldValue(text, TextRange(text.length))
                        runCatching { filterFocus.requestFocus() }
                    }
                }
                true
            }
    ) {
        DeviceHeader(
            devices = devices,
            chip = chip,
            onChip = { chip = it },
            staleBeforeMs = staleBeforeMs,
            filter = filter,
            onFilterChange = { filter = it },
            filterFocus = filterFocus,
            onFilterFocused = { searchFocused = it },
            view = view,
            onView = { SettingsManager.setString(AppPref.DEVICES_VIEW, it.name) },
            detailsOpen = detailsOpen,
            onDetails = { onDetailsOpen(!detailsOpen) },
            onRefresh = { DeviceManager.refresh() },
        )

        if (devices.isEmpty()) {
            DeviceOnboarding(onSettings = { settingsOpen = true }, modifier = Modifier.weight(1f))
        } else {
            SelectionBar(
                selected = selected,
                onMirror = { list -> MirrorLauncher.mirror(list.map { it.id }) },
                onScreenshot = { list -> scope.launch { list.forEach { DeviceManager.saveScreenshot(it) } } },
                onLogs = { list -> PageManager.openLogs(list.map { it.id }) },
                onFiles = { d -> WindowManager.openFiles(d.id) },
                onInstall = {
                    scope.launch {
                        val files = pickLocalFiles(installTitle, extensions = listOf("apk"))
                        if (files.isNotEmpty()) InstallManager.send(DeviceManager.selectedIds.value, files)
                    }
                },
                onInput = { PageManager.openInput(DeviceManager.selectedIds.value) },
                onCommands = { PageManager.openCommands(DeviceManager.selectedIds.value) },
                onRunScript = { list -> PageManager.openScripts(list.map { it.id }) },
                onClear = { DeviceManager.setSelection(TableSelection()) },
            )

            val cardShape = RoundedCornerShape(Dimens.cardCorner)
            Row(Modifier.weight(1f).padding(horizontal = Dimens.pageGutter)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(cardShape)
                        .border(Dimens.hairline, if (dropping) Colors.tabAccent else Colors.sidebarBorder, cardShape)
                        // an APK installs, anything else is copied — see InstallManager
                        .fileDropTarget(
                            enabled = sorted.isNotEmpty(),
                            onDragChanged = {
                                dropping = it
                                if (!it) dropAt = null
                            },
                            onMove = { dropAt = it },
                            onDrop = { files ->
                                val ids = dropIds
                                if (ids.isNotEmpty()) scope.launch { InstallManager.send(ids, files) }
                            },
                        )
                ) {
                    if (sorted.isEmpty()) {
                        EmptyState(
                            title = stringResource(Res.string.no_matches),
                            detail = stringResource(Res.string.no_matches_msg),
                        )
                    } else if (view == DeviceView.ICONS) {
                        DeviceGrid(
                            rows = sorted,
                            selection = selection,
                            busy = busy,
                            iconSize = iconSize,
                            onFitted = { fittedIconSize = it },
                            onSelectionChange = { DeviceManager.setSelection(it) },
                            onActivate = { row -> MirrorLauncher.mirror(row.id) },
                            dropAt = dropAt,
                            onDropRow = { dropRow = it },
                            dropKeys = if (dropping) dropIds.toSet() else emptySet(),
                            menu = { row ->
                                DeviceMenu(
                                    device = row,
                                    column = null,
                                    onCopy = copyToClipboard,
                                    onEdit = { input = it },
                                    onAction = { action -> scope.launch { action() } },
                                )
                            },
                        )
                    } else {
                        DataTable(
                            rows = sorted,
                            columns = tableColumns,
                            widths = widths.withDefaults(tableColumns),
                            fitColumns = true,
                            sortedBy = sort.column,
                            sortAscending = sort.ascending,
                            selection = selection,
                            rowKey = { it.id },
                            header = { it.label },
                            rowHeight = if (compact) Dimens.tableRowHeight else Dimens.deviceRowHeight,
                            onSelectionChange = { DeviceManager.setSelection(it) },
                            dropAt = dropAt,
                            onDropRow = { dropRow = it },
                            dropKeys = if (dropping) dropIds.toSet() else emptySet(),
                            onSort = { DeviceTableState.sortBy(it) },
                            onResize = { column, width -> DeviceTableState.resize(column, width) },
                            // indices into what the header draws, which can be short the Model column — mapped back
                            // onto the visible list the state reorders
                            onReorder = { from, to ->
                                val moved = tableColumns.getOrNull(from)
                                val target = tableColumns.getOrNull(to)
                                if (moved != null && target != null) {
                                    DeviceTableState.moveColumn(columns.indexOf(moved), columns.indexOf(target))
                                }
                            },
                            onActivate = { row -> MirrorLauncher.mirror(row.id) },
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
                                        tableColumns.forEach { each ->
                                            DeviceTableState.sizeToFit(each, widestIn(sorted, each, measurer, density))
                                        }
                                    },
                                )
                            },
                        ) { row, column ->
                            DeviceCell(
                                device = row,
                                column = column,
                                busy = row.id in busy,
                                compact = compact,
                            )
                        }
                    }
                }
                val shown = device
                if (detailsOpen && shown != null) {
                    Spacer(Modifier.width(Dimens.rowPadding))
                    DeviceDetailsPanel(
                        device = shown,
                        busy = shown.id in busy,
                        onClose = { onDetailsOpen(false) },
                    )
                }
            }
            Spacer(Modifier.height(Dimens.gap))
        }

        HorizontalDivider(color = Colors.divider)
        DeviceStatusBar(
            devices = devices,
            shown = sorted.size,
            selected = selection.size,
            onHistory = { historyOpen = true },
            slider = if (view == DeviceView.ICONS && devices.isNotEmpty()) {
                {
                    DeviceIconSizeSlider(
                        value = iconSize ?: fittedIconSize,
                        fitting = iconSize == null,
                        onFit = { SettingsManager.setInt(AppPref.DEVICES_ICON_SIZE, 0) },
                        onChange = { draggedIconSize = it },
                        onChangeFinished = {
                            SettingsManager.setInt(AppPref.DEVICES_ICON_SIZE, it)
                            draggedIconSize = null
                        },
                    )
                }
            } else {
                null
            },
        )
    }

    }

    InputDialog(input) { input = null }
    StatusHistoryDialog(open = historyOpen, onDismiss = { historyOpen = false })
    if (settingsOpen) SettingsDialog(onDismiss = { settingsOpen = false })
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
private fun DeviceCell(
    device: Device,
    column: DeviceColumn,
    busy: Boolean,
    compact: Boolean,
) {
    when (column) {
        // a dot for the state, and — unless the rows are compact — what the device is under its
        // name, or what it's waiting for when it can't be used yet
        DeviceColumn.NAME -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Dimens.stateDot).clip(CircleShape).background(device.stateColor(busy)))
            Spacer(Modifier.width(Dimens.gap))
            if (compact) {
                TableText(searchHighlighted(device.displayName))
            } else {
                Column {
                    TableText(searchHighlighted(device.displayName), color = if (device.isOnline) Color.Unspecified else Colors.secondaryText)
                    Text(
                        text = searchHighlighted(device.subtitle()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (device.needsAccepting) Colors.busy else Colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        DeviceColumn.BATTERY -> BatteryLevel(
            level = device.batteryLevel,
            charging = device.powerStatus.isCharging,
        )

        // greyed rather than hidden when a device is offline: the number was true when it was read,
        // and a blank cell reads as "unknown" instead of "stale"
        DeviceColumn.FREE -> TableText(
            text = searchHighlighted(column.text(device).orEmpty()),
            color = if (device.isOnline) Color.Unspecified else Colors.offline,
        )

        else -> TableText(searchHighlighted(column.text(device).orEmpty()))
    }
}

/** waiting on someone to tap Allow on the device itself. */
private val Device.needsAccepting: Boolean
    get() = state == DeviceState.UNAUTHORIZED || state == DeviceState.AUTHORIZING

/** the line under a device's name: its model, or what's stopping it from being used. */
@Composable
private fun Device.subtitle(): String = when {
    needsAccepting -> stringResource(Res.string.state_unauthorized)
    !isOnline -> stringResource(Res.string.state_offline)
    else -> model ?: serial
}

/** the device's state as a colour: busy and not-ready both outrank the connection state. */
@Composable
fun Device.stateColor(busy: Boolean): Color = when {
    busy -> Colors.busy
    isOnline && !isBooted -> Colors.unauthorized
    state == DeviceState.DEVICE -> Colors.online
    state == DeviceState.UNAUTHORIZED || state == DeviceState.AUTHORIZING -> Colors.unauthorized
    state == DeviceState.OFFLINE || state == DeviceState.DETACHED -> Colors.offline
    else -> Colors.error
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
    val deviceId = device.id
    val installMenuTitle = stringResource(Res.string.install_title)

    if (!device.isOnline) {
        if (device.isWireless) {
            Item(stringResource(Res.string.reconnect)) {
                onAction { DeviceManager.reconnect(deviceId).toast() }
            }
        }
        Item(stringResource(Res.string.remove)) { DeviceManager.remove(deviceId) }
        return
    }

    val editName = stringResource(Res.string.edit_name)
    val editPhone = stringResource(Res.string.edit_phone)
    val editCustom1 = stringResource(Res.string.edit_custom_1)
    val editCustom2 = stringResource(Res.string.edit_custom_2)

    Item(editName) {
        onEdit(InputRequest(editName, device.nickname.orEmpty()) { name ->
            onAction { DeviceManager.setNickname(deviceId, name).toast() }
        })
    }
    Item(editPhone) {
        onEdit(InputRequest(editPhone, device.customProperty(Device.CUST_PROP_PHONE).orEmpty()) { value ->
            onAction { DeviceManager.setCustomProperty(deviceId, Device.CUST_PROP_PHONE, value).toast() }
        })
    }
    Item(editCustom1) {
        onEdit(InputRequest(editCustom1, device.customProperty(Device.CUST_PROP_1).orEmpty()) { value ->
            onAction { DeviceManager.setCustomProperty(deviceId, Device.CUST_PROP_1, value).toast() }
        })
    }
    Item(editCustom2) {
        onEdit(InputRequest(editCustom2, device.customProperty(Device.CUST_PROP_2).orEmpty()) { value ->
            onAction { DeviceManager.setCustomProperty(deviceId, Device.CUST_PROP_2, value).toast() }
        })
    }
    Separator()

    Item(stringResource(Res.string.copy_field)) { onCopy(column?.text(device) ?: device.displayName) }
    Item(stringResource(Res.string.copy_line)) { onCopy(device.asLine()) }
    Separator()

    Item(stringResource(Res.string.device_details)) { PageManager.openDetails(deviceId) }
    Item(stringResource(Res.string.action_browse)) { WindowManager.openFiles(deviceId) }
    Item(stringResource(Res.string.action_view_logs)) { WindowManager.openLogs(deviceId) }
    Item(stringResource(Res.string.action_mirror)) { MirrorLauncher.mirror(deviceId) }
    Item(stringResource(Res.string.action_screenshot)) { onAction { DeviceManager.saveScreenshot(device) } }
    Item(stringResource(Res.string.action_install)) {
        onAction {
            val files = pickLocalFiles(installMenuTitle, extensions = listOf("apk"))
            if (files.isNotEmpty()) InstallManager.send(listOf(deviceId), files)
        }
    }
    Item(stringResource(Res.string.restart)) { onAction { DeviceManager.reboot(deviceId).toast() } }

    Separator()
    val commands by CommandStore.commands.collectAsState()
    commands.forEach { entry ->
        Item(entry.name) {
            onAction { DeviceManager.runCommand(deviceId, entry.command, entry.name + " on").toast() }
        }
    }
    Item(stringResource(Res.string.menu_run_command)) { PageManager.openCommands(listOf(deviceId)) }

    if (device.isWireless) {
        Separator()
        Item(stringResource(Res.string.disconnect)) {
            onAction { DeviceManager.disconnect(deviceId).toast() }
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

/**
 * the version and the heap on the left, what the app is doing in the middle, and how fresh the rows
 * are and how many there are on the right.
 *
 * In the icon view, [slider] sizes the icons from the right-hand corner, where FileExplorerKMP
 * keeps it.
 */
@Composable
private fun DeviceStatusBar(
    devices: List<Device>,
    shown: Int,
    selected: Int,
    onHistory: () -> Unit,
    slider: (@Composable () -> Unit)? = null,
) {
    val status by StatusManager.current.collectAsState()
    val total = devices.size
    // ticks so "12s ago" counts up between refreshes; the heap figure rides along with it
    var now by remember { mutableStateOf(DateUtils.nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STATUS_TICK_MS)
            now = DateUtils.nowMs()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight)
            .background(Colors.statusBarBg)
            .padding(horizontal = Dimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the version is the handle for "where are this app's own logs?" — same corner the Swing
        // status bar puts them behind, and opened in an editor as it does there
        val logPath = remember { StorageManager.getAbsolutePath(PATH_LOGS, FILE_LOG) }
        val logOpenFailed = stringResource(Res.string.status_log_open_failed, logPath.orEmpty())
        StatusBarButton(onClick = {
            if (logPath == null || !editLocalFile(logPath)) ToastManager.showToast(logOpenFailed, success = false)
        }) {
            AppIcon(Res.drawable.file_logs, size = 12.dp, tint = Colors.secondaryText)
            Spacer(Modifier.width(4.dp))
            StatusText("v$APP_VERSION")
        }
        Spacer(Modifier.width(Dimens.gapLarge))
        AppIcon(Res.drawable.memory, size = 12.dp, tint = Colors.secondaryText)
        Spacer(Modifier.width(4.dp))
        // the app's own heap, not the machine's — 45 screencap buffers is what runs it out. read
        // against [now] so it moves with the tick rather than only when the table changes
        val heap = remember(now) { PlatformUtils.getMemoryInfo().let { it.totalBytes - it.freeBytes } }
        StatusText(Utils.bytesToDisplayString(heap))

        Spacer(Modifier.weight(1f))
        // whatever the app is doing right now, or "History" when it is idle — the Swing status bar,
        // with its progress fill behind a running command. a toast takes this place while it's up
        StatusToastSlot {
            StatusBarButton(
                onClick = onHistory,
                progress = status?.progress ?: -1,
                isError = status?.isError == true,
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
        }
        Spacer(Modifier.weight(1f))

        // the newest details read across every device — each is re-read on its own timer
        val newest = devices.maxOfOrNull { it.lastUpdateMs ?: 0L } ?: 0L
        if (newest > 0L) {
            val secs = ((now - newest) / 1000).coerceAtLeast(0)
            StatusText(
                when {
                    secs < 5 -> stringResource(Res.string.device_refreshed_now)
                    secs < 60 -> stringResource(Res.string.device_refreshed_secs, secs.toInt())
                    else -> stringResource(Res.string.device_refreshed_mins, (secs / 60).toInt())
                }
            )
            Spacer(Modifier.width(Dimens.gapLarge))
        }
        // what a bulk action will run on while a selection is live; while a filter is on, what it hides
        StatusText(
            when {
                selected > 1 -> stringResource(Res.string.device_selected_count, selected, total)
                shown != total -> stringResource(Res.string.device_shown_of, shown, total)
                else -> pluralStringResource(Res.plurals.device_count, total, total)
            }
        )
        if (slider != null) {
            Spacer(Modifier.width(Dimens.gapLarge))
            slider()
        }
    }
}

// how often the status bar's clock moves; "refreshed 12s ago" doesn't need finer than this
private const val STATUS_TICK_MS = 5_000L

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Colors.secondaryText,
    )
}

/**
 * what the app has been doing, newest first — the Swing app's Status History.
 *
 * the status bar only ever shows the latest line and then goes quiet; this is where "what did that
 * command say?" is answered after the fact. A filter narrows it, rows multi-select and copy, and a
 * double-click opens a line's [StatusEvent.detail] — the per-device results a summary stands for.
 */
@Composable
private fun StatusHistoryDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val history by StatusManager.history.collectAsState()
    var filter by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var shownEvent by remember { mutableStateOf<StatusEvent?>(null) }
    val widths = remember { mutableStateMapOf<HistoryColumn, Dp>().apply { HistoryColumn.entries.forEach { put(it, it.width) } } }
    val headers = HistoryColumn.entries.associateWith { stringResource(it.label) }
    val copy = rememberClipboardCopy()
    // read here rather than in rowContentColor, which isn't composable
    val errorColor = Colors.error
    val textColor = Colors.primaryText
    val focus = remember { FocusRequester() }
    val rows = remember(history, filter) {
        if (filter.isBlank()) history
        else history.filter { it.label.contains(filter, true) || it.detail?.contains(filter, true) == true }
    }
    fun copySelected() {
        val text = rows.filter { it in selection }.joinToString("\n") { it.historyLine() }
        if (text.isNotEmpty()) copy(text)
    }
    AppDialog(
        title = stringResource(Res.string.history),
        onDismiss = onDismiss,
        extraButton = {
            DialogButton(stringResource(Res.string.logs_clear)) { StatusManager.clearHistory() }
        },
        modifier = Modifier.width(Dimens.historyDialogWidth),
    ) {
        FilterField(
            value = filter,
            onValueChange = { filter = it },
            hint = stringResource(Res.string.history_filter),
            focusRequester = focus,
            modifier = Modifier.width(Dimens.historyFilterWidth),
        )
        Spacer(Modifier.height(Dimens.gap))
        Box(
            Modifier
                .fillMaxWidth()
                .height(Dimens.historyTableHeight)
                .border(Dp.Hairline, Colors.divider)
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(Res.string.history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                DataTable(
                    rows = rows,
                    columns = HistoryColumn.entries,
                    widths = widths,
                    fitColumns = true,
                    sortedBy = null,
                    sortAscending = true,
                    selection = selection,
                    // the event itself: two lines can share a label and even a millisecond
                    rowKey = { it },
                    header = { headers.getValue(it) },
                    rowContentColor = { if (it.isError) errorColor else textColor },
                    onSelectionChange = { selection = it },
                    onResize = { column, width -> widths[column] = width },
                    onActivate = { shownEvent = it },
                    tooltip = { event, column, clipped ->
                        if (column == HistoryColumn.EVENT) event.detail ?: event.label.takeIf { clipped } else null
                    },
                    rowMenu = { event, _ ->
                        Item(stringResource(Res.string.copy)) { copySelected() }
                        Item(stringResource(Res.string.history_show_detail)) { shownEvent = event }
                    },
                ) { event, column ->
                    when (column) {
                        HistoryColumn.TIME -> TableText(event.historyTime())
                        HistoryColumn.EVENT -> TableText(
                            if (event.detail.isNullOrEmpty()) event.label
                            else "${event.label} — ${event.detail.lineSequence().joinToString(" | ")}"
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    shownEvent?.let { event ->
        AppDialog(
            title = event.historyTime(),
            onDismiss = { shownEvent = null },
            extraButton = {
                DialogButton(stringResource(Res.string.copy)) { copy(event.historyLine()) }
            },
            cancelLabel = stringResource(Res.string.close),
            modifier = Modifier.width(Dimens.historyDialogWidth),
        ) {
            SelectionContainer {
                Text(
                    text = listOfNotNull(event.label, event.detail?.takeIf { it.isNotEmpty() }).joinToString("\n\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (event.isError) Colors.error else Colors.primaryText,
                    modifier = Modifier
                        .heightIn(max = Dimens.historyTableHeight)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private enum class HistoryColumn(val label: StringResource, val width: Dp) {
    TIME(Res.string.run_col_time, 80.dp),
    EVENT(Res.string.history_col_event, 560.dp),
}

private fun StatusEvent.historyTime(): String = DateUtils.formatDate(DateUtils.timeToDate(timeMs), "HH:mm:ss")

/** a line as it copies: time, label, and any detail under it. */
private fun StatusEvent.historyLine(): String = buildString {
    append(historyTime()).append("  ").append(label)
    if (!detail.isNullOrEmpty()) append('\n').append(detail)
}
