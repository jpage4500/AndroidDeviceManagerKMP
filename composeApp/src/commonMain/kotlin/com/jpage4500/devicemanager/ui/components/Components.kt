package com.jpage4500.devicemanager.ui.components

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
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** the coloured dot in front of a device row — the state at a glance, before any text is read. */
@Composable
fun StateDot(state: DeviceState, modifier: Modifier = Modifier) {
    val color = when (state) {
        DeviceState.DEVICE -> Colors.online
        DeviceState.UNAUTHORIZED, DeviceState.AUTHORIZING -> Colors.unauthorized
        DeviceState.OFFLINE, DeviceState.DETACHED -> Colors.offline
        else -> Colors.error
    }
    Box(
        modifier
            .size(Dimens.statusDot)
            .clip(CircleShape)
            .background(color)
    )
}

/** a short uppercase label — a device's OS version, its carrier, the transport it came in on. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** what a list says when it has nothing in it — never an empty pane. */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    /** something to do about it, under the text — a button, usually. */
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Dimens.gapLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(Dimens.gap))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Dimens.gapLarge))
            action()
        }
    }
}

/**
 * one of the app's own icons, tinted.
 *
 * every icon is a monochrome PNG carried over from the Swing app, so a tint is what gives it its
 * colour — there is no coloured artwork to preserve. `onSurface` by default, so an icon follows the
 * theme the way text does.
 */
@Composable
fun AppIcon(
    res: DrawableResource,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.icon,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * a toolbar button: icon above a label, the way the Swing app's toolbar reads.
 *
 * disabled rather than hidden when there's nothing to act on — a toolbar whose buttons come and go
 * as devices are selected is a toolbar you have to re-find every time.
 */
@Composable
fun ToolbarButton(
    res: DrawableResource,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    tooltip: String? = null,
) {
    val alpha = if (enabled) 1f else 0.35f
    val button = @Composable {
        // a minimum width rather than a fixed one: a label like "Auto Format OFF" is wider than the
        // icon above it, and the button grows to hold it rather than cutting it off
        Column(
            modifier
                .widthIn(min = Dimens.toolbarButtonWidth)
                .clip(RoundedCornerShape(Dimens.corner))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 4.dp, horizontal = Dimens.toolbarButtonPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(res, size = Dimens.toolbarIcon, tint = tint.copy(alpha = alpha), contentDescription = label)
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.8f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    if (tooltip == null) button() else CellTooltip(text = { tooltip }) { button() }
}

/** the vertical rule between toolbar groups. */
@Composable
fun ToolbarSeparator() {
    VerticalDivider(Modifier.height(Dimens.toolbarIcon).padding(horizontal = Dimens.gap / 2))
}

/**
 * a battery as a bar rather than a number, because 45 of them are read at a glance.
 *
 * green down to 30%, amber to 15%, red below — the thresholds the Swing app's battery icons use.
 *
 * amber is `busy` rather than `unauthorized`: the latter is now the Swing app's "not ready" yellow,
 * which is all but invisible as a 2px bar on white.
 */
@Composable
fun BatteryLevel(level: Int?, charging: Boolean, modifier: Modifier = Modifier) {
    if (level == null) {
        TableText("", modifier)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BatteryBar(level)
        if (charging) {
            Spacer(Modifier.width(2.dp))
            AppIcon(Res.drawable.charging, size = 10.dp, tint = Colors.busy)
        }
    }
}

/**
 * the same battery, stacked, for a phone row.
 *
 * The number goes **above** the bar rather than beside it: a 28dp table row has no second line so
 * the desktop reads the bar, but a 68dp touch row does, and at arm's length the digits are what
 * anyone actually reads — the bar is the thing you glance at across forty-five of them.
 */
@Composable
fun BatteryGauge(level: Int?, charging: Boolean, modifier: Modifier = Modifier) {
    if (level == null) return
    Column(
        modifier.width(Dimens.batteryGaugeWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$level%",
            style = MaterialTheme.typography.labelSmall,
            color = batteryColor(level),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryBar(level)
            Spacer(Modifier.width(2.dp))
            // the slot is there whether or not the bolt is, so plugging a device in doesn't move
            // its own bar — and every row's bar lines up with every other row's
            Box(Modifier.size(Dimens.icon)) {
                if (charging) AppIcon(Res.drawable.charging, tint = Colors.busy)
            }
        }
    }
}

@Composable
private fun BatteryBar(level: Int) {
    Box(
        Modifier
            .size(Dimens.batteryWidth, Dimens.batteryHeight)
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
            .padding(1.5.dp),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0, 100) / 100f)
                .clip(RoundedCornerShape(1.dp))
                .background(batteryColor(level))
        )
    }
}

fun batteryColor(level: Int): Color = when {
    level > 30 -> Colors.online
    level > 15 -> Colors.busy
    else -> Colors.error
}
