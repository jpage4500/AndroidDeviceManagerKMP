package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*

actual val dialogsAreWindows = false

@Composable
actual fun DialogContainer(
    title: String,
    onDismiss: () -> Unit,
    onEnter: (() -> Unit)?,
    content: @Composable () -> Unit,
) = OverlayDialogContainer(onDismiss, content)
