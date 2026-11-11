package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/** what a tab's dot says: its colour, and the word its tooltip uses for it. */
class TabStatus(val color: Color, val label: String)

/**
 * one tab per device, over a page that shows one of them — Logs and Files both.
 *
 * The open tab is white, taller, bold and capped in green; the rest sit back in grey. Height is the
 * half of that people notice first, which is why the strip is bottom-aligned: the open tab rises out
 * of a row of shorter ones. It has no bottom edge and its ground covers the strip's rule, so it
 * opens onto the page under it.
 *
 * **Tabs shrink to [Dimens.tabMinWidth] and no further.** Past that the ones that don't fit go into a
 * "N more" menu, and the open tab is always one of those showing. 45 tabs squeezed into one row
 * would be 45 unreadable slivers.
 *
 * The + is the shared [DevicePickerButton]: a checklist of every device, titled [pickerTitle], where
 * ticking opens a tab ([onPick]) and unticking closes it ([onClose]).
 *
 * After it, a chip for each of the [recent] devices that's online with no tab open, newest first —
 * one click to bring back a device closed a minute ago. They take only room the tabs don't need: a
 * chip goes before a tab is squeezed under [Dimens.tabMinWidth], and with tabs in "N more" there are
 * none.
 */
@Composable
fun DeviceTabStrip(
    deviceIds: List<String>,
    active: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onPick: (String) -> Unit,
    recent: RecentDevices,
    modifier: Modifier = Modifier,
    /** the open tab's ground — whatever the page under it starts with, so the two are one shape. */
    openGround: Color = Colors.listBg,
    /** a tab's dot; null is the device's own online or offline. */
    status: @Composable (String) -> TabStatus? = { null },
    pickerTitle: String,
) {
    val devices by DeviceManager.devices.collectAsState()
    val recentIds by recent.ids.collectAsState()
    val border = Colors.sidebarBorder
    val onlineLabel = stringResource(Res.string.tab_online)
    val offlineLabel = stringResource(Res.string.tab_offline)
    val online = Colors.online
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Dimens.tabStripHeight)
            // the strip's rule, which the open tab covers
            .drawBehind {
                val line = Dimens.hairline.toPx()
                val y = size.height - line / 2
                drawLine(border, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
            },
    ) {
        val each = Dimens.tabMinWidth + Dimens.tabGap
        val room = maxWidth - Dimens.tabAdd - Dimens.tabGap
        val shown = if ((room / each).toInt() >= deviceIds.size) {
            deviceIds
        } else {
            val fit = ((room - Dimens.tabMoreWidth - Dimens.tabGap) / each).toInt().coerceAtLeast(1)
            val first = deviceIds.take(fit)
            // the open tab is never the one in the menu: it takes the last place that fits
            if (active == null || active in first || active !in deviceIds) first else first.dropLast(1) + active
        }
        val hidden = deviceIds.filter { it !in shown }
        val eachChip = Dimens.tabRecentWidth + Dimens.tabGap
        val chipRoom = room - each * deviceIds.size
        val chips = if (hidden.isNotEmpty() || chipRoom < eachChip) emptyList() else recentIds
            .filter { id -> id !in deviceIds && devices.any { it.id == id && it.isOnline } }
            .take((chipRoom / eachChip).toInt().coerceAtMost(MAX_RECENT_CHIPS))

        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Dimens.tabGap),
        ) {
            // keyed, so what a tab remembers — its status's flows, its close button's hover — stays
            // with its device when a tab before it closes, rather than sliding onto the next one
            shown.forEach { id ->
                key(id) {
                    val device = devices.firstOrNull { it.id == id }
                    val isOnline = device?.isOnline == true
                    DeviceTab(
                        name = device?.displayName ?: Device.serialOf(id),
                        status = status(id) ?: TabStatus(if (isOnline) online else Colors.offline, if (isOnline) onlineLabel else offlineLabel),
                        open = id == active,
                        openGround = openGround,
                        onClick = { onSelect(id) },
                        onClose = { onClose(id) },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (hidden.isNotEmpty()) {
                MoreTabsButton(hidden, devices, onSelect)
            }
            DevicePickerButton(
                title = pickerTitle,
                checked = deviceIds,
                onChange = { ids, on -> ids.forEach { if (on) onPick(it) else onClose(it) } },
                modifier = Modifier.padding(bottom = Dimens.tabGap),
            )
            chips.forEach { id ->
                key(id) {
                    RecentChip(devices.firstOrNull { it.id == id }?.displayName ?: Device.serialOf(id)) { onPick(id) }
                }
            }
        }
    }
}

// three is what reads as "the ones I just had", rather than a second row of tabs
private const val MAX_RECENT_CHIPS = 3

/** a recent device beside the +: an outline pill, quieter than a tab, that opens one for it. */
@Composable
private fun RecentChip(name: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    val label = stringResource(Res.string.tab_reopen, name)
    CellTooltip(text = { label }, modifier = Modifier.padding(bottom = Dimens.tabGap)) {
        Box(Modifier.height(Dimens.tabAdd), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .height(Dimens.chipHeight)
                    .widthIn(max = Dimens.tabRecentWidth)
                    .clip(shape)
                    .border(Dimens.hairline, Colors.divider, shape)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label }
                    .padding(horizontal = Dimens.navGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Res.drawable.recent, size = Dimens.tabCloseIcon, tint = Colors.secondaryText)
                Spacer(Modifier.width(Dimens.tabGap))
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "N more ▾": the tabs that didn't fit. */
@Composable
private fun MoreTabsButton(
    hidden: List<String>,
    devices: List<Device>,
    onSelect: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(bottom = Dimens.tabGap)) {
        Row(
            Modifier
                .height(Dimens.tabAdd)
                .widthIn(min = Dimens.tabMoreWidth)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable { menu = true }
                .padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(Res.string.tab_more, hidden.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Colors.secondaryText,
                maxLines = 1,
            )
            Spacer(Modifier.width(Dimens.tabGap))
            AppIcon(Res.drawable.arrow_down, size = Dimens.tabCloseIcon, tint = Colors.secondaryText)
        }
        AppPopupMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, Dimens.tabAdd + Dimens.tabGap)) {
            hidden.forEach { id ->
                val device = devices.firstOrNull { it.id == id }
                Item(device?.displayName ?: Device.serialOf(id)) { onSelect(id) }
            }
        }
    }
}

