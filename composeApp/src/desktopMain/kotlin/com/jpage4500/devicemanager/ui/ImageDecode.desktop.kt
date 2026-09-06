package com.jpage4500.devicemanager.ui

import androidx.compose.ui.graphics.*
import org.jetbrains.skia.Image

// skia is what compose desktop already draws with, so nothing extra is pulled in to decode a PNG
actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
