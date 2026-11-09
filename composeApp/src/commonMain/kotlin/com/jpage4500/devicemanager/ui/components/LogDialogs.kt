package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

// the two dropdowns are fixed-width so every row's Value field starts at the same place, and so is
// the "where / and / or" before them
private val JOIN_WIDTH = 44.dp
private val FIELD_WIDTH = 108.dp
private val OPERATOR_WIDTH = 168.dp

// typing into a value re-counts 50,000 lines; wait for a pause rather than doing it per key
private const val COUNT_DELAY_MS = 150L

/**
 * the editor's live count: lines this filter passes on its own, of all held on [device] — and
 * [combined], with the tab's [others] on as well, when there are any.
 */
data class FilterCount(
    val alone: Int,
    val combined: Int?,
    val total: Int,
    val device: String,
    /** the other filters on in the tab, by the names their pills show. */
    val others: List<String> = emptyList(),
)

/**
 * add or edit a saved log filter.
 *
 * Built from dropdowns rather than a text box, like the Swing app's: the expression syntax is
 * documented but nobody should have to remember whether the star goes before or after. Each row is a
 * [LogTermSpec]; on save they are joined with `&&` (All) or `||` (Any) into the same expression
 * string the saved-filter file already speaks. Under the rules, the filter in words and its live
 * count — on its own, and with whatever else is on in the tab.
 *
 * `onSave` is handed the original so a rename replaces the right entry. [count], when given, is
 * asked for the live line count as the rules change. [names] is pid -> process, for the summary;
 * [apps] are the processes the log has lines from, offered by an App rule's ▾.
 */
@Composable
fun FilterDialog(
    filter: LogFilter?,
    onDismiss: () -> Unit,
    onSave: (original: LogFilter, saved: LogFilter) -> Unit,
    count: ((LogFilter) -> FilterCount)? = null,
    names: Map<String, String> = emptyMap(),
    apps: List<String> = emptyList(),
) {
    if (filter == null) return
    val adding = filter.name.isBlank()
    val suggested = stringResource(Res.string.logs_filter_new)

    // opens with the suggestion selected, so typing replaces it
    var name by remember(filter) {
        val initial = filter.name.ifBlank { LogFilterStore.uniqueName(suggested) }
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    var terms by remember(filter) {
        mutableStateOf(LogTermSpec.parse(filter.expression).ifEmpty { listOf(LogTermSpec()) })
    }
    var any by remember(filter) { mutableStateOf(filter.matchesAny) }
    val focus = remember { FocusRequester() }

    val joiner = if (any) LogFilter.OR else LogFilter.AND
    val expression = terms.filter { it.value.isNotBlank() }.joinToString(" $joiner ") {
        it.asExpression()
    }
    var counted by remember(filter) { mutableStateOf<FilterCount?>(null) }
    if (count != null) {
        LaunchedEffect(expression) {
            delay(COUNT_DELAY_MS)
            // an emptied filter keeps its last count, so typing a value again doesn't wait on one
            if (expression.isNotEmpty()) counted = count(LogFilter("", expression))
        }
    }
    val valid = name.text.isNotBlank() && expression.isNotEmpty()

    fun save() {
        if (!valid) return
        onSave(filter, LogFilter(name.text.trim(), expression, enabled = true))
        onDismiss()
    }

    AppDialog(
        title = stringResource(if (adding) Res.string.logs_filter_add else Res.string.logs_filter_edit),
        onDismiss = onDismiss,
        // a new filter is saved to be used, so saving it turns it on in this tab
        confirmLabel = stringResource(if (adding) Res.string.logs_filter_save_on else Res.string.save),
        confirmEnabled = valid,
        onConfirm = ::save,
        modifier = Modifier.width(640.dp),
    ) {
        FieldLabel(stringResource(Res.string.logs_filter_name))
        AppTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            focusRequester = focus,
            onSubmit = ::save,
        )
        Spacer(Modifier.height(Dimens.gapLarge))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Dimens.gap)) {
            Text(stringResource(Res.string.logs_filter_match), style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
            Spacer(Modifier.width(Dimens.gap))
            SegmentedToggle(
                options = listOf(false, true),
                selected = any,
                label = { stringResource(if (it) Res.string.logs_filter_any else Res.string.logs_filter_all) },
                onSelect = { any = it },
            )
            Spacer(Modifier.width(Dimens.gap))
            Text(stringResource(Res.string.logs_filter_match_rules), style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText)
        }
        val where = stringResource(Res.string.logs_filter_where)
        val join = stringResource(if (any) Res.string.logs_filter_or else Res.string.logs_filter_and)
        // the window grows with each rule until the rules hit their cap, and scrolls from there
        val fit = LocalDialogFit.current
        // the summary waits for its count, so it arrives at its full height in one step
        val summary = expression.isNotEmpty() && (count == null || counted != null)
        LaunchedEffect(terms.size, summary, counted?.combined != null) { fit() }
        Column(Modifier.heightIn(max = Dimens.filterRulesMaxHeight).verticalScroll(rememberScrollState())) {
            terms.forEachIndexed { index, term ->
                TermRow(
                    join = if (index == 0) where else join,
                    term = term,
                    apps = apps,
                    onChange = { changed -> terms = terms.toMutableList().also { it[index] = changed } },
                    // the last rule stays: a filter of no rules passes everything, which is no filter
                    onRemove = if (terms.size > 1) {
                        { terms = terms.filterIndexed { at, _ -> at != index } }
                    } else {
                        null
                    },
                    onSubmit = ::save,
                )
                Spacer(Modifier.height(Dimens.gap / 2))
            }
            AddRuleButton(Modifier.padding(start = JOIN_WIDTH + Dimens.gap, top = Dimens.tabGap)) {
                terms = terms + LogTermSpec()
            }

            if (summary) {
                Spacer(Modifier.height(Dimens.gapLarge))
                Summary(LogFilter("", expression).describe(names), counted)
            }
        }
        FocusOnShow(focus, filter)
    }
}

