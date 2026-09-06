package com.jpage4500.devicemanager.ui

import androidx.compose.ui.graphics.*
import org.jetbrains.skia.Image

// the same skia compose already draws with on iOS
actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
