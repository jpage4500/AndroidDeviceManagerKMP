package com.jpage4500.devicemanager.ui.components

import androidx.compose.ui.*

/**
 * the left-right arrow over a column divider.
 *
 * desktop only — `PointerIcon` needs an AWT cursor, and a touch screen has no pointer to change.
 */
expect fun Modifier.resizeCursor(): Modifier