@Composable
private fun DeviceTab(
    name: String,
    status: TabStatus,
    open: Boolean,
    openGround: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = Dimens.tabCorner, topEnd = Dimens.tabCorner)
    val accent = Colors.tabAccent
    val edge = Colors.sidebarBorder
    val ground = if (open) openGround else Colors.tabIdle
    CellTooltip(text = { "$name · ${status.label}" }, modifier = modifier) {
        Row(
            Modifier
                .widthIn(min = Dimens.tabMinWidth, max = Dimens.tabMaxWidth)
                .height(if (open) Dimens.tabHeightOpen else Dimens.tabHeight)
                .clip(shape)
                .background(ground)
                // edges drawn rather than a border: the open tab has sides and a green cap but **no
                // bottom**, so it runs on into the panel under it; a closed one keeps the strip's rule
                .drawWithContent {
                    drawContent()
                    val line = Dimens.hairline.toPx()
                    if (open) {
                        drawLine(edge, Offset(line / 2, 0f), Offset(line / 2, size.height), line)
                        drawLine(edge, Offset(size.width - line / 2, 0f), Offset(size.width - line / 2, size.height), line)
                        drawRect(accent, size = Size(size.width, Dimens.tabAccent.toPx()))
                    } else {
                        drawLine(edge, Offset(0f, size.height - line / 2), Offset(size.width, size.height - line / 2), line)
                    }
                }
                .clickable(onClick = onClick)
                .padding(start = Dimens.rowPadding, end = Dimens.tabGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Dimens.tabDot)
                    .clip(CircleShape)
                    .background(status.color)
            )
            Spacer(Modifier.width(Dimens.gap))
            Text(
                text = name,
                color = if (open) Colors.primaryText else Colors.secondaryText,
                fontWeight = if (open) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Dimens.tabGap))
            CloseButton(stringResource(Res.string.tab_close, name), onClose)
        }
    }
}

/**
 * a tab's ×: the cross alone, with a circle behind it only while the pointer is over it or it has
 * the keyboard — the design's. The circled icon it replaced sat on every tab at once, a row of grey
 * discs louder than the names beside them.
 */
@Composable
private fun CloseButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val lit = hovered || focused
    val ink = if (lit) Colors.primaryText else Colors.secondaryText
    Box(
        Modifier
            .size(Dimens.tabClose)
            .clip(CircleShape)
            .background(if (lit) Colors.secondaryText.copy(alpha = 0.16f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(Dimens.tabCloseCross)) {
            val stroke = Dimens.tabCloseStroke.toPx()
            drawLine(ink, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
            drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
        }
    }
}
