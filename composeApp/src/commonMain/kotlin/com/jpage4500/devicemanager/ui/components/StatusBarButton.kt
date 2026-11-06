package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a status bar item that does something when clicked — the Swing app's `HoverLabel`: flat until the
 * pointer is over it, then a grey chip so it reads as a button and not as more status text.
 *
 * @param progress 0..100 fills the chip from the left behind the content, green or [isError] red,
 *   the way the Swing status label shows a running command; -1 for no fill
 */
@Composable
fun StatusBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Int = -1,
    isError: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hover = Colors.statusBarHover
    // the Swing label's fill is its colour at alpha 80 of 255, low enough that the text stays readable
    val fill = (if (isError) Colors.error else Colors.online).copy(alpha = 0.31f)
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .drawBehind {
                if (hovered) drawRect(hover)
                if (progress >= 0) {
                    drawRect(fill, size = Size(size.width * progress.coerceAtMost(100) / 100f, size.height))
                }
            }
            .focusProperties { canFocus = false }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
