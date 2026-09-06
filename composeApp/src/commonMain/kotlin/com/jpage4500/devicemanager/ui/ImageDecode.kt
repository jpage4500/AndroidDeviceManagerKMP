package com.jpage4500.devicemanager.ui

import androidx.compose.ui.graphics.*

/**
 * turn the PNG `screencap` sends back into something Compose can draw.
 *
 * platform-specific because the three targets have three different decoders — skia on desktop and
 * iOS, `BitmapFactory` on Android — and none of them is reachable from `commonMain`.
 */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
