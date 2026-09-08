package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

actual val dialogsAreWindows = true

/**
 * a real dialog window, owned by the window it was opened from.
 *
 * `DpSize.Unspecified` lets the window size itself to the form, so a dialog is never bigger than
 * what it holds and a taller form doesn't need a hand-picked height. The form caps its own height,
 * which is what stops the app log from asking for a window taller than the screen.
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
        // resizable, because one of these is now the app log viewer and a form the user can't
        // widen is a form with a scrollbar it didn't need
        resizable = true,
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
