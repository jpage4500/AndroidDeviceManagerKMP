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
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// at or under this, a device counts towards the Low battery chip
private const val LOW_BATTERY_PERCENT = 20

// at or over this much of its storage used, a device counts towards the Low storage chip
private const val LOW_STORAGE_PERCENT = 90

// a device not heard from in this many refresh cycles counts towards the Not responding chip. One
// late read is a slow device; three in a row on a 45-device rig is a hub port or a wedged adbd
private const val STALE_CYCLES = 3

// how often the stale cut-off moves on, so a device turns stale without anything else changing
private const val STALE_TICK_MS = 5_000L

/** what a chip narrows the device list to: a problem, or a value several devices share. */
sealed interface DeviceFilter {
    /** [staleBeforeMs] is the cut-off for [DeviceChip.STALE] — see [rememberStaleBeforeMs]. */
    fun matches(device: Device, staleBeforeMs: Long): Boolean
}

/**
 * the fixed filter chips over the device table.
 *
 * past All, every one counts a problem, so the row reads as the desk's health: on a good day it's
 * All on its own. How a device is connected is a column to sort by, not a filter.
 */
enum class DeviceChip(val label: StringResource, val alert: Boolean = false) : DeviceFilter {
    ALL(Res.string.chip_all),
    ATTENTION(Res.string.chip_attention, alert = true),
    NOT_CHARGING(Res.string.chip_not_charging),
    LOW_BATTERY(Res.string.chip_low_battery),
    LOW_STORAGE(Res.string.chip_low_storage),
    STALE(Res.string.chip_stale);

    override fun matches(device: Device, staleBeforeMs: Long): Boolean = when (this) {
        ALL -> true
        // offline, unauthorized, or still booting — anything that can't be used as it stands
        ATTENTION -> !device.isReady
        // plugged in over usb but drawing no power: on a hub, a bad port or one sharing too much.
        // only once the battery has been read — before that, NONE is the default, not an answer
        NOT_CHARGING -> device.isOnline && !device.isWireless && device.batteryLevel != null &&
            !device.powerStatus.isCharging
        LOW_BATTERY -> device.isOnline && (device.batteryLevel ?: 100) <= LOW_BATTERY_PERCENT
        LOW_STORAGE -> device.isOnline && (device.storageUsedPercent ?: 0) >= LOW_STORAGE_PERCENT
        // listed as online, but the refresh has stopped getting answers from it
        STALE -> device.isOnline && (device.lastUpdateMs ?: Long.MAX_VALUE) < staleBeforeMs
    }
}

/** a field whose values sort devices into groups: what a [GroupChip] filters on. */
sealed interface GroupField {
    fun read(device: Device): String?

    fun valueOf(device: Device): String? = read(device)?.trim()?.takeIf { it.isNotEmpty() }
}

/** the fields every install has. */
enum class FixedGroupField(val label: StringResource, private val reader: (Device) -> String?) : GroupField {
    OS(Res.string.chip_os, { it.os }),
    CUSTOM_1(Res.string.chip_value, { it.customProperty(Device.CUST_PROP_1) }),
    CUSTOM_2(Res.string.chip_value, { it.customProperty(Device.CUST_PROP_2) });

    override fun read(device: Device): String? = reader(device)
}

/**
 * a custom column's values — "Inventory 3.4.1", the few devices still on an old build.
 *
 * keyed by the column's [CustomColumn.valueKey] rather than the column itself, so renaming it
 * doesn't turn off a chip that's on.
 */
data class ColumnGroupField(val valueKey: String, val label: String, val versions: Boolean) : GroupField {
    constructor(column: CustomColumn) : this(column.valueKey, column.label, column.type == CustomColumnType.VERSION)

    override fun read(device: Device): String? = device.customValues[valueKey]

    override fun equals(other: Any?): Boolean = other is ColumnGroupField && valueKey == other.valueKey

    override fun hashCode(): Int = valueKey.hashCode()
}

/** the devices whose [field] reads [value] — a chip made from what the devices themselves say. */
data class GroupChip(val field: GroupField, val value: String) : DeviceFilter {
    override fun matches(device: Device, staleBeforeMs: Long): Boolean = field.valueOf(device) == value
}

/**
 * a chip for every value of a [GroupField] that more than one device shares, with its count — the
 * fixed fields, then each custom column in table order.
 *
 * one device on its own is a row to look at rather than a group to filter to, and a value every
 * device shares would only be All again. OS and app versions run newest first, everything else A
 * to Z — numbers in it as numbers.
 */