/** one rule: what to look at, how to compare it, and what to compare it against. */
@Composable
private fun TermRow(
    join: String,
    term: LogTermSpec,
    apps: List<String>,
    onChange: (LogTermSpec) -> Unit,
    onRemove: (() -> Unit)?,
    onSubmit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            join,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.secondaryText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(JOIN_WIDTH),
        )
        Spacer(Modifier.width(Dimens.gap))
        AppDropdown(
            value = term.field,
            // a filter carried over may use a column the editor no longer offers; it keeps it
            options = LogTerm.Field.OFFERED + listOf(term.field).filter { it !in LogTerm.Field.OFFERED },
            label = { it.label },
            onSelect = { onChange(term.copy(field = it)) },
            modifier = Modifier.width(FIELD_WIDTH),
        )
        Spacer(Modifier.width(Dimens.gap))
        AppDropdown(
            value = term.operator,
            options = LogOperator.OFFERED + listOf(term.operator).filter { it !in LogOperator.OFFERED },
            label = { it.label },
            onSelect = { onChange(term.copy(operator = it)) },
            modifier = Modifier.width(OPERATOR_WIDTH),
        )
        Spacer(Modifier.width(Dimens.gap))
        AppTextField(
            value = term.value,
            onValueChange = { onChange(term.copy(value = it)) },
            hint = stringResource(Res.string.logs_filter_value),
            modifier = Modifier.weight(1f),
            onSubmit = onSubmit,
        )
        if (term.field == LogTerm.Field.APP) {
            Spacer(Modifier.width(Dimens.tabGap))
            AppPicker(apps) { onChange(term.copy(value = it)) }
        }
        Spacer(Modifier.width(Dimens.gap))
        AppIcon(
            res = Res.drawable.close,
            size = 13.dp,
            tint = Colors.secondaryText.copy(alpha = if (onRemove != null) 1f else 0.3f),
            contentDescription = stringResource(Res.string.logs_filter_remove_term),
            modifier = Modifier.plainClickable(enabled = onRemove != null) { onRemove?.invoke() }.padding(3.dp),
        )
    }
}

/**
 * ▾ beside an App rule's value: the apps this log has lines from, so a rule can be picked rather
 * than spelled. A search box over the list narrows it as you type; Enter takes the first match.
 *
 * Escape closes the list and nothing else. The popup is a layer of the dialog's window rather than a
 * child of the form, so a key it leaves alone goes on to the window — which closes the whole dialog.
 */
