package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import com.jpage4500.devicemanager.ui.dialog.common.*

/** what a [ConfirmDialog] is currently asking about; null when nothing is. */
data class ConfirmRequest(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit,
)

/**
 * are you sure — for the handful of actions that can't be undone.
 *
 * The request-shaped wrapper is what every caller already holds; the dialog it draws is the ported
 * [ConfirmationDialog], so a delete prompt in the file explorer reads the same as one in settings.
 */
@Composable
fun ConfirmDialog(request: ConfirmRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    ConfirmationDialog(
        title = request.title,
        message = request.message,
        onDismiss = onDismiss,
        onConfirm = request.onConfirm,
    )
}
