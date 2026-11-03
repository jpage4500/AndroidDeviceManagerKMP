package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.*

/**
 * a 24×24 icon drawn from SVG path data, stroked (or [filled]) in [tint].
 *
 * for the icons the app's PNGs don't have — the device tile's serial, phone, IMEI and the rest.
 * Drawn rather than bitmaps, they stay sharp at every size the icon view's slider picks. The paths
 * are in [GlyphPaths].
 */
@Composable
fun PathIcon(path: String, size: Dp, tint: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            if (filled) {
                drawPath(parsed, tint)
            } else {
                drawPath(parsed, tint, style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** path data for [PathIcon], on a 24×24 grid. Every one is drawn stroked except [SERIAL]. */
object GlyphPaths {
    // a barcode — draw filled
    const val SERIAL = "M2 5h2v14H2zM5.5 5h1v14h-1zM8 5h2.5v14H8zM12 5h1v14h-1zM14.5 5h2v14h-2zM18 5h1v14h-1zM20.5 5h1.5v14h-1.5z"

    // a handset
    const val PHONE =
        "M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3" +
            "a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4" +
            "c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z"

    // a SIM card, for the IMEI
    const val SIM = "M6 2h9l5 5v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM8 12h8v6H8zM12 12v6M8 15h8"

    // signal bars, for the carrier
    const val SIGNAL = "M3 20v-2M8 20v-5M13 20v-8M18 20V8"

    // a luggage tag, for a custom field
    const val TAG =
        "M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6" +
            "a2.4 2.4 0 0 0 0-3.4zM7.5 7.5h.01"

    // a battery on its side
    const val BATTERY = "M4 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM22 10.5v3"

    // a drive, for storage
    const val STORAGE =
        "M22 12H2M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.8 1.1zM6 16h.01M10 16h.01"

    // Wi-Fi struck through, for disconnecting a wireless device
    const val WIFI_OFF =
        "M12 20h.01M8.5 16.4a5 5 0 0 1 7 0M2 8.8a15 15 0 0 1 4.2-2.6M10.7 5c4-.4 8.1.9 11.3 3.8M16.9 11.3a10 10 0 0 1 2.2 1.7" +
            "M5 13a10 10 0 0 1 5.2-2.8M2 2l20 20"
}