@Composable
private fun AppPicker(apps: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // the list opens flush under the button, so its own measured height is the offset
    var height by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val label = stringResource(Res.string.logs_filter_pick_app)
    Box(Modifier.onSizeChanged { height = with(density) { it.height.toDp() } }) {
        CellTooltip(text = { label }) {
            Box(
                Modifier
                    .size(Dimens.chipHeight)
                    .clip(RoundedCornerShape(Dimens.corner))
                    .plainClickable { open = true },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Res.drawable.arrow_down, size = 9.dp, tint = Colors.secondaryText, contentDescription = label)
            }
        }
        if (open) {
            val position = remember(height, density) {
                PointerPositionProvider(IntOffset(0, with(density) { height.roundToPx() }))
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                AppPickerList(apps, onDismiss = { open = false }, onPick = onPick)
            }
        }
    }
}

@Composable
private fun AppPickerList(apps: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val matching = remember(apps, search) { apps.filter { it.containsIgnoreCase(search.trim()) } }
    val focus = remember { FocusRequester() }
    val scope = remember(onDismiss) { AppMenuScope(onDismiss) }
    // a popup is drawn inside its window's layer, and a dialog's window is only as tall as its form
    val container = LocalWindowInfo.current.containerSize.height
    val windowHeight = with(LocalDensity.current) { container.toDp() }
    val listMax = if (container <= 0) Dimens.appPickerMaxHeight else {
        minOf(Dimens.appPickerMaxHeight, windowHeight - Dimens.chipHeight * 3).coerceAtLeast(Dimens.menuItemHeight * 4)
    }
    val shape = RoundedCornerShape(Dimens.menuCorner)
    Column(
        Modifier
            .width(Dimens.appPickerWidth)
            .shadow(Dimens.menuShadow, shape)
            .clip(shape)
            .background(Colors.menuBg)
            .border(Dp.Hairline, Colors.menuBorder, shape)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onDismiss(); true }
                    Key.Enter, Key.NumPadEnter -> {
                        matching.firstOrNull()?.let { onDismiss(); onPick(it) }
                        true
                    }
                    else -> false
                }
            }
            .padding(vertical = Dimens.menuVerticalPadding),
    ) {
        FilterField(
            value = search,
            onValueChange = { search = it },
            hint = stringResource(Res.string.logs_filter_search_apps),
            focusRequester = focus,
            // FilterField leaves its chrome to the caller: here, a field's
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.navGap, vertical = Dimens.tabGap)
                .clip(RoundedCornerShape(5.dp))
                .background(Colors.listBg)
                .border(Dp.Hairline, Colors.divider, RoundedCornerShape(5.dp))
                .padding(horizontal = Dimens.gap, vertical = 6.dp),
        )
        HorizontalDivider(color = Colors.divider, modifier = Modifier.padding(vertical = Dimens.tabGap))
        Column(Modifier.heightIn(max = listMax).verticalScroll(rememberScrollState())) {
            if (matching.isEmpty()) {
                val none = if (apps.isEmpty()) Res.string.logs_filter_no_apps else Res.string.logs_filter_no_matching_apps
                scope.Item(stringResource(none), enabled = false) {}
            }
            matching.forEach { app ->
                scope.Item(app) { onPick(app) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** + Add rule, dashed like + Add filter — something that isn't there yet. */
@Composable
private fun AddRuleButton(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(Dimens.chipHeight)
            .clip(RoundedCornerShape(Dimens.navCorner))
            .dashedOutline(Colors.divider, Dimens.navCorner)
            .plainClickable(onClick = onClick)
            .padding(horizontal = Dimens.navGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Res.drawable.add, size = Dimens.pillClose, tint = Colors.toolbarIcon)
        Spacer(Modifier.width(Dimens.tabGap))
        Text(stringResource(Res.string.logs_filter_add_term), style = MaterialTheme.typography.labelMedium, color = Colors.primaryText)
    }
}

/** the filter in words, and how many lines it lets through — alone, then with what else is on. */
@Composable
private fun Summary(words: String, counted: FilterCount?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.navCorner))
            .background(Colors.toolbarBg)
            .padding(horizontal = Dimens.rowPadding, vertical = Dimens.navGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.tabGap),
    ) {
        Text(
            words,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = Colors.primaryText,
        )
        counted?.let { c ->
            Text(
                stringResource(Res.string.logs_filter_count, c.alone, c.total, c.device),
                style = MaterialTheme.typography.labelMedium,
                color = Colors.secondaryText,
            )
            if (c.combined != null && c.others.isNotEmpty()) {
                Text(
                    stringResource(Res.string.logs_filter_count_combined, c.others.joinToString(" + "), c.combined),
                    style = MaterialTheme.typography.labelMedium,
                    color = Colors.secondaryText,
                )
            }
        }
    }
}

