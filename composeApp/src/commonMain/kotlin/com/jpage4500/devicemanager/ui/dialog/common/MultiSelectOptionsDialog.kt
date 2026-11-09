package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** one row of a [MultiSelectOptionsDialog]. */
data class MultiSelectDialogOption<T>(
    val data: T,                        // handed back in the set when the row is checked
    val icon: DrawableResource? = null,
    val text: String? = null,
    val textRes: StringResource? = null,
    val value: String? = null,
    val valueRes: StringResource? = null,
    val valueColor: Color? = null,
)

/**
 * pick any number of a list, with checkboxes — the columns a table shows, the log levels a filter
 * keeps. Past ten options the dialog grows a filter field. ported from DashboardWeatherKMP.
 */
@Composable
fun <T> MultiSelectOptionsDialog(
    title: String? = null,
    titleRes: StringResource? = null,
    message: String? = null,
    options: List<MultiSelectDialogOption<T>>,
    selectedValues: Set<T>,
    // true = let a long label wrap onto more lines instead of truncating to one
    wrapLabels: Boolean = false,
    // false = let OK through with nothing checked, for a list where "none" is a real answer
    requireSelection: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Set<T>) -> Unit,
) {
    var currentSelection by remember { mutableStateOf(selectedValues) }
    var filter by remember { mutableStateOf("") }

    val optionsWithText = options.map { option ->
        val label = option.textRes?.let { stringResource(it) } ?: option.text.orEmpty()
        val value = option.valueRes?.let { stringResource(it) } ?: option.value
        Triple(option, label, value)
    }
    val filtered = if (filter.isEmpty()) optionsWithText else {
        optionsWithText.filter { it.second.contains(filter, ignoreCase = true) }
    }

    BaseDialog(
        title = title,
        titleRes = titleRes,
        okText = stringResource(Res.string.ok),
        cancelText = stringResource(Res.string.cancel),
        okEnabled = !requireSelection || currentSelection.isNotEmpty(),
        onConfirm = { onConfirm(currentSelection) },
        onDismiss = onDismiss,
    ) {
        message?.let {
            DialogMessage(it)
            Spacer(Modifier.height(Dimens.gap))
        }

        if (options.size >= 10) {
            FilterField(
                value = filter,
                onValueChange = { filter = it },
                hint = stringResource(Res.string.filter),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding, vertical = Dimens.gap),
            )
        }

        filtered.forEach { (option, label, value) ->
            MultiSelectOptionItem(
                option = option,
                label = label,
                value = value,
                isSelected = option.data in currentSelection,
                wrapLabels = wrapLabels,
                onClick = {
                    currentSelection = if (option.data in currentSelection) {
                        currentSelection - option.data
                    } else {
                        currentSelection + option.data
                    }
                },
            )
        }
    }
}

@Composable
private fun <T> MultiSelectOptionItem(
    option: MultiSelectDialogOption<T>,
    label: String,
    value: String?,
    isSelected: Boolean,
    wrapLabels: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Checkbox,
                onClick = onClick,
                onLongClick = { ToastManager.showToast(listOfNotNull(label, value).joinToString(" - ")) },
            )
            .padding(horizontal = Dimens.settingPadding, vertical = Dimens.settingRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = null)  // the row handles the click
        Spacer(modifier = Modifier.width(Dimens.settingItemGap))

        option.icon?.let {
            AppIcon(it, size = Dimens.settingIcon, tint = Colors.accent)
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = Dimens.settingLabelSize,
            maxLines = if (wrapLabels) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = wrapLabels,
            color = if (isSelected) Colors.accent else Colors.primaryText,
            modifier = if (wrapLabels) Modifier.weight(1f) else Modifier,
        )

        if (value != null) {
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = Dimens.settingValueSize,
                color = option.valueColor ?: Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
