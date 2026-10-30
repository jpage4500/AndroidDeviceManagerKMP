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
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// at or under this, a device counts towards the Low battery chip
private const val LOW_BATTERY_PERCENT = 20

/** the filter chips over the device table; each shows how many devices it would leave. */
enum class DeviceChip(val label: StringResource) {
    ALL(Res.string.chip_all),
    USB(Res.string.chip_usb),
    WIFI(Res.string.chip_wifi),
    LOW_BATTERY(Res.string.chip_low_battery),
    ATTENTION(Res.string.chip_attention);

    fun matches(device: Device): Boolean = when (this) {
        ALL -> true
        USB -> !device.isWireless
        WIFI -> device.isWireless
        LOW_BATTERY -> device.isOnline && (device.batteryLevel ?: 100) <= LOW_BATTERY_PERCENT
        // offline, unauthorized, or still booting — anything that can't be used as it stands
        ATTENTION -> !device.isReady
    }
}

/**
 * the Devices page's title row: filter chips, search, refresh and Connect.
 *
 * a chip only shows while it has something to count, so an all-USB desk isn't offered an empty
 * Wi-Fi filter — except the one that's on, which stays so it can be turned off again.
 */
@Composable
fun DeviceHeader(
    devices: List<Device>,
    chip: DeviceChip,
    onChip: (DeviceChip) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    filterFocus: FocusRequester,
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
        if (devices.isNotEmpty()) {
            DeviceChip.entries.forEach { entry ->
                val count = devices.count(entry::matches)
                if (entry == DeviceChip.ALL || count > 0 || entry == chip) {
                    FilterChip(
                        label = stringResource(entry.label, count),
                        on = entry == chip,
                        onClick = { onChip(if (entry == chip) DeviceChip.ALL else entry) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (devices.isNotEmpty()) {
            SearchBox(filter, onFilterChange, filterFocus)
        }
        IconBox(Res.drawable.refresh, stringResource(Res.string.action_refresh), onRefresh)
        PrimaryButton(Res.drawable.add, stringResource(Res.string.action_connect)) { WindowManager.openConnect() }
    }
}

@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .height(Dimens.chipHeight)
            .clip(shape)
            .background(if (on) Colors.chipOn else Color.Transparent)
            .border(Dimens.hairline, if (on) Colors.chipOn else Colors.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.navGap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) Colors.chipOnText else Colors.primaryText,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, focus: FocusRequester) {
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
            modifier = Modifier.weight(1f),
        )
    }
}

/** a bordered square holding one icon — refresh. */
@Composable
fun IconBox(icon: DrawableResource, label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    CellTooltip(text = { label }) {
        Box(
            Modifier
                .size(Dimens.controlHeight)
                .clip(shape)
                .background(Colors.listBg)
                .border(Dimens.hairline, Colors.divider, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, size = Dimens.icon, tint = Colors.toolbarIcon, contentDescription = label)
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
 * open a single thing (Files, Details) take the first ready device.
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
    onDetails: (Device) -> Unit,
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
        BarAction(Res.drawable.mirror, stringResource(Res.string.action_mirror), any) { onMirror(ready) }
        BarAction(Res.drawable.screenshot, stringResource(Res.string.action_screenshot), any) { onScreenshot(ready) }
        BarAction(Res.drawable.logs, stringResource(Res.string.sel_logs), any) { onLogs(ready) }
        BarAction(Res.drawable.folder, stringResource(Res.string.sel_files), first != null) { first?.let(onFiles) }
        BarAction(Res.drawable.download, stringResource(Res.string.action_install), any, onClick = onInstall)
        BarAction(Res.drawable.keyboard, stringResource(Res.string.sel_type_text), any, onClick = onInput)
        BarAction(Res.drawable.terminal, stringResource(Res.string.sel_commands), true, onClick = onCommands)
        if (ScriptHost.supported) {
            BarAction(Res.drawable.script, stringResource(Res.string.sel_run_script), any) { onRunScript(ready) }
        }
        BarAction(Res.drawable.about, stringResource(Res.string.sel_details), first != null) { first?.let(onDetails) }
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
