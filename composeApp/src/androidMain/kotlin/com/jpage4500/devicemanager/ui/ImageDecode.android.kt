package com.jpage4500.devicemanager.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.*

actual fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
}.getOrNull()
