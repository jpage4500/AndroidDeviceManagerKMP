package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import com.jpage4500.devicemanager.resources.*
import org.jetbrains.compose.resources.stringResource

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
 * Enter confirms.
 */
@Composable
fun InputDialog(request: InputRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    val focus = remember { FocusRequester() }
    var value by remember(request) { mutableStateOf(request.initialValue) }

    fun confirm() {
        request.onConfirm(value.trim())
        onDismiss()
    }

    AppDialog(
        title = request.title,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.ok),
        onConfirm = ::confirm,
    ) {
        AppTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            focusRequester = focus,
            onSubmit = ::confirm,
        )
        FocusOnShow(focus, request)
    }
}
