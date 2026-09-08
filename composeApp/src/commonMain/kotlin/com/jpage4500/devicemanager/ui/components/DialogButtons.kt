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
 * a dialog's CANCEL / OK row.
 *
 * **Split half and half on a phone**, where the two buttons are the reachable part of the dialog and
 * a thumb should not have to aim; **bottom-right at their natural width on a desktop**, which is
 * where a desktop puts them and where a single CLOSE would otherwise be a grey bar across the whole
 * form. ported from DashboardWeatherKMP, which is a phone and only ever needed the first.
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
    val stretch = !dialogsAreWindows
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap, Alignment.End),
    ) {
        if (cancelText != null) {
            Button(
                onClick = onCancel,
                modifier = if (stretch) Modifier.weight(1f) else Modifier,
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
                modifier = if (stretch) Modifier.weight(1f) else Modifier,
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
