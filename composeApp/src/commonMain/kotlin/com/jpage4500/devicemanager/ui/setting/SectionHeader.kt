package com.jpage4500.devicemanager.ui.setting

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a settings section header: grey text over a divider.
 *
 * deliberately small and tight — a settings screen carries several of these and none of them can
 * spend a full-size heading and its whitespace. ported from DashboardWeatherKMP.
 */
@Composable
fun SectionHeader(text: String) {
    HeaderRow(text = text, open = null, onToggle = {})
}

/**
 * the same header, with everything under it folded away when [open] is false.
 *
 * The chevron **turns** rather than swapping icon, which is what the phone's [OptionRow] does, so
 * the app has one way of saying "this opens" rather than two. Whether it is open is the caller's to
 * hold and — on a screen someone shuts a section on once and wants shut next time — to persist.
 */
@Composable
fun CollapsibleSection(
    text: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HeaderRow(text = text, open = open, onToggle = onToggle)
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(content = content)
        }
    }
}

/** [open] null is a header that heads something already on screen: no chevron, and no tap. */
@Composable
private fun HeaderRow(text: String, open: Boolean?, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open == true) 90f else 0f, label = "sectionChevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (open == null) Modifier else Modifier.clickable(onClick = onToggle))
            .heightIn(min = if (open == null) 0.dp else Dimens.sectionHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Colors.secondaryText,
            modifier = Modifier.weight(1f).padding(vertical = 2.dp),
        )
        if (open != null) {
            AppIcon(
                res = Res.drawable.arrow_right,
                modifier = Modifier.rotate(turn),
                size = Dimens.icon,
                tint = Colors.secondaryText,
            )
        }
    }
    HorizontalDivider(color = Colors.divider)
}
