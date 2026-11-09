package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.shape.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.*

/**
 * a card that hangs off the open tab above it: its ground, and an outline down both sides and round
 * the bottom but **not** across the top.
 *
 * the top edge is the tab strip's rule, already drawn by the strip — a second line there would show
 * under the open tab, which is the one place the two are meant to read as a single shape. The
 * ground has to be the open tab's (`DeviceTabStrip`'s `openGround`) for the same reason.
 */
fun Modifier.joinedCard(ground: Color, outline: Color, corner: Dp, stroke: Dp): Modifier =
    clip(RoundedCornerShape(bottomStart = corner, bottomEnd = corner))
        .background(ground)
        .drawBehind {
            val w = stroke.toPx()
            val r = corner.toPx()
            // inset by half the stroke so the line sits inside the clip rather than half outside it
            val left = w / 2
            val right = size.width - w / 2
            val bottom = size.height - w / 2
            val path = Path().apply {
                moveTo(left, 0f)
                lineTo(left, bottom - r)
                arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 180f, -90f, false)
                lineTo(right - r, bottom)
                arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 90f, -90f, false)
                lineTo(right, 0f)
            }
            drawPath(path, outline, style = Stroke(width = w))
        }
