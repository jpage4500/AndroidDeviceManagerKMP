package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

// the two dropdowns are fixed-width so every row's Value field starts at the same place
private val FIELD_WIDTH = 108.dp
private val OPERATOR_WIDTH = 168.dp

/**
 * add or edit a saved log filter.
 *
 * Built from dropdowns rather than a text box, like the Swing app's: the expression syntax is
 * documented but nobody should have to remember whether the star goes before or after. Each row is a
 * [LogTermSpec]; on save they are joined with `&&` into the same expression string the sidebar,
 * the status bar and the saved-filter file all already speak.
 *
 * `onSave` is handed the original so a rename replaces the right entry.
 */
@Composable
fun FilterDialog(
    filter: LogFilter?,
    onDismiss: () -> Unit,
    onSave: (original: LogFilter, saved: LogFilter) -> Unit,
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
    val focus = remember { FocusRequester() }

    val expression = terms.filter { it.value.isNotBlank() }.joinToString(" ${LogFilter.AND} ") {
        it.asExpression()
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
        confirmLabel = stringResource(Res.string.save),
        confirmEnabled = valid,
        onConfirm = ::save,
        modifier = Modifier.width(620.dp),
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

        FieldLabel(stringResource(Res.string.logs_filter_expression))
        terms.forEachIndexed { index, term ->
            TermRow(
                term = term,
                onChange = { changed -> terms = terms.toMutableList().also { it[index] = changed } },
                onRemove = { terms = terms.filterIndexed { at, _ -> at != index } },
                onSubmit = ::save,
            )
            Spacer(Modifier.height(Dimens.gap / 2))
        }
        AppIcon(
            res = Res.drawable.add,
            size = 15.dp,
            tint = Colors.accent,
            contentDescription = stringResource(Res.string.logs_filter_add_term),
            // padding inside the clickable, so the hit area is bigger than the glyph
            modifier = Modifier.plainClickable { terms = terms + LogTermSpec() }.padding(3.dp),
        )
        FocusOnShow(focus, filter)
    }
}

/** one term: what to look at, how to compare it, and what to compare it against. */
@Composable
private fun TermRow(
    term: LogTermSpec,
    onChange: (LogTermSpec) -> Unit,
    onRemove: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppDropdown(
            value = term.field,
            options = LogTerm.Field.entries,
            label = { it.label },
            onSelect = { onChange(term.copy(field = it)) },
            modifier = Modifier.width(FIELD_WIDTH),
        )
        Spacer(Modifier.width(Dimens.gap))
        AppDropdown(
            value = term.operator,
            options = LogOperator.entries,
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
            res = Res.drawable.trash,
            size = 13.dp,
            tint = Colors.secondaryText,
            contentDescription = stringResource(Res.string.logs_filter_remove_term),
            modifier = Modifier.plainClickable(onClick = onRemove).padding(3.dp),
        )
    }
}
