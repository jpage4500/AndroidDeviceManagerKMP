package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** one filter that's on, as the filter bar shows it. */
class ActiveFilter(
    val label: String,
    /** what it tests, in words, for the pill's tooltip. */
    val expression: String,
    /** a right-click's filter: this tab only, not saved. Drawn dashed. */
    val quick: Boolean,
    val onClick: () -> Unit,
    val onRemove: () -> Unit,
    /** muted after the name — a saved filter's "2 rules". */
    val detail: String? = null,
    /** a saved filter's right-click menu — edit, duplicate, delete. */
    val menu: (@Composable AppMenuScope.() -> Unit)? = null,
)

/**
 * the second row of a log tab's controls: "Filters", the ones that are on as pills joined by AND,
 * + Add filter, and Clear filters.
 *
 * every pill must pass — the same AND the Swing app applies to a multi-selection in its filter
 * list, said out loud between the pills rather than left for the user to know. These, the level
 * button and the typed filter box are what take lines away; search only highlights.
 *
 * [names] is pid -> process, so a filter on the App column reads as the app rather than a number.
 */
@Composable
fun LogFilterBar(
    active: List<ActiveFilter>,
    saved: List<LogFilter>,
    selected: Set<String>,
    names: Map<String, String>,
    onToggleSaved: (LogFilter) -> Unit,
    onEditSaved: (LogFilter) -> Unit,
    onDeleteSaved: (LogFilter) -> Unit,
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
        AppIcon(Res.drawable.filter, size = Dimens.pillClose + Dimens.hairline * 3, tint = Colors.secondaryText)
        Spacer(Modifier.width(Dimens.liveDot))
        Text(
            stringResource(Res.string.logs_filters),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.secondaryText,
        )
        Spacer(Modifier.width(Dimens.gap))

        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
        ) {
            if (active.isEmpty()) {
                Text(
                    stringResource(Res.string.logs_filters_none),
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.secondaryText,
                )
            }
            active.forEachIndexed { index, filter ->
                if (index > 0) {
                    Text(
                        stringResource(Res.string.logs_and),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = Colors.secondaryText,
                    )
                }
                FilterPill(filter)
            }
            AddFilterButton(saved, selected, names, onToggleSaved, onEditSaved, onDeleteSaved, onNewFilter)
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

/**
 * the log tab's one search box. It **highlights and never filters** — a hit is only useful with the
 * lines around it — and steps through the hits: Enter and ⇧Enter here, ⌘G and ⇧⌘G anywhere.
 *
 * `tag:` and `app:` narrow what it looks at. Once something is typed, Save as filter is the way to
 * turn the search into a filter that does take lines away.
 *
 * @param hits how many lines match, or null while they're still being counted
 * @param current which of them the cursor is on, from 1, or null if it isn't on one yet
 */
@Composable
fun LogSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onFieldFocus: (Boolean) -> Unit,
    hits: Int?,
    current: Int?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSaveAsFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        modifier
            .height(Dimens.chipHeight + Dimens.tabGap)
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.divider, shape)
            .padding(start = Dimens.tabGap + Dimens.hairline * 2, end = Dimens.tabGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyntaxHelpButton(
            icon = Res.drawable.search,
            label = stringResource(Res.string.logs_search_help),
            title = stringResource(Res.string.logs_search_help_title),
            rows = SEARCH_HELP,
            note = stringResource(Res.string.logs_search_help_note),
        )
        Spacer(Modifier.width(Dimens.tabGap))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Colors.primaryText),
            cursorBrush = SolidColor(Colors.primaryText),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFieldFocus(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (event.type != KeyEventType.KeyDown || !enter) return@onPreviewKeyEvent false
                    if (event.isShiftPressed) onPrevious() else onNext()
                    true
                },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(Res.string.logs_search),
                            style = MaterialTheme.typography.bodySmall,
                            color = Colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        if (value.isNotBlank()) {
            if (hits != null) {
                Text(
                    when {
                        hits == 0 -> stringResource(Res.string.logs_search_none)
                        current != null -> stringResource(Res.string.logs_search_count, current, hits)
                        else -> hits.toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Colors.secondaryText,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = Dimens.liveDot),
                )
            }
            SearchArrow(Res.drawable.arrow_up, stringResource(Res.string.logs_search_prev), enabled = hits != 0, onClick = onPrevious)
            SearchArrow(Res.drawable.arrow_down, stringResource(Res.string.logs_search_next), enabled = hits != 0, onClick = onNext)
            SearchArrow(Res.drawable.close, stringResource(Res.string.logs_clear), enabled = true) { onValueChange("") }
            Spacer(Modifier.width(Dimens.tabGap))
            SaveAsFilterButton(onSaveAsFilter)
        }
    }
}

