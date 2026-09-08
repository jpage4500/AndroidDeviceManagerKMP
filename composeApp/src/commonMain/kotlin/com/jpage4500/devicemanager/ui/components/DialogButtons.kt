package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a dialog's CANCEL / OK row, each button taking half the width.
 *
 * Full-width rather than a pair huddled bottom-right: on a phone the two buttons *are* the reachable
 * part of the dialog, and the desktop reads them fine at the same weight. ported from
 * DashboardWeatherKMP.
 */
@Composable
fun DialogButtons(
    okText: String?,
    cancelText: String?,
    onOk: () -> Unit,
    onCancel: () -> Unit,
    okEnabled: Boolean = true,      // only applies when okText is not null
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        if (cancelText != null) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Colors.offline),
                shape = RoundedCornerShape(Dimens.gap),
            ) {
                DialogButtonLabel(cancelText, Color.White)
            }
        }
        if (okText != null) {
            Button(
                onClick = onOk,
                enabled = okEnabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(Dimens.gap),
            ) {
                DialogButtonLabel(okText, LocalContentColor.current)
            }
        }
    }
}

@Composable
private fun DialogButtonLabel(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        fontSize = Dimens.dialogButtonSize,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Bold,
    )
}
