package com.jpage4500.devicemanager.ui.components

import androidx.compose.ui.*

// no pointer on a touch screen, so nothing to change the shape of
actual fun Modifier.resizeCursor(): Modifier = this

actual fun Modifier.rowResizeCursor(): Modifier = this
