package com.jpage4500.devicemanager.ui.components

import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import java.awt.Cursor

private val horizontalResize = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual fun Modifier.resizeCursor(): Modifier = pointerHoverIcon(horizontalResize)
