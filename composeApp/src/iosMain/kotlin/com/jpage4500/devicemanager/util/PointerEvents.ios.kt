package com.jpage4500.devicemanager.util

import androidx.compose.ui.input.pointer.*

// as on android: one window, so every press is a real one
actual val PointerEvent.isRealPress: Boolean
    get() = type == PointerEventType.Press
