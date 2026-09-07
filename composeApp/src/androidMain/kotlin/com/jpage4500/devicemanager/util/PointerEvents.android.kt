package com.jpage4500.devicemanager.util

import androidx.compose.ui.input.pointer.*

// android has one window per app and no dragging a panel over another, so there are no invented
// presses to filter out
actual val PointerEvent.isRealPress: Boolean
    get() = type == PointerEventType.Press
