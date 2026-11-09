package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import com.jpage4500.devicemanager.ui.dialog.common.*

/** what an [InputDialog] is currently asking for; null when nothing is. */
data class InputRequest(
    val title: String,
    val initialValue: String = "",
    val onConfirm: (String) -> Unit,
)

/**
 * one line of text, asked for in a dialog.
 *
 * every "Edit …" goes through this rather than each growing its own. The field takes focus, and
 * Enter confirms. Drawn by the ported [TextInputDialog]; an empty answer is allowed, since clearing
 * a nickname back to nothing is a real thing to want.
 */
@Composable
fun InputDialog(request: InputRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    // keyed, so a second request replacing the first without passing through null gets a field with
    // the new value in it rather than the old one
    key(request) {
        TextInputDialog(
            title = request.title,
            initialValue = request.initialValue,
            validator = { true },
            onDismiss = onDismiss,
            onConfirm = {
                request.onConfirm(it.trim())
                onDismiss()
            },
        )
    }
}