fun groupChips(devices: List<Device>, columns: List<CustomColumn>): List<Pair<GroupChip, Int>> {
    val fields: List<GroupField> = FixedGroupField.entries + columns.map { ColumnGroupField(it) }.distinct()
    return fields.flatMap { field ->
        val counts = devices.mapNotNull(field::valueOf).groupingBy { it }.eachCount()
            .filter { (_, count) -> count in 2 until devices.size }
        val values = when {
            field == FixedGroupField.OS -> counts.keys.sortedWith(compareByDescending<String> { versionKey(it) }.thenBy { it })
            field is ColumnGroupField && field.versions -> counts.keys.sortedByDescending { NaturalKey(it) }
            else -> counts.keys.sortedBy { NaturalKey(it) }
        }
        values.map { GroupChip(field, it) to counts.getValue(it) }
    }
}

// "13" before "9", and "8.1" after "8": each part as a number, padded so they compare as text
private fun versionKey(version: String): String =
    version.split('.').take(3).joinToString(".") { part ->
        (part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0).toString().padStart(4, '0')
    }

/**
 * the time before which a device's last answer makes it stale, moving on every few seconds.
 *
 * read from the details refresh interval, so a desk that refreshes every five minutes doesn't call
 * every device stale after a minute.
 */
@Composable
fun rememberStaleBeforeMs(): Long {
    val refreshSecs by SettingsManager.getIntAsState(AppPref.DETAILS_REFRESH_SECS)
    var now by remember { mutableStateOf(DateUtils.nowMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(STALE_TICK_MS)
            now = DateUtils.nowMs()
        }
    }
    return now - refreshSecs.coerceAtLeast(5) * 1000L * STALE_CYCLES
}

/**
 * the Devices page's title row: filter chips, search, List / Icons, the details panel's toggle,
 * refresh and Connect.
 *
 * a chip only shows while it would narrow the list — none matching is nothing to filter to, and all
 * of them matching is All again — except the one that's on, which stays so it can be turned off.
 */
@Composable
fun DeviceHeader(
    devices: List<Device>,
    chip: DeviceFilter,
    onChip: (DeviceFilter) -> Unit,
    staleBeforeMs: Long,
    filter: TextFieldValue,
    onFilterChange: (TextFieldValue) -> Unit,
    filterFocus: FocusRequester,
    onFilterFocused: (Boolean) -> Unit,
    view: DeviceView,
    onView: (DeviceView) -> Unit,
    detailsOpen: Boolean,
    onDetails: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(Dimens.pageHeaderHeight).padding(horizontal = Dimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        Text(
            stringResource(Res.string.devices),
            fontSize = Dimens.pageTitleSize,
            fontWeight = FontWeight.Bold,
            color = Colors.primaryText,
            modifier = Modifier.padding(end = Dimens.gap),
        )
        // the chips take what the controls on the right leave and scroll inside it, so a narrow
        // window or a bad day's worth of chips never pushes Search and Connect off the edge
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            if (devices.isNotEmpty()) DeviceChip.entries.forEach { entry ->
                val count = devices.count { entry.matches(it, staleBeforeMs) }
                if (entry == DeviceChip.ALL || entry == chip || count in 1 until devices.size) {
                    val dot = when {
                        entry == DeviceChip.ALL -> null
                        entry.alert -> Colors.chipAlert
                        else -> Colors.chipWarn
                    }
                    FilterChip(
                        label = stringResource(entry.label),
                        leading = dot?.let { { Box(Modifier.size(Dimens.chipDot).background(it, CircleShape)) } },
                        on = entry == chip,
                        onClick = { onChip(if (entry == chip) DeviceChip.ALL else entry) },
                    )
                }
            }
            // the groups the devices fall into, after the problems — plus the one that's on, kept
            // even once nothing matches it so it can be turned off
            // only the custom columns on show: a hidden one is something nobody's looking at
            val shownColumns by DeviceTableState.visible.collectAsState()
            val columns = remember(shownColumns) { shownColumns.filterIsInstance<CustomColumn>() }
            val found = remember(devices, columns) { groupChips(devices, columns) }
            val groups = if (chip is GroupChip && found.none { it.first == chip }) {
                found + (chip to devices.count { chip.matches(it, staleBeforeMs) })
            } else found
            if (devices.isNotEmpty() && groups.isNotEmpty()) {
                Box(Modifier.width(Dimens.hairline).height(Dimens.headerSeparator).background(Colors.divider))
                groups.forEach { (group, _) ->
                    val tagTint = if (group == chip) Colors.chipOnText else Colors.secondaryText
                    val field = group.field
                    FilterChip(
                        label = when (field) {
                            is FixedGroupField -> stringResource(field.label, group.value)
                            is ColumnGroupField -> stringResource(Res.string.chip_column, field.label, group.value)
                        },
                        // a custom field holds whatever was typed into it, so the tag says where
                        // the value came from
                        leading = if (field == FixedGroupField.OS) null else ({
                            PathIcon(GlyphPaths.TAG, size = Dimens.chipIcon, tint = tagTint)
                        }),
                        on = group == chip,
                        onClick = { onChip(if (group == chip) DeviceChip.ALL else group) },
                    )
                }
            }
        }
        if (devices.isNotEmpty()) {
            SearchBox(filter, onFilterChange, filterFocus, onFilterFocused)
            DeviceViewToggle(view, onView)
            IconBox(Res.drawable.about, stringResource(Res.string.sel_details), on = detailsOpen, onClick = onDetails)
        }
        IconBox(Res.drawable.refresh, stringResource(Res.string.action_refresh), onClick = onRefresh)
        PrimaryButton(Res.drawable.add, stringResource(Res.string.action_connect)) { WindowManager.openConnect() }
    }
}

