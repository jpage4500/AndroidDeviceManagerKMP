package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
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
                AppIcon(it, size = Dimens.dialogTitleIcon, tint = Colors.accent, contentDescription = title)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontSize = Dimens.dialogTitleSize,
                color = Colors.accent,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AppIcon(
            res = Res.drawable.close,
            size = Dimens.dialogTitleIcon,
            tint = Colors.accent,
            contentDescription = null,
        )
    }
}
