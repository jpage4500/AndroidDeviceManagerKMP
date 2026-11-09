package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.runtime.*
import com.jpage4500.devicemanager.ui.dialog.*
import com.jpage4500.devicemanager.ui.setting.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * one small number, set with +/- buttons. write-through: every tap reports.
 *
 * ported from DashboardWeatherKMP.
 */
@Composable
fun StepperDialog(
    title: String,
    icon: DrawableResource,
    initialValue: Int,
    minValue: Int = 1,
    maxValue: Int = 10,
    defaultValue: Int? = null,
    onDismiss: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    var currentValue by remember { mutableStateOf(initialValue) }

    BaseDialog(title = title, icon = icon, onDismiss = onDismiss) {
        SettingStepperItem(
            icon = icon,
            text = title,
            value = currentValue,
            minValue = minValue,
            maxValue = maxValue,
            defaultValue = defaultValue,
            showDivider = false,
            onValueChange = { value ->
                currentValue = value
                onValueChange(value)
            },
        )
    }
}
