package com.jpage4500.devicemanager.ui.setting

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

/**
 * a settings row with a slider under it: the label and the current value on top, the track below.
 *
 * ported from DashboardWeatherKMP.
 */
@Composable
fun SettingSliderItem(
    icon: DrawableResource,
    text: String? = null,
    textRes: StringResource? = null,
    value: Int,
    valueRange: IntRange,
    valueDesc: String? = null,
    defaultValue: Int? = null,
    steps: Int = valueRange.last - valueRange.first - 1,    // 0 for a smooth slider over a wide range
    primaryColor: Color? = null,
    showDivider: Boolean = true,
    // when non-null, tapping the row opens a type-a-value dialog under this title (e.g. "Value in
    // seconds"); null disables manual entry. only enable it where the slider value is the real
    // quantity being typed — NOT where it's an index into a preset list, since manual entry is
    // deliberately NOT clamped to valueRange
    manualEntryLabel: String? = null,
    onValueChange: ((Int) -> String?)? = null,
    onValueChangeFinished: ((Int) -> Unit)? = null,
) {
    var currentValue by remember { mutableIntStateOf(value) }
    var valueText by remember { mutableStateOf(valueDesc ?: value.toString()) }
    var showInputDialog by remember { mutableStateOf(false) }

    // sync external updates
    LaunchedEffect(value) { currentValue = value }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingItem(
            icon = icon,
            labelText = text,
            labelRes = textRes,
            valueText = valueText,
            showDivider = false,    // the divider goes below the slider, not above it
            onClick = { if (manualEntryLabel != null) showInputDialog = true },
        )

        // matches SettingItem's own padding, so the track lines up with the label above it
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.settingPadding)) {
            CustomSlider(
                startValue = valueRange.first,
                endValue = valueRange.last,
                value = currentValue,
                steps = steps,
                defaultValue = defaultValue,
                primaryColor = primaryColor,
                onValueChange = { sliderValue ->
                    if (currentValue != sliderValue) {
                        currentValue = sliderValue
                        valueText = onValueChange?.invoke(sliderValue) ?: sliderValue.toString()
                    }
                },
                onValueChangeFinished = { onValueChangeFinished?.invoke(it) },
            )
            if (showDivider) HorizontalDivider(color = Colors.divider)
        }
    }

    // manual numeric entry (opt-in). no validator, so the value is NOT clamped to valueRange —
    // typing an exact value past the slider's max is the point
    if (showInputDialog && manualEntryLabel != null) {
        TextInputDialog(
            title = manualEntryLabel,
            initialValue = currentValue.toString(),
            inputType = InputType.NUMBER,
            onDismiss = { showInputDialog = false },
            onConfirm = { input ->
                input.toIntOrNull()?.let { newValue ->
                    currentValue = newValue
                    valueText = onValueChange?.invoke(newValue) ?: newValue.toString()
                    onValueChangeFinished?.invoke(newValue)
                }
                showInputDialog = false
            },
        )
    }
}
