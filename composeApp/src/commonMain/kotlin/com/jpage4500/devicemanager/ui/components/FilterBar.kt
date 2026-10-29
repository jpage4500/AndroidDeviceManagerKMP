package com.jpage4500.devicemanager.ui.components

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
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/** one filter that's on, as the filter bar shows it. */
class ActiveFilter(
    val label: String,
    /** what it tests, for the pill's tooltip. */
    val expression: String,
    /** a right-click's filter: this tab only, not saved. Drawn dashed. */
    val quick: Boolean,
    val onClick: () -> Unit,
    val onRemove: () -> Unit,
    /** a saved filter's right-click menu — edit, duplicate, delete. */
    val menu: (@Composable AppMenuScope.() -> Unit)? = null,
)

/**
 * the second row of a log tab's controls: the extra expression box, the filters that are on as
 * pills joined by AND, + Add filter, and Clear filters.
 *
 * every pill must pass — the same AND the Swing app applies to a multi-selection in its filter
 * list, said out loud between the pills rather than left for the user to know.
 */
@Composable
fun LogFilterBar(
    filterText: String,
    onFilterText: (String) -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    active: List<ActiveFilter>,
    saved: List<LogFilter>,
    selected: Set<String>,
    onToggleSaved: (LogFilter) -> Unit,
    onNewFilter: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.filterBarHeight)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fieldShape = RoundedCornerShape(Dimens.navCorner)
        Row(
            Modifier
                .width(Dimens.filterFieldWidth)
                .height(Dimens.chipHeight + Dimens.liveDot)
                .clip(fieldShape)
                .background(Colors.listBg)
                .border(Dimens.hairline, Colors.divider, fieldShape)
                .padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Res.drawable.filter, size = Dimens.icon, tint = Colors.secondaryText)
            Spacer(Modifier.width(Dimens.liveDot))
            FilterField(
                value = filterText,
                onValueChange = onFilterText,
                hint = stringResource(Res.string.logs_filter_this),
                modifier = Modifier.weight(1f).onFocusChanged { onFieldFocus(it.isFocused) },
            )
        }
        Spacer(Modifier.width(Dimens.rowPadding))

        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
        ) {
            active.forEachIndexed { index, filter ->
                if (index > 0) {
                    Text(
                        stringResource(Res.string.logs_and),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Colors.secondaryText,
                    )
                }
                FilterPill(filter)
            }
            AddFilterButton(saved, selected, onToggleSaved, onNewFilter)
        }

        if (active.isNotEmpty()) {
            Box(
                Modifier
                    .height(Dimens.chipHeight)
                    .clip(RoundedCornerShape(Dimens.navCorner))
                    .clickable(onClick = onClear)
                    .padding(horizontal = Dimens.gap),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(Res.string.logs_clear_filters), style = MaterialTheme.typography.labelMedium, color = Colors.secondaryText)
            }
        }
    }
}

@Composable
private fun FilterPill(filter: ActiveFilter) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val edge = Colors.pillBorder
    Box {
        CellTooltip(text = { filter.expression }) {
            Row(
                Modifier
                    .height(Dimens.chipHeight)
                    .clip(shape)
                    .background(if (filter.quick) Colors.listBg else Colors.pillBg)
                    // a quick filter's edge is dashed — it's this tab's, and it isn't saved
                    .drawBehind {
                        val stroke = Dimens.hairline.toPx()
                        drawRoundRect(
                            color = edge,
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(size.height / 2),
                            style = Stroke(
                                width = stroke,
                                pathEffect = if (filter.quick) PathEffect.dashPathEffect(floatArrayOf(4f * stroke, 3f * stroke)) else null,
                            ),
                        )
                    }
                    .contextClickable { if (filter.menu != null) menu = true }
                    .clickable(onClick = filter.onClick)
                    .padding(start = Dimens.navGap, end = Dimens.hairline * 3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    filter.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = Dimens.pillMaxWidth),
                )
                Spacer(Modifier.width(Dimens.hairline * 2))
                val remove = stringResource(Res.string.logs_remove_filter, filter.label)
                Box(
                    Modifier.size(Dimens.chipHeight - Dimens.liveDot).clip(CircleShape).clickable(onClick = filter.onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(Res.drawable.close, size = Dimens.pillClose, tint = Colors.secondaryText, contentDescription = remove)
                }
            }
        }
        filter.menu?.let { content ->
            AppPopupMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, Dimens.chipHeight)) { content() }
        }
    }
}

/** + Add filter: every saved filter as a tick, and New filter… under them. */
@Composable
private fun AddFilterButton(
    saved: List<LogFilter>,
    selected: Set<String>,
    onToggle: (LogFilter) -> Unit,
    onNew: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val edge = Colors.divider
    Box {
        Row(
            Modifier
                .height(Dimens.chipHeight)
                .clip(shape)
                .drawBehind {
                    val stroke = Dimens.hairline.toPx()
                    drawRoundRect(
                        color = edge,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * stroke, 3f * stroke))),
                    )
                }
                .clickable { open = true }
                .padding(horizontal = Dimens.navGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Res.drawable.add, size = Dimens.pillClose, tint = Colors.toolbarIcon)
            Spacer(Modifier.width(Dimens.tabGap))
            Text(stringResource(Res.string.logs_add_filter), style = MaterialTheme.typography.labelMedium, color = Colors.primaryText)
        }
        AppPopupMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, Dimens.chipHeight)) {
            saved.forEach { filter ->
                val on = filter.name in selected
                Item(
                    text = filter.name,
                    shortcut = filter.expression,
                    leading = { color ->
                        Box(Modifier.size(Dimens.icon), contentAlignment = Alignment.Center) {
                            if (on) AppIcon(Res.drawable.success, size = Dimens.pillClose, tint = color)
                        }
                    },
                ) { onToggle(filter) }
            }
            if (saved.isNotEmpty()) Separator()
            Item(stringResource(Res.string.logs_new_filter)) { onNew() }
        }
    }
}

/**
 * the V D I W E buttons: the lowest level shown. The one that's on is filled; those below it are
 * dimmed, since that's what's being hidden.
 */
@Composable
fun LevelFloorButtons(floor: LogLevelFloor, onFloor: (LogLevelFloor) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.hairline * 2)) {
        Text(
            stringResource(Res.string.logs_level),
            style = MaterialTheme.typography.labelMedium,
            color = Colors.secondaryText,
            modifier = Modifier.padding(end = Dimens.tabGap),
        )
        LogLevelFloor.entries.forEach { level ->
            val on = level == floor
            val below = level.ordinal < floor.ordinal
            val label = stringResource(level.label())
            CellTooltip(text = { label }) {
                Box(
                    Modifier
                        .size(Dimens.levelButton)
                        .clip(RoundedCornerShape(Dimens.checkboxCorner + Dimens.hairline))
                        .background(if (on) Colors.chipOn else Color.Transparent)
                        .clickable { onFloor(level) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        level.code.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            on -> Colors.chipOnText
                            below -> Colors.secondaryText.copy(alpha = 0.5f)
                            else -> Colors.primaryText
                        },
                    )
                }
            }
        }
    }
}

private fun LogLevelFloor.label() = when (this) {
    LogLevelFloor.VERBOSE -> Res.string.logs_level_verbose
    LogLevelFloor.DEBUG -> Res.string.logs_level_debug
    LogLevelFloor.INFO -> Res.string.logs_level_info
    LogLevelFloor.WARN -> Res.string.logs_level_warn
    LogLevelFloor.ERROR -> Res.string.logs_level_error
}
