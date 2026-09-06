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
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.ui.theme.*

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
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
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
    }
}