/** which lines Save writes. */
enum class LogSaveScope { SELECTED, VISIBLE, ALL }

/**
 * what Save asks first: the selected lines, the ones the filters show, or everything held.
 *
 * Selected only appears for more than one line — a single selected line is usually just where the
 * cursor is, not a choice. Starts on Selected when it's there, Visible otherwise.
 */
@Composable
fun SaveLogDialog(
    open: Boolean,
    selected: Int,
    visible: Int,
    total: Int,
    onDismiss: () -> Unit,
    onSave: (LogSaveScope) -> Unit,
) {
    if (!open) return
    val offerSelected = selected > 1
    var scope by remember { mutableStateOf(if (offerSelected) LogSaveScope.SELECTED else LogSaveScope.VISIBLE) }
    fun save() {
        onDismiss()
        onSave(scope)
    }
    AppDialog(
        title = stringResource(Res.string.logs_save_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = ::save,
    ) {
        if (offerSelected) {
            RadioRow(stringResource(Res.string.logs_save_selected, selected), scope == LogSaveScope.SELECTED) {
                scope = LogSaveScope.SELECTED
            }
        }
        RadioRow(stringResource(Res.string.logs_save_visible, visible), scope == LogSaveScope.VISIBLE) {
            scope = LogSaveScope.VISIBLE
        }
        RadioRow(stringResource(Res.string.logs_save_all, total), scope == LogSaveScope.ALL) {
            scope = LogSaveScope.ALL
        }
    }
}

/** a radio button and its label, at the size [CheckMark] draws a tick box. */
@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.corner))
            .plainClickable(onClick = onClick)
            .padding(horizontal = Dimens.gap, vertical = Dimens.tabGap + Dimens.hairline * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (selected) Colors.accent else Colors.listBg)
                .border(Dp.Hairline, if (selected) Colors.accent else Colors.divider, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(Colors.rowSelectedText))
        }
        Spacer(Modifier.width(Dimens.gap))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
    }
}

/** a finished save: where it went and how many lines, or that it failed. */
data class SavedLog(val path: String, val lines: Int, val ok: Boolean)

/**
 * after Save: where the log went, with Open and Show in Finder (Explorer on Windows) beside Close.
 * A failed save says so, with only Close.
 */
@Composable
fun LogSavedDialog(saved: SavedLog?, onDismiss: () -> Unit) {
    if (saved == null) return
    val revealLabel = stringResource(
        when (localDesktopOS) {
            DesktopOS.MAC -> Res.string.logs_saved_finder
            DesktopOS.WINDOWS -> Res.string.logs_saved_explorer
            else -> Res.string.logs_saved_folder
        }
    )
    AppDialog(
        title = stringResource(Res.string.logs_saved_title),
        onDismiss = onDismiss,
        cancelLabel = stringResource(Res.string.close),
        confirmLabel = if (saved.ok) stringResource(Res.string.logs_saved_open) else null,
        onConfirm = if (saved.ok) {
            {
                openLocalFile(saved.path)
                onDismiss()
            }
        } else {
            null
        },
        extraButton = if (!saved.ok) null else {
            {
                DialogButton(revealLabel) {
                    revealLocalFile(saved.path)
                    onDismiss()
                }
            }
        },
        modifier = Modifier.widthIn(min = 460.dp),
    ) {
        if (saved.ok) {
            Text(
                stringResource(Res.string.logs_saved_msg, saved.lines),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.primaryText,
            )
            Spacer(Modifier.height(Dimens.tabGap))
            SelectionContainer {
                Text(
                    saved.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Colors.secondaryText,
                )
            }
        } else {
            Text(
                stringResource(Res.string.logs_saved_failed, saved.path),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.error,
            )
        }
    }
}
