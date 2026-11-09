package com.jpage4500.devicemanager.util

import androidx.compose.ui.awt.*
import androidx.compose.ui.input.pointer.*
import java.awt.event.MouseEvent

/**
 * both halves are needed. the type alone lets Compose's invented press through, and the native event
 * alone lets through the `Unknown` copy Compose re-sends when nothing consumed the original — which
 * still carries the AWT press it came from.
 */
actual val PointerEvent.isRealPress: Boolean
    get() = type == PointerEventType.Press && awtEventOrNull?.id == MouseEvent.MOUSE_PRESSED
