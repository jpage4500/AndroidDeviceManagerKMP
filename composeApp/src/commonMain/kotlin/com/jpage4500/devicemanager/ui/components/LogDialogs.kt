package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
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
 * asked for the live line count as the rules change. [onDelete], when given, puts Delete filter in
 * the corner of an existing filter. [names] is pid -> process, for the summary.
 */
@Composable
fun FilterDialog(
    filter: LogFilter?,
    onDismiss: () -> Unit,
    onSave: (original: LogFilter, saved: LogFilter) -> Unit,
    count: ((LogFilter) -> FilterCount)? = null,
    onDelete: ((LogFilter) -> Unit)? = null,
    names: Map<String, String> = emptyMap(),
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
            counted = if (expression.isEmpty()) null else count(LogFilter("", expression))
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
        extraButton = if (adding || onDelete == null) null else {
            {
                Text(
                    stringResource(Res.string.logs_filter_delete),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dimens.corner))
                        .plainClickable {
                            onDelete(filter)
                            onDismiss()
                        }
                        .padding(horizontal = Dimens.gap, vertical = Dimens.tabGap),
                )
            }
        },
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
        LaunchedEffect(terms.size, expression.isEmpty(), counted != null) { fit() }
        Column(Modifier.heightIn(max = Dimens.filterRulesMaxHeight).verticalScroll(rememberScrollState())) {
            terms.forEachIndexed { index, term ->
                TermRow(
                    join = if (index == 0) where else join,
                    term = term,
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

            if (expression.isNotEmpty()) {
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
