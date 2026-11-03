package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
// explicit: the graphics package's Color would collide with AWT's
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.ui.theme.*
import java.awt.Color as AwtColor

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
    size: DpSize?,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = size ?: DpSize.Unspecified),
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
        // the form's height as the window measures it, set directly and only when it differs. Not by
        // handing the state an unspecified height again: that packs the window to the screen's height
        // before shrinking it to the form, and every rule added flashed the window tall
        val fit = remember(window) {
            // a window opened at a size of its own keeps it
            if (size != null) return@remember {}
            {
                val wanted = window.preferredSize.height
                if (wanted != window.height) window.setSize(window.width, wanted)
            }
        }
        // the window itself in the form's colour: Compose leaves what its content doesn't cover to the
        // window's background, so a window made bigger than its form showed the form on a grey panel.
        // Not a fillMaxSize box under the content — the window packs against the screen's size, and
        // that box opened every dialog full screen
        val ground = Colors.menuBg.toArgb()
        SideEffect {
            window.background = AwtColor(ground, true)
            window.contentPane.background = AwtColor(ground, true)
        }
        CompositionLocalProvider(LocalDialogTitled provides true, LocalDialogFit provides fit) { content() }
    }
}
