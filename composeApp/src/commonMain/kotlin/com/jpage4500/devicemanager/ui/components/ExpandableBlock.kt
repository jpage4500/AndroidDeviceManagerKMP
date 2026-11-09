package com.jpage4500.devicemanager.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * a row that opens into its own contents, drawn as one block.
 *
 * The phone's one expanding idiom — the device list's computers, and whatever else comes to need it:
 * a rounded inset, and an accent stripe down the left of the row *and* what it opened. Content that
 * simply appears below a row reads as the next item in the list rather than as that row's contents —
 * see docs/MOBILE-UI.md.
 *
 * **The ground is the caller's**, painted through [modifier] and the same open or shut. It used to be
 * this block's, tinted to [Colors.listBg] only while open — invisible from the day it was written,
 * because `listBg` is also `MaterialTheme.colorScheme.surface`, the ground the rows inside already
 * paint. A block that stands out has to stand out shut as well: a remembered computer nobody is
 * connected to never opens at all.
 */
@Composable
fun ExpandableBlock(
    open: Boolean,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.corner)
    val stripe = Colors.accent
    val stripeWidth = with(LocalDensity.current) { Dimens.accentStripe.toPx() }

    Column(
        modifier
            .fillMaxWidth()
            // the same inset open or shut: making it conditional moved the icon and the title down
            // and to the right at the instant the body appeared, which read as a stutter
            .padding(Dimens.gap / 2)
            .clip(shape)
            // painted rather than laid out: the stripe has to be as tall as the row *and* its
            // contents, and a sibling in a Row can't know that height while the body is animating
            .drawBehind { if (open) drawRect(stripe, size = Size(stripeWidth, size.height)) },
    ) {
        header()
        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            body()
        }
    }
}

/**
 * the row that heads a block: an icon, a title, a line saying what it is, and what it is doing.
 *
 * [open] is what draws the chevron — pass null for a row that isn't a section — and it turns rather
 * than swapping icon, so the row says which way it is going. [trailing] is anything that belongs to
 * the row rather than to its contents: a "Later" chip, or an info button with a click of its own.
 */
@Composable
fun OptionRow(
    icon: DrawableResource,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    open: Boolean? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tint = iconTint ?: if (enabled) Colors.accent else Colors.menuDisabledText
    val turn by animateFloatAsState(if (open == true) 90f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchRowHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.rowPadding, vertical = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(res = icon, size = Dimens.iconLarge, tint = tint)
        Spacer(Modifier.width(Dimens.rowPadding))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) Colors.primaryText else Colors.menuDisabledText,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                )
            }
        }
        trailing?.invoke()
        // a row that can't be opened says so by having nothing to turn — its trailing content is
        // what explains why
        if (open != null && enabled) {
            AppIcon(
                res = Res.drawable.arrow_right,
                modifier = Modifier.rotate(turn),
                size = Dimens.icon,
                tint = Colors.secondaryText,
            )
        }
    }
}
