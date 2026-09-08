package com.jpage4500.devicemanager.ui.setting

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * a settings row whose value is a switch.
 *
 * Uncontrolled by default; pass [checked] for controlled mode, where the parent owns the state —
 * useful for mirroring something that can change outside the app. The whole row is the target, not
 * just the switch. ported from DashboardWeatherKMP.
 */
@Composable
fun SettingSwitchItem(
    icon: DrawableResource,
    iconColor: Color? = null,               // null = the accent colour
    labelText: String? = null,              // label (string version)
    labelRes: StringResource? = null,       // label (resource version)
    descText: String? = null,               // optional second line (string version)
    descRes: StringResource? = null,        // optional second line (resource version)
    initialChecked: Boolean = false,
    checked: Boolean? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val label = labelRes?.let { stringResource(it) } ?: labelText.orEmpty()
    // a description is for a setting whose label can't say what it actually does
    val desc = descRes?.let { stringResource(it) } ?: descText

    // controlled when `checked` is non-null; otherwise fall back to internal state
    var internalChecked by remember { mutableStateOf(initialChecked) }
    val isChecked = checked ?: internalChecked
    val contentAlpha = if (enabled) 1f else 0.38f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        val newValue = !isChecked
                        if (checked == null) internalChecked = newValue
                        onCheckedChange?.invoke(newValue)
                    },
                    onLongClick = onLongClick ?: { ToastManager.showToast(label) },
                )
                .padding(horizontal = Dimens.settingPadding, vertical = Dimens.settingRowPadding),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                res = icon,
                size = Dimens.settingIcon,
                tint = (iconColor ?: Colors.accent).copy(alpha = contentAlpha),
                modifier = Modifier.alpha(contentAlpha),
            )
            Spacer(modifier = Modifier.width(Dimens.settingItemGap))

            Column(modifier = Modifier.weight(1f).alpha(contentAlpha)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = Dimens.settingLabelSize,
                    color = Colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
                if (desc != null) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                    )
                }
            }

            Spacer(modifier = Modifier.width(Dimens.settingItemGap))
            // height-constrained to the icon so the row doesn't grow to Material's switch metrics
            Switch(
                checked = isChecked,
                enabled = enabled,
                onCheckedChange = {
                    if (checked == null) internalChecked = it
                    onCheckedChange?.invoke(it)
                },
                modifier = Modifier.height(Dimens.settingIcon),
            )
        }

        if (showDivider) HorizontalDivider(color = Colors.divider)
    }
}
