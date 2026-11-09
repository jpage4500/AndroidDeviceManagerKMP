package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import com.jpage4500.devicemanager.util.*
import kotlin.math.floor

/**
 * a QR code, drawn.
 *
 * **Always dark on white, in both themes.** A QR inverted for a dark theme is one a good half of
 * scanners refuse — the spec allows it, cameras in practice do not — and the white quiet zone around
 * it is part of the code rather than padding. So this paints its own ground instead of taking the
 * one behind it.
 *
 * The module size is rounded down to a whole pixel and the result centred: at a fractional size the
 * rects land on half pixels and the anti-aliasing between them reads as grey lines, which is exactly
 * what a scanner is looking for.
 */
@Composable
fun QrImage(content: String, modifier: Modifier = Modifier, quietZone: Int = QUIET_ZONE) {
    val matrix = remember(content) { QrCode.encode(content) } ?: return
    Canvas(modifier.aspectRatio(1f)) {
        val across = matrix.size + quietZone * 2
        val module = floor(size.minDimension / across).coerceAtLeast(1f)
        val used = module * across
        val left = (size.width - used) / 2
        val top = (size.height - used) / 2

        drawRect(Color.White, topLeft = Offset(left, top), size = Size(used, used))
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix[x, y]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left + (x + quietZone) * module, top + (y + quietZone) * module),
                    size = Size(module, module),
                )
            }
        }
    }
}

/** four light modules on every side, which is what the spec asks for. */
private const val QUIET_ZONE = 4
