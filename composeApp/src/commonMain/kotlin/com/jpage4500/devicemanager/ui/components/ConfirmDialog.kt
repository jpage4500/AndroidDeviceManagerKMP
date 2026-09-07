package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.theme.*
import org.jetbrains.compose.resources.stringResource

/** what a [ConfirmDialog] is currently asking about; null when nothing is. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

/** are you sure — for the handful of actions that can't be undone. */
@Composable
fun ConfirmDialog(request: ConfirmRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    AppDialog(
        title = request.title,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = {
            request.onConfirm()
            onDismiss()
        },
    ) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            Text(
                text = request.message,
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
            )
        }
    }
}
