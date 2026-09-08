package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.setting.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * one number, set with a slider.
 *
 * Write-through by default — [onValueChangeFinished] fires as the thumb is let go and there are no
 * buttons; pass [okText] for a flow that applies on OK instead. ported from DashboardWeatherKMP.
 */
@Composable
fun SliderDialog(
    title: String,
    icon: DrawableResource,
    initialValue: Int,
    valueRange: IntRange,
    valueDesc: String? = null,
    steps: Int = valueRange.last - valueRange.first - 1,    // 0 for a smooth slider over a wide range
    defaultValue: Int? = null,
    desc: String? = null,
    // when non-null, tapping the row opens type-a-value entry under this title; null disables it.
    // only where the value is the real quantity typed, not an index into a list (see
    // [SettingSliderItem])
    manualEntryLabel: String? = null,
    okText: String? = null,
    cancelText: String? = null,
    onDismiss: () -> Unit,
    // returns the text to show for a value, and the description to show under the slider
    onValueChange: ((Int) -> Pair<String?, String?>?)? = null,
    onValueChangeFinished: ((Int) -> Unit)? = null,
    onConfirm: ((Int) -> Unit)? = null,
) {
    var currentValue by remember { mutableStateOf(initialValue) }
    var currentDesc by remember { mutableStateOf(desc) }

    BaseDialog(
        title = title,
        icon = icon,
        okText = okText,
        cancelText = cancelText,
        onDismiss = onDismiss,
        onConfirm = { onConfirm?.invoke(currentValue) },
    ) {
        SettingSliderItem(
            icon = icon,
            text = title,
            value = currentValue,
            valueDesc = valueDesc,
            valueRange = valueRange,
            steps = steps,
            defaultValue = defaultValue,
            manualEntryLabel = manualEntryLabel,
            showDivider = false,
            onValueChange = { newValue ->
                currentValue = newValue
                val result = onValueChange?.invoke(newValue)
                currentDesc = result?.second
                result?.first
            },
            onValueChangeFinished = onValueChangeFinished,
        )

        currentDesc?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding),
                style = MaterialTheme.typography.bodyMedium,
                color = Colors.secondaryText,
            )
        }
    }
}
