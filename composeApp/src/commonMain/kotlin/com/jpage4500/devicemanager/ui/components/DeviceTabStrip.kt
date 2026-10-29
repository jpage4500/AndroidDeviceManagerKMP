package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/**
 * one tab per device, over a page that shows one of them — Logs and Files both.
 *
 * The open tab is white, taller, bold and capped in green; the rest sit back in grey. Height is the
 * half of that people notice first, which is why the strip is bottom-aligned: the open tab rises out
 * of a row of shorter ones. It has no bottom edge and its ground covers the strip's rule, so it
 * opens onto the page under it.
 *
 * The + opens a menu of the devices not already open here, and [onPick] is what it reports.
 */
@Composable
fun DeviceTabStrip(
    deviceIds: List<String>,
    active: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** the open tab's ground — whatever the page under it starts with, so the two are one shape. */
    openGround: Color = Colors.listBg,
    /** a count to badge a tab with — new errors, on a log tab — or null for none. */
    badge: @Composable (String) -> Int? = { null },
) {
    val devices by DeviceManager.devices.collectAsState()
    val border = Colors.sidebarBorder
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.tabStripHeight)
            // the strip's rule, which the open tab covers
            .drawBehind {
                val line = Dimens.hairline.toPx()
                val y = size.height - line / 2
                drawLine(border, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
            },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Dimens.tabGap),
    ) {
        // keyed, so what a tab remembers — its badge's flow — stays with its device when a tab before
        // it closes, rather than sliding onto the next one
        deviceIds.forEach { id ->
            key(id) {
                val device = devices.firstOrNull { it.id == id }
                DeviceTab(
                    name = device?.displayName ?: Device.serialOf(id),
                    online = device?.isOnline == true,
                    open = id == active,
                    openGround = openGround,
                    badge = badge(id),
                    onClick = { onSelect(id) },
                    onClose = { onClose(id) },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        AddTabButton(open = deviceIds, onPick = onPick)
    }
}

@Composable
private fun DeviceTab(
    name: String,
    online: Boolean,
    open: Boolean,
    openGround: Color,
    badge: Int?,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = Dimens.tabCorner, topEnd = Dimens.tabCorner)
    val accent = Colors.tabAccent
    val edge = Colors.sidebarBorder
    val ground = if (open) openGround else Colors.tabIdle
    Row(
        modifier
            .widthIn(max = Dimens.tabMaxWidth)
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
                .background(if (online) Colors.online else Colors.offline)
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
        if (badge != null && badge > 0) {
            Spacer(Modifier.width(Dimens.liveDot))
            Box(
                Modifier
                    .height(Dimens.tabBadge)
                    .widthIn(min = Dimens.tabBadge)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Colors.error)
                    .padding(horizontal = Dimens.tabGap),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (badge > MAX_BADGE) "$MAX_BADGE+" else badge.toString(),
                    fontSize = Dimens.countTextSize,
                    fontWeight = FontWeight.Bold,
                    color = Colors.onAccent,
                )
            }
        }
        Spacer(Modifier.width(Dimens.tabGap))
        val closeLabel = stringResource(Res.string.tab_close, name)
        Box(
            Modifier
                .size(Dimens.tabClose)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(Res.drawable.close, size = Dimens.tabCloseIcon, tint = Colors.secondaryText, contentDescription = closeLabel)
        }
    }
}

/** the + after the last tab: every device not already open, to open one more. */
@Composable
private fun AddTabButton(open: List<String>, onPick: (String) -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.tab_add)
    Box(Modifier.padding(bottom = Dimens.tabGap)) {
        Box(
            Modifier
                .size(Dimens.tabAdd)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(Res.drawable.add, size = Dimens.icon, tint = Colors.toolbarIcon, contentDescription = label)
        }
        AppPopupMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, Dimens.tabAdd + Dimens.tabGap)) {
            val choices = devices.filter { it.id !in open }
            if (choices.isEmpty()) {
                Item(stringResource(Res.string.tab_add_none), enabled = false) {}
            }
            choices.forEach { device ->
                Item(device.displayName, enabled = device.isOnline) { onPick(device.id) }
            }
        }
    }
}

// past this a badge says "99+": the count is "look at me", not a figure to read
private const val MAX_BADGE = 99
