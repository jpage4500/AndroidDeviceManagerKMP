package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * the full text of the row under the pointer, on a fixed yellow panel.
 *
 * **Not a floating tooltip.** The Swing app pins this to the top or bottom edge of the log table
 * (`MessageTooltipPanel`) at the table's own width, because the messages worth reading are the ones
 * too long for their cell — a bubble that follows the cursor would either be too narrow to help or
 * cover the rows you are comparing it against. Same colours: light yellow on a darker hairline,
 * monospaced, capped at [MAX_HEIGHT].
 *
 * Drawn at the **top** unless the row is near the top of the viewport, in which case it moves to
 * the bottom so it doesn't sit over what you are pointing at.
 */
@Composable
fun BoxScope.MessagePanel(text: String?, atBottom: Boolean) {
    if (text.isNullOrBlank()) return
    Box(
        Modifier
            .align(if (atBottom) Alignment.BottomStart else Alignment.TopStart)
            .fillMaxWidth()
            .heightIn(max = MAX_HEIGHT)
            .background(TOOLTIP_BACKGROUND)
            .border(Dp.Hairline, TOOLTIP_BORDER)
            .verticalScroll(rememberScrollState())
            .padding(PADDING),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            // black rather than the palette's text colour: the panel is always yellow, in both
            // appearances, so its foreground can't follow the theme
            color = Color.Black,
            lineHeight = 15.sp,
        )
    }
}

private val TOOLTIP_BACKGROUND = Color(0xFFFFFFC8)
private val TOOLTIP_BORDER = Color(0xFFB4B496)
private val MAX_HEIGHT = 100.dp
private val PADDING = 8.dp