/**
 * the log tab's typed filter: a filter expression, in the syntax a saved filter uses, that **takes
 * lines away** — ANDed with the level and the pills, the way search never is.
 *
 * this tab's alone and not saved; Save as filter turns it into a pill. A word on its own is a bare
 * `*:*text*`, so it does the obvious thing without the syntax, and the funnel lists the rest.
 */
@Composable
fun LogFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    onSaveAsFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        modifier
            .height(Dimens.chipHeight + Dimens.tabGap)
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.divider, shape)
            .padding(start = Dimens.tabGap + Dimens.hairline * 2, end = Dimens.tabGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SyntaxHelpButton(
            icon = Res.drawable.filter,
            label = stringResource(Res.string.logs_filter_help),
            title = stringResource(Res.string.logs_filter_help_title),
            rows = FILTER_HELP,
            note = stringResource(Res.string.logs_filter_help_note),
        )
        Spacer(Modifier.width(Dimens.tabGap))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Colors.primaryText),
            cursorBrush = SolidColor(Colors.primaryText),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFieldFocus(it.isFocused) },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(Res.string.logs_filter_field),
                            style = MaterialTheme.typography.bodySmall,
                            color = Colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        if (value.isNotBlank()) {
            SearchArrow(Res.drawable.close, stringResource(Res.string.logs_clear), enabled = true) { onValueChange("") }
            Spacer(Modifier.width(Dimens.tabGap))
            SaveAsFilterButton(onSaveAsFilter)
        }
    }
}

/** the small outlined Save as filter at the end of a box that has something typed in it. */
@Composable
private fun SaveAsFilterButton(onClick: () -> Unit) {
    val small = RoundedCornerShape(Dimens.corner)
    Box(
        Modifier
            .height(Dimens.searchArrow + Dimens.hairline * 2)
            .clip(small)
            .background(Colors.toolbarBg)
            .border(Dimens.hairline, Colors.divider, small)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.gap),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.logs_save_as_filter),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.primaryText,
            maxLines = 1,
        )
    }
}

/**
 * a box's own icon at its start — the magnifier, the funnel — as a button: a panel of the syntax
 * that box takes, an example beside what it does, then [note]. A panel rather than a tooltip so it
 * stays up while the example is being typed in.
 */
@Composable
private fun SyntaxHelpButton(
    icon: DrawableResource,
    label: String,
    title: String,
    rows: List<Pair<StringResource, StringResource>>,
    note: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        CellTooltip(text = { label }) {
            Box(
                Modifier
                    .size(Dimens.searchArrow)
                    .clip(RoundedCornerShape(Dimens.corner))
                    .clickable { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(icon, size = Dimens.icon - Dimens.hairline * 2, tint = Colors.secondaryText, contentDescription = label)
            }
        }
        if (open) {
            val density = LocalDensity.current
            val position = remember(density) {
                PointerPositionProvider(IntOffset(0, with(density) { (Dimens.searchArrow + Dimens.navGap).roundToPx() }))
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SyntaxHelpPanel(title, rows, note)
            }
        }
    }
}

@Composable
private fun SyntaxHelpPanel(title: String, rows: List<Pair<StringResource, StringResource>>, note: String) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(
        Modifier
            .width(Dimens.syntaxHelpWidth)
            .shadow(Dimens.menuShadow, shape)
            .clip(shape)
            .background(Colors.menuBg)
            .border(Dimens.hairline, Colors.menuBorder, shape)
            .padding(horizontal = Dimens.rowPadding + Dimens.hairline * 2, vertical = Dimens.navGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.tabGap),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Colors.primaryText)
        rows.forEach { (syntax, meaning) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    stringResource(syntax),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = Colors.primaryText,
                    modifier = Modifier.width(Dimens.syntaxColumnWidth),
                )
                Text(stringResource(meaning), style = MaterialTheme.typography.labelMedium, color = Colors.secondaryText)
            }
        }
        HorizontalDivider(color = Colors.divider, modifier = Modifier.padding(vertical = Dimens.tabGap))
        Text(note, style = MaterialTheme.typography.labelMedium, color = Colors.secondaryText)
    }
}

// each box's ? panel: an example, then what it does
private val SEARCH_HELP = listOf(
    Res.string.logs_help_text to Res.string.logs_search_help_text,
    Res.string.logs_help_tag to Res.string.logs_search_help_tag,
    Res.string.logs_help_app to Res.string.logs_search_help_app,
)