@Composable
private fun FilterChip(
    label: String,
    leading: (@Composable () -> Unit)?,
    on: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(Dimens.chipHeight)
            .clip(shape)
            .background(if (on) Colors.chipOn else Color.Transparent)
            .border(Dimens.hairline, if (on) Colors.chipOn else Colors.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.navGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap / 2),
    ) {
        leading?.invoke()
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) Colors.chipOnText else Colors.primaryText,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchBox(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    onFocused: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        Modifier
            .width(Dimens.searchWidth)
            .height(Dimens.controlHeight)
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.divider, shape)
            .padding(horizontal = Dimens.navGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // FilterField draws its own funnel
        FilterField(
            value = value,
            onValueChange = onValueChange,
            hint = stringResource(Res.string.devices_search),
            focusRequester = focus,
            modifier = Modifier.weight(1f).onFocusChanged { onFocused(it.isFocused) },
        )
    }
}

/** a bordered square holding one icon, its [label] as the tooltip — refresh, the details panel's actions. */
@Composable
fun IconBox(icon: DrawableResource, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconBox(label, on = on, enabled = enabled, onClick = onClick) { tint ->
        AppIcon(icon, size = Dimens.icon, tint = tint, contentDescription = label)
    }
}

/** the same square around any icon — [icon] is handed its tint, lit when [on] and dimmed when disabled. */
@Composable
fun IconBox(
    label: String,
    on: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    CellTooltip(text = { label }) {
        Box(
            Modifier
                .size(Dimens.controlHeight)
                .clip(shape)
                // on: the List / Icons toggle's lit segment, so the two read as one family
                .background(if (on) Colors.chipOn else Colors.listBg)
                .border(Dimens.hairline, if (on) Colors.chipOn else Colors.divider, shape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (on) Colors.chipOnText else Colors.toolbarIcon
            icon(tint.copy(alpha = if (enabled) 1f else 0.4f))
        }
    }
}

/** the page's one filled button. */
@Composable
fun PrimaryButton(icon: DrawableResource?, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(Dimens.controlHeight)
            .clip(RoundedCornerShape(Dimens.navCorner))
            .background(Colors.primaryButton)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.rowPadding + Dimens.hairline * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, size = Dimens.icon, tint = Colors.onPrimaryButton)
            Spacer(Modifier.width(Dimens.liveDot))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Colors.onPrimaryButton)
    }
}

/** a bordered button with a label, and an icon before it if given — the secondary actions on a page. */
@Composable
fun OutlinedAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        modifier
            .height(Dimens.controlHeight)
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            AppIcon(icon, size = Dimens.pillClose + Dimens.tabGap, tint = Colors.toolbarIcon)
            Spacer(Modifier.width(Dimens.liveDot))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = Colors.primaryText)
    }
}

/**
 * the bar over the device table: a hint while nothing is selected, and with a selection, what can
 * be done to all of it at once.
 *
 * the actions act on the selected devices that are **ready** — an offline device in a selection of
 * ten doesn't stop the other nine being mirrored — and are disabled when none are. The ones that
 * open a single thing (Files) take the first ready device.
 */
