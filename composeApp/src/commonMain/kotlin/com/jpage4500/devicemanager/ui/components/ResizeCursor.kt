package com.jpage4500.devicemanager.ui.components

import androidx.compose.ui.*

/**
 * the left-right arrow over a column divider.
 *
 * desktop only — `PointerIcon` needs an AWT cursor, and a touch screen has no pointer to change.
 */
expect fun Modifier.resizeCursor(): Modifier

/** the up-down arrow over a divider between a pane and the one under it — desktop only, as [resizeCursor]. */
expect fun Modifier.rowResizeCursor(): Modifier