private val FILTER_HELP = listOf(
    Res.string.logs_help_text to Res.string.logs_filter_help_text,
    Res.string.logs_help_tag to Res.string.logs_filter_help_is,
    Res.string.logs_help_starts to Res.string.logs_filter_help_starts,
    Res.string.logs_help_ends to Res.string.logs_filter_help_ends,
    Res.string.logs_help_contains to Res.string.logs_filter_help_contains,
    Res.string.logs_help_not to Res.string.logs_filter_help_not,
    Res.string.logs_help_level to Res.string.logs_filter_help_level,
    Res.string.logs_help_quoted to Res.string.logs_filter_help_quoted,
    Res.string.logs_help_and to Res.string.logs_filter_help_and,
    Res.string.logs_help_or to Res.string.logs_filter_help_or,
)

@Composable
private fun SearchArrow(icon: DrawableResource, label: String, enabled: Boolean, onClick: () -> Unit) {
    CellTooltip(text = { label }) {
        Box(
            Modifier
                .size(Dimens.searchArrow)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                icon,
                size = Dimens.pillClose + Dimens.hairline * 2,
                tint = Colors.secondaryText.copy(alpha = if (enabled) 1f else 0.4f),
                contentDescription = label,
            )
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
                filter.detail?.let { detail ->
                    Spacer(Modifier.width(Dimens.liveDot))
                    Text(detail, style = MaterialTheme.typography.labelMedium, color = Colors.secondaryText, maxLines = 1)
                }
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

/**
 * + Add filter: a panel of every saved filter as a tick box, with its rules in words under its name
 * and ✎ to edit it or 🗑 to delete it, then New filter and Done.
 *
 * a panel rather than a menu because it **stays open while ticking** — combining three filters is
 * three ticks, not three trips — and because a filter's rules are worth reading before turning it on.
 */
@Composable
private fun AddFilterButton(
    saved: List<LogFilter>,
    selected: Set<String>,
    names: Map<String, String>,
    onToggle: (LogFilter) -> Unit,
    onEdit: (LogFilter) -> Unit,
    onDelete: (LogFilter) -> Unit,
    onNew: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(Dimens.chipHeight)
                .clip(RoundedCornerShape(percent = 50))
                .dashedOutline(Colors.divider, null)
                .clickable { open = !open }
                .padding(horizontal = Dimens.navGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Res.drawable.add, size = Dimens.pillClose, tint = Colors.toolbarIcon)
            Spacer(Modifier.width(Dimens.tabGap))
            Text(stringResource(Res.string.logs_add_filter), style = MaterialTheme.typography.labelMedium, color = Colors.primaryText)
        }
        if (open) {
            val density = LocalDensity.current
            val position = remember(density) {
                PointerPositionProvider(IntOffset(0, with(density) { (Dimens.chipHeight + Dimens.tabGap).roundToPx() }))
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                SavedFiltersPanel(
                    saved = saved,
                    selected = selected,
                    names = names,
                    onToggle = onToggle,
                    onEdit = {
                        open = false
                        onEdit(it)
                    },
                    // closed too: the prompt is a window of its own, and the panel would lose focus to it
                    onDelete = {
                        open = false
                        onDelete(it)
                    },
                    onNew = {
                        open = false
                        onNew()
                    },
                    onDone = { open = false },
                )
            }
        }
    }
}

@Composable
private fun SavedFiltersPanel(
    saved: List<LogFilter>,
    selected: Set<String>,
    names: Map<String, String>,
    onToggle: (LogFilter) -> Unit,
    onEdit: (LogFilter) -> Unit,
    onDelete: (LogFilter) -> Unit,
    onNew: () -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(
        Modifier
            .width(Dimens.savedFiltersWidth)
            .shadow(Dimens.menuShadow, shape)
            .clip(shape)
            .background(Colors.menuBg)
            .border(Dimens.hairline, Colors.menuBorder, shape),
    ) {
        Column(
            Modifier.padding(horizontal = Dimens.rowPadding + Dimens.hairline * 2, vertical = Dimens.navGap),
            verticalArrangement = Arrangement.spacedBy(Dimens.hairline * 2),
        ) {
            Text(
                stringResource(Res.string.logs_saved_filters),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Colors.primaryText,
            )
            Text(
                stringResource(Res.string.logs_saved_filters_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Colors.secondaryText,
            )
        }
        HorizontalDivider(color = Colors.divider)
        Column(
            Modifier
                .heightIn(max = Dimens.savedFiltersMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Dimens.tabGap),
        ) {
            if (saved.isEmpty()) {
                Text(
                    stringResource(Res.string.logs_saved_filters_none),
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.secondaryText,
                    modifier = Modifier.padding(horizontal = Dimens.rowPadding + Dimens.hairline * 2, vertical = Dimens.gap),
                )
            }
            saved.forEach { filter ->
                SavedFilterRow(
                    filter,
                    on = filter.name in selected,
                    names = names,
                    onToggle = { onToggle(filter) },
                    onEdit = { onEdit(filter) },
                    onDelete = { onDelete(filter) },
                )
            }
        }
        HorizontalDivider(color = Colors.divider)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.navGap, vertical = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogButton(stringResource(Res.string.logs_new_filter), icon = Res.drawable.add, onClick = onNew)
            Spacer(Modifier.weight(1f))
            DialogButton(stringResource(Res.string.done), primary = true, onClick = onDone)
        }
    }
}

