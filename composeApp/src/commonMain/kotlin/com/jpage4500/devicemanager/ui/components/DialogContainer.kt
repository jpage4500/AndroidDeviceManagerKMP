package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.window.*

/**
 * whether the container around a dialog already shows its title.
 *
 * true on the desktop, where a dialog is a real OS window with a real title bar; false on a phone,
 * where it is an overlay and the panel has to draw its own heading.
 */
val LocalDialogTitled = staticCompositionLocalOf { false }

/**
 * what a dialog sits in.
 *
 * The desktop gets a separate OS window with a title bar and a close button, which is what the Swing
 * app's `JDialog`s were and what makes a form read as a form rather than as a card floating over the
 * app. A phone gets an overlay, because a phone has no windows.
 *
 * [onEnter] is handled by the window rather than by the form, so the default button answers Enter
 * even when nothing inside has focus — a confirmation with no fields in it, say.
 */
@Composable
expect fun DialogContainer(
    title: String,
    onDismiss: () -> Unit,
    onEnter: (() -> Unit)?,
    content: @Composable () -> Unit,
)

/** the phone's container, shared by the Android and iOS actuals. */
@Composable
internal fun OverlayDialogContainer(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        // Compose's default caps a dialog at a phone's dialog width, which cuts these forms off
        properties = DialogProperties(usePlatformDefaultWidth = false),
        content = content,
    )
}
