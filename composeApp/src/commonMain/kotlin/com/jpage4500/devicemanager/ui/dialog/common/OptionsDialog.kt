package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** one row of an [OptionsDialog]. */
data class DialogOption<T>(
    val data: T,
    val icon: DrawableResource? = null,
    val label: String? = null,
    val textRes: StringResource? = null,
    val value: String? = null,          // the note after the label — what picking this one costs
    val valueRes: StringResource? = null,
    val valueColor: Color? = null,
)

/**
 * pick one of a list, with radio buttons.
 *
 * Nothing is committed until OK, so a mis-tap costs nothing. Past ten options the dialog grows a
 * filter field. ported from DashboardWeatherKMP.
 */
@Composable
fun <T> OptionsDialog(
    title: String,
    options: List<DialogOption<T>>,
    selectedValue: T? = null,
    message: String? = null,
    // the confirm button's label, given what's selected right now. a choice that carries on into
    // another dialog — picking a transport that then needs an address — says NEXT rather than OK
    okTextFor: ((T?) -> String)? = null,
    onDismiss: () -> Unit,
    onConfirm: (T) -> Unit,
    // long-pressing an option. unset, the option's label and value are toasted — the same fallback
    // SettingItem uses, so a value the row ellipsized is always readable
    onLongClick: ((T) -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    var currentSelection by remember { mutableStateOf(selectedValue) }
    var filter by remember { mutableStateOf("") }

    // resolved up front so the filter can match what's actually on screen
    val optionsWithText = options.map { option ->
        option to (option.textRes?.let { stringResource(it) } ?: option.label.orEmpty())
    }
    val filtered = if (filter.isEmpty()) optionsWithText else {
        optionsWithText.filter { it.second.contains(filter, ignoreCase = true) }
    }

    BaseDialog(
        title = title,
        okText = okTextFor?.invoke(currentSelection) ?: stringResource(Res.string.ok),
        cancelText = stringResource(Res.string.cancel),
        okEnabled = currentSelection != null,
        onConfirm = { currentSelection?.let(onConfirm) },
        onDismiss = onDismiss,
    ) {
        message?.let {
            DialogMessage(it)
            Spacer(Modifier.height(Dimens.gap))
        }

        if (options.size >= FILTER_THRESHOLD) {
            FilterField(
                value = filter,
                onValueChange = { filter = it },
                hint = stringResource(Res.string.filter),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding, vertical = Dimens.gap),
            )
        }

        filtered.forEach { (option, displayText) ->
            OptionItem(
                option = option,
                displayText = displayText,
                isSelected = currentSelection == option.data,
                onClick = { currentSelection = option.data },
                onLongClick = onLongClick,
            )
        }

        content?.invoke()
    }
}

// below this a filter field is more chrome than the list it filters
private const val FILTER_THRESHOLD = 10

@Composable
private fun <T> OptionItem(
    option: DialogOption<T>,
    displayText: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: ((T) -> Unit)?,
) {
    // resolved here too, so the fallback toast can name the value
    val valueText = option.valueRes?.let { stringResource(it) } ?: option.value

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isSelected }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (onLongClick != null) onLongClick(option.data)
                    else ToastManager.showToast(listOfNotNull(displayText, valueText).joinToString(" - "))
                },
                role = Role.RadioButton,
            )
            .padding(horizontal = Dimens.settingPadding, vertical = Dimens.settingRowPadding + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)    // the row handles the click
        Spacer(modifier = Modifier.width(Dimens.settingItemGap))

        option.icon?.let {
            AppIcon(it, size = Dimens.settingIcon, tint = Colors.accent)
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))
        }

        // natural width, so the label always fits and the value is what truncates
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = Dimens.settingLabelSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            color = if (isSelected) Colors.accent else Colors.primaryText,
        )

        if (valueText != null) {
            Spacer(modifier = Modifier.width(Dimens.gap))
            Text(
                text = valueText,
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
