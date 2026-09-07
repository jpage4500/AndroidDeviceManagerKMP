package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/**
 * a real dialog window, owned by the window it was opened from.
 *
 * `DpSize.Unspecified` lets the window size itself to the form, so a dialog is never bigger than
 * what it holds and a taller form doesn't need a hand-picked height.
 */
@Composable
actual fun DialogContainer(
    title: String,
    onDismiss: () -> Unit,
    onEnter: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize.Unspecified),
        title = title,
        resizable = false,
        // last in line: anything focused inside the form sees these first and can consume them
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@DialogWindow false
            when (event.key) {
                Key.Escape -> { onDismiss(); true }
                Key.Enter, Key.NumPadEnter -> onEnter?.let { it(); true } ?: false
                else -> false
            }
        },
    ) {
        CompositionLocalProvider(LocalDialogTitled provides true) { content() }
    }
}
