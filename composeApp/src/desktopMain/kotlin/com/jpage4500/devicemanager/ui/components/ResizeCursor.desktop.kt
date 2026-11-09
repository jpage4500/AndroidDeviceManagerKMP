package com.jpage4500.devicemanager.ui.components

import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.*
import java.awt.Cursor

private val horizontalResize = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

private val verticalResize = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

actual fun Modifier.resizeCursor(): Modifier = pointerHoverIcon(horizontalResize)

actual fun Modifier.rowResizeCursor(): Modifier = pointerHoverIcon(verticalResize)