/** one saved filter: a tick, its name with its rules in words under it, ✎ and 🗑. */
@Composable
private fun SavedFilterRow(
    filter: LogFilter,
    on: Boolean,
    names: Map<String, String>,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = Dimens.rowPadding + Dimens.hairline * 2, end = Dimens.gap, top = Dimens.gap, bottom = Dimens.gap),
        verticalAlignment = Alignment.Top,
    ) {
        CheckMark(on, Modifier.padding(top = Dimens.hairline * 2))
        Spacer(Modifier.width(Dimens.navGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.hairline * 2)) {
            Text(
                filter.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                filter.describe(names),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowIconButton(Res.drawable.edit, stringResource(Res.string.logs_edit_filter), onEdit)
        RowIconButton(Res.drawable.trash, stringResource(Res.string.logs_delete_filter), onDelete)
    }
}

/** a saved filter's ✎ or 🗑: a small icon with its name as a tooltip. */
@Composable
private fun RowIconButton(icon: DrawableResource, label: String, onClick: () -> Unit) {
    CellTooltip(text = { label }) {
        Box(
            Modifier
                .size(Dimens.chipHeight)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, size = Dimens.pillClose + Dimens.tabGap, tint = Colors.secondaryText, contentDescription = label)
        }
    }
}

/**
 * a dashed hairline round the edge — something that isn't there yet, like + Add filter. [corner]
 * null is a pill, rounded by half the height.
 */
fun Modifier.dashedOutline(color: Color, corner: Dp?): Modifier = drawBehind {
    val stroke = Dimens.hairline.toPx()
    val radius = corner?.toPx() ?: (size.height / 2)
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * stroke, 3f * stroke))),
    )
}

/**
 * Level ≥ with the lowest level shown, as one button: its letter in a badge of the level's colour,
 * the way the rows are coloured; a click opens a menu of all five. One button rather than five so the
 * search and filter boxes have the row's width — the level is set once and left, they are typed in.
 */
@Composable
fun LevelFloorButton(floor: LogLevelFloor, onFloor: (LogLevelFloor) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.navCorner)
    val label = stringResource(floor.label())
    Box {
        CellTooltip(text = { label }) {
            Row(
                Modifier
                    .height(Dimens.controlHeight)
                    .clip(shape)
                    .background(Colors.listBg)
                    .border(Dimens.hairline, Colors.divider, shape)
                    .clickable { open = !open }
                    .padding(start = Dimens.gap + Dimens.hairline * 2, end = Dimens.tabGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.logs_level),
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.secondaryText,
                )
                Spacer(Modifier.width(Dimens.tabGap))
                LevelBadge(floor, on = true)
            }
        }
        AppPopupMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, Dimens.controlHeight)) {
            LogLevelFloor.entries.forEach { level ->
                Item(stringResource(level.label()), leading = { LevelBadge(level, on = level == floor) }) { onFloor(level) }
            }
        }
    }
}

/**
 * a level's letter in its colour — filled with that colour when [on]. Debug has no colour of its
 * own (its rows are plain text), so its badge is the chips'.
 */
@Composable
private fun LevelBadge(level: LogLevelFloor, on: Boolean) {
    val plain = Colors.primaryText
    val tone = logLevelColor(level.code.toString(), plain)
    val coloured = level != LogLevelFloor.DEBUG
    Box(
        Modifier
            .size(Dimens.levelButton)
            .clip(RoundedCornerShape(Dimens.checkboxCorner + Dimens.hairline))
            .background(if (on) (if (coloured) tone else Colors.chipOn) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            level.code.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                !on -> tone
                coloured -> Colors.onAccent
                else -> Colors.chipOnText
            },
        )
    }
}

/** what a level button means, for its tooltip and for naming the floor among the filters on. */
fun LogLevelFloor.label() = when (this) {
    LogLevelFloor.VERBOSE -> Res.string.logs_level_verbose
    LogLevelFloor.DEBUG -> Res.string.logs_level_debug
    LogLevelFloor.INFO -> Res.string.logs_level_info
    LogLevelFloor.WARN -> Res.string.logs_level_warn
    LogLevelFloor.ERROR -> Res.string.logs_level_error
}
