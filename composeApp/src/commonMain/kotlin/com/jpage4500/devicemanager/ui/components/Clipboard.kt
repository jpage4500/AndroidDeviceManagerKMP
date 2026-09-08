package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*

/**
 * put plain text on the clipboard.
 *
 * still `LocalClipboardManager`, which Compose 1.11 deprecates in favour of the suspending
 * `LocalClipboard` — but that one takes a `ClipEntry`, and building one from text needs a
 * platform-specific constructor `commonMain` has no accessor for yet. One place to change when it
 * does, rather than a suppression at every call site.
 */
@Suppress("DEPRECATION")
@Composable
fun rememberClipboardCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { text -> clipboard.setText(AnnotatedString(text)) } }
}

/**
 * read plain text back off the clipboard.
 *
 * one caller so far, and it is the reason the connect panel is usable: the Swing app's Share Server
 * dialog copies an `adm://` connection string, and pasting it beats typing a 16-character token on
 * a soft keyboard.
 */
@Suppress("DEPRECATION")
@Composable
fun rememberClipboardPaste(): () -> String? {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) { { clipboard.getText()?.text } }
}
