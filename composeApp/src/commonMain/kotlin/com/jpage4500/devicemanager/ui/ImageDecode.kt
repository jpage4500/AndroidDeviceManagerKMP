package com.jpage4500.devicemanager.ui

import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*

/**
 * turn the PNG `screencap` sends back into something Compose can draw.
 *
 * platform-specific because the three targets have three different decoders — skia on desktop and
 * iOS, `BitmapFactory` on Android — and none of them is reachable from `commonMain`.
 */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * this image no taller than [maxHeight] pixels, keeping its shape; itself if it already fits.
 *
 * for a still that is only ever shown small: a full-size capture is ~10 MB of pixels, the same
 * screen at a preview's height a few hundred KB, which is what makes keeping one per device cheap.
 */
fun ImageBitmap.scaledToHeight(maxHeight: Int): ImageBitmap {
    if (height <= maxHeight || maxHeight <= 0) return this
    val w = (width.toLong() * maxHeight / height).toInt().coerceAtLeast(1)
    val out = ImageBitmap(w, maxHeight)
    Canvas(out).drawImageRect(
        this,
        dstSize = IntSize(w, maxHeight),
        paint = Paint().apply { filterQuality = FilterQuality.Medium },
    )
    return out
}
