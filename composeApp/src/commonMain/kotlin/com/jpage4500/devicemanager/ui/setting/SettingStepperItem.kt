package com.jpage4500.devicemanager.ui.setting

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource

// the +/- buttons and the number between them. fixed rather than wrapped, so the value doesn't
// shift the buttons sideways as it goes from 9 to 10
private val STEP_BUTTON = 32.dp
private val STEP_VALUE_WIDTH = 42.dp

/**
 * a settings row whose value is a small integer, changed with +/- buttons.
 *
 * For a count small enough to reach by tapping — how many mirror windows may poll at once — where a
 * slider would be imprecise and a text field would be a keyboard. ported from DashboardWeatherKMP,
 * with the icon buttons redrawn as glyphs since this app carries no +/- artwork.
 */
@Composable
fun SettingStepperItem(
    icon: DrawableResource,
    iconColor: Color? = null,
    text: String,
    value: Int,
    valueText: String = value.toString(),   // what's displayed, when the raw number isn't it
    valueColor: Color? = null,
    minValue: Int = 1,
    maxValue: Int = 10,
    defaultValue: Int? = null,              // bolded when [value] is still it
    showDivider: Boolean = true,
    onValueChange: (Int) -> Unit,
) {
    val isDefault = defaultValue != null && value == defaultValue
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { },
                    onLongClick = { ToastManager.showToast("$text - $valueText") },
                )
                .padding(horizontal = Dimens.settingPadding, vertical = Dimens.settingRowPadding),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, size = Dimens.settingIcon, tint = iconColor ?: Colors.accent)
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = Dimens.settingLabelSize,
                color = Colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                softWrap = false,
            )
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−", enabled = value > minValue) { onValueChange(value - 1) }
                Box(modifier = Modifier.width(STEP_VALUE_WIDTH), contentAlignment = Alignment.Center) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = Dimens.settingValueSize,
                        color = valueColor ?: Colors.settingValue,
                        fontWeight = if (isDefault) FontWeight.Bold else null,
                    )
                }
                StepButton("+", enabled = value < maxValue) { onValueChange(value + 1) }
            }
        }

        if (showDivider) HorizontalDivider(color = Colors.divider)
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(STEP_BUTTON)
            .clip(CircleShape)
            .background(if (enabled) Colors.accent else Colors.offline)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
