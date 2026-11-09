package com.jpage4500.devicemanager.ui.setting

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * one row of a settings screen: an icon, a label, and what the setting is currently set to.
 *
 * The value is the part anyone actually scans, which is why it is coloured — see
 * [Colors.settingValue]. A long press toasts label and value together, so a value the row had to
 * ellipsize is always readable. ported from DashboardWeatherKMP.
 */
@Composable
fun SettingItem(
    icon: DrawableResource,                         // left-aligned icon
    iconColor: Color? = null,                       // null = the accent colour
    labelText: String? = null,                      // label (string version)
    labelRes: StringResource? = null,               // label (resource version)
    labelColor: Color? = null,                      // null = the theme's primary text
    valueText: String? = null,                      // value (string version)
    valueRes: StringResource? = null,               // value (resource version)
    valueColor: Color? = null,                      // null = Colors.settingValue
    valueIcon: DrawableResource? = null,            // right-aligned icon, only when there's no value
    valueIconColor: Color? = null,
    valueContent: (@Composable () -> Unit)? = null, // custom trailing content; wins over valueIcon
    // when too narrow to fit both: false = keep the label whole, true = keep the value whole
    prioritizeValue: Boolean = false,
    showDivider: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val label = labelRes?.let { stringResource(it) } ?: labelText.orEmpty()
    val value = valueRes?.let { stringResource(it) } ?: valueText
    val tint = iconColor ?: Colors.accent
    val onValueColor = valueColor ?: Colors.settingValue
    val alpha = if (enabled) 1f else 0.38f

    // column so the divider sits below the row rather than beside it
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick ?: {
                        ToastManager.showToast(listOfNotNull(label, value).joinToString(" - "))
                    },
                )
                .padding(horizontal = Dimens.settingPadding, vertical = Dimens.settingRowPadding),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // whichever element isn't prioritized takes the remaining space and truncates; the
            // prioritized one keeps its full size
            val labelModifier = if (prioritizeValue) Modifier.weight(1f) else Modifier
            val valueModifier = if (prioritizeValue) Modifier else Modifier.weight(1f)

            AppIcon(icon, size = Dimens.settingIcon, tint = tint.copy(alpha = alpha))
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = Dimens.settingLabelSize,
                maxLines = 1,
                color = (labelColor ?: Colors.primaryText).copy(alpha = alpha),
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = labelModifier,
            )
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))

            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = Dimens.settingValueSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    color = onValueColor.copy(alpha = alpha),
                    modifier = valueModifier,
                )
            } else if (valueContent != null) {
                // the label isn't weighted in this case, so push the content to the right
                if (!prioritizeValue) Spacer(modifier = Modifier.weight(1f))
                valueContent()
            } else if (valueIcon != null) {
                if (!prioritizeValue) Spacer(modifier = Modifier.weight(1f))
                AppIcon(
                    res = valueIcon,
                    size = Dimens.settingIcon,
                    tint = (valueIconColor ?: onValueColor).copy(alpha = alpha),
                )
            }
        }

        if (showDivider) HorizontalDivider(color = Colors.divider)
    }
}

/** the last row of a section: what the settings above it are for, as a toast. */
@Composable
fun SettingHelpItem(
    res: StringResource? = null,
    text: String? = null,
) {
    val helpText = res?.let { stringResource(it) } ?: text.orEmpty()
    SettingItem(
        icon = Res.drawable.about,
        labelRes = Res.string.help,
        showDivider = false,
        onClick = { ToastManager.showToast(helpText) },
    )
}