@Composable
fun SelectionBar(
    selected: List<Device>,
    onMirror: (List<Device>) -> Unit,
    onScreenshot: (List<Device>) -> Unit,
    onLogs: (List<Device>) -> Unit,
    onFiles: (Device) -> Unit,
    onInstall: () -> Unit,
    onInput: () -> Unit,
    onCommands: () -> Unit,
    onRunScript: (List<Device>) -> Unit,
    onClear: () -> Unit,
) {
    val ready = selected.filter { it.isReady }
    val first = ready.firstOrNull()
    val any = ready.isNotEmpty()
    SelectionBarFrame(
        count = selected.size,
        hint = stringResource(Res.string.selection_hint),
        onClear = onClear,
        modifier = Modifier.padding(start = Dimens.pageGutter, end = Dimens.pageGutter, bottom = Dimens.rowPadding),
    ) {
        BarAction(Res.drawable.mirror, stringResource(Res.string.action_mirror), any, tooltip = stringResource(Res.string.tip_sel_mirror)) { onMirror(ready) }
        BarAction(Res.drawable.screenshot, stringResource(Res.string.action_screenshot), any, tooltip = stringResource(Res.string.tip_sel_screenshot)) { onScreenshot(ready) }
        BarAction(Res.drawable.logs, stringResource(Res.string.sel_logs), any, tooltip = stringResource(Res.string.tip_sel_logs)) { onLogs(ready) }
        BarAction(Res.drawable.folder, stringResource(Res.string.sel_files), first != null, tooltip = stringResource(Res.string.tip_sel_files)) { first?.let(onFiles) }
        BarAction(Res.drawable.download, stringResource(Res.string.action_install), any, tooltip = stringResource(Res.string.tip_sel_install), onClick = onInstall)
        BarAction(Res.drawable.keyboard, stringResource(Res.string.sel_type_text), any, tooltip = stringResource(Res.string.tip_sel_type_text), onClick = onInput)
        BarAction(Res.drawable.terminal, stringResource(Res.string.sel_commands), true, tooltip = stringResource(Res.string.tip_sel_commands), onClick = onCommands)
        if (ScriptHost.supported) {
            BarAction(Res.drawable.script, stringResource(Res.string.sel_run_script), any, tooltip = stringResource(Res.string.tip_sel_run_script)) { onRunScript(ready) }
        }
    }
}

/**
 * a selection bar's frame — Devices' and Files' both: [hint] while [count] is 0, and with a
 * selection, the count, [actions] and Clear.
 *
 * @param label what the selection is called, if more than its count — Files adds the total size
 * @param outlined false where the bar sits inside a card of its own, which is the outline it needs
 */
@Composable
fun SelectionBarFrame(
    count: Int,
    hint: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    outlined: Boolean = true,
    actions: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    val hasSelection = count > 0
    val outline = when {
        !outlined -> Color.Transparent
        hasSelection -> Colors.selectionBarBorder
        else -> Colors.sidebarBorder
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.selectionBarHeight)
            .clip(shape)
            .background(if (hasSelection) Colors.selectionBar else Color.Transparent)
            .border(Dimens.hairline, outline, shape)
            .padding(start = Dimens.rowPadding + Dimens.hairline * 2, end = Dimens.liveDot),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hasSelection) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }
        Text(
            label ?: stringResource(Res.string.selection_count, count),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.primaryText,
            maxLines = 1,
            modifier = Modifier.padding(end = Dimens.navGap),
        )
        // scrolls rather than clipping when the window is narrower than the row of actions
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
        Box(
            Modifier
                .height(Dimens.controlHeight - Dimens.gap / 2)
                .clip(RoundedCornerShape(Dimens.navCorner))
                .clickable(onClick = onClear)
                .padding(horizontal = Dimens.navGap),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.selection_clear), style = MaterialTheme.typography.bodyMedium, color = Colors.secondaryText)
        }
    }
}

@Composable
fun BarAction(
    icon: DrawableResource,
    label: String,
    enabled: Boolean,
    /** true for the one that can't be undone — Delete — which is drawn in the error colour. */
    danger: Boolean = false,
    /** what the action does, said when the pointer rests on it — the label alone is one word. */
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    if (tooltip == null) BarActionContent(icon, label, enabled, danger, onClick)
    else CellTooltip(text = { tooltip }) { BarActionContent(icon, label, enabled, danger, onClick) }
}

@Composable
private fun BarActionContent(
    icon: DrawableResource,
    label: String,
    enabled: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .height(Dimens.controlHeight + Dimens.hairline * 2)
            .clip(RoundedCornerShape(Dimens.navCorner))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.navGap - Dimens.hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, size = Dimens.icon, tint = (if (danger) Colors.error else Colors.toolbarIcon).copy(alpha = alpha))
        Spacer(Modifier.width(Dimens.liveDot))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = (if (danger) Colors.error else Colors.primaryText).copy(alpha = alpha),
            maxLines = 1,
            softWrap = false,
        )
    }
}
