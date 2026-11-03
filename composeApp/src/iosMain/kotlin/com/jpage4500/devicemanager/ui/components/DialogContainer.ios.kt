package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.unit.*

actual val dialogsAreWindows = false

@Composable
actual fun DialogContainer(
    title: String,
    onDismiss: () -> Unit,
    onEnter: (() -> Unit)?,
    size: DpSize?,
    content: @Composable () -> Unit,
) = OverlayDialogContainer(onDismiss, content)
