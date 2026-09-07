package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.*
import com.jpage4500.devicemanager.util.*

// nothing to drag from on a touch screen
@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onDragChanged: (Boolean) -> Unit,
    onDrop: (List<LocalFile>) -> Unit,
): Modifier = this
