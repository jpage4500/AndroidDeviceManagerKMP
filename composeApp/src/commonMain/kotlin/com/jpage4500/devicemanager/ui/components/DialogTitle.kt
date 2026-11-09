package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * the bar across the top of a dialog: an optional icon, the title, and a close button.
 *
 * the whole bar is the close button, not just the X — the X is what says so. ported from
 * DashboardWeatherKMP.
 */
@Composable
fun DialogTitle(
    title: String,
    icon: DrawableResource? = null,
    iconTint: Color? = null,    // null = the accent colour
    subtitle: String? = null,   // a dimmer second line inside the same band
    maxLines: Int = 1,          // lines the title may wrap to before it ellipsizes
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss)
            .background(Colors.dialogTitleBg)
            .padding(horizontal = Dimens.gapLarge, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap + 4.dp),
        ) {
            icon?.let {
                AppIcon(
                    res = it,
                    size = Dimens.dialogTitleIcon,
                    tint = iconTint ?: Colors.accent,
                    contentDescription = title,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = Dimens.dialogTitleSize,
                    color = Colors.accent,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                // what the title alone can't say — which computer a device came in through, say.
                // inside the band rather than under it, so the heading is still one block
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AppIcon(
            res = Res.drawable.close,
            size = Dimens.dialogTitleIcon,
            tint = Colors.accent,
            contentDescription = null,
        )
    }
}
