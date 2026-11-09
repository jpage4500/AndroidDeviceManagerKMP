package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * whether the text in the cell being composed had to be clipped.
 *
 * [TableText] writes the fact in and [CellTooltip] reads it out, which is what gives a table
 * "answer only for the values that don't fit" without every column having to wire it up. Null when
 * nothing is listening — every cell of a table with tooltips off — so the write costs nothing there.
 *
 * static rather than plain: the value is a [MutableState] whose *identity* never changes, so there
 * is nothing for the runtime to track and no reason to pay for tracking it.
 */
val LocalCellClipped = staticCompositionLocalOf<MutableState<Boolean>?> { null }

/**
 * how long the pointer has to rest on a cell before its tooltip appears.
 *
 * Swing's `ToolTipManager` waits 750ms. A table is scanned rather than read, and every cell the
 * pointer crosses on the way somewhere else is a tooltip nobody asked for — so the pause is the
 * whole feature, not a detail of it.
 */
private const val TOOLTIP_DELAY_MS = 600L

/**
 * a cell that answers when the pointer rests on it.
 *
 * [text] is asked **after** the pause rather than before, and is told whether the cell's own text
 * was clipped — by then the cell has been laid out at the width it actually got, so "only if it
 * doesn't fit" is a question that can be answered. Returning null shows nothing.
 *
 * The bubble is placed where the pointer was when the pause elapsed and then stays put: a tooltip
 * that follows the cursor is a tooltip you can't read. Not focusable, either — a `Popup` that takes
 * the keyboard would stop the table's arrow keys dead.
 */
@Composable
fun CellTooltip(
    text: (clipped: Boolean) -> String?,
    modifier: Modifier = Modifier,
    /**
     * padding applied **inside** the hover area rather than around it, so the whole cell answers
     * and not only the text in it — a cell's own padding is the part of it a pointer rests on most.
     */
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable () -> Unit,
) {
    val clipped = remember { mutableStateOf(false) }
    val describe by rememberUpdatedState(text)
    // the live pointer position, and **never read while composing**: read there, every mouse move
    // would recompose the cell, and the bubble would ride along with the cursor
    val pointer = remember { mutableStateOf(Offset.Zero) }
    var hovering by remember { mutableStateOf(false) }
    var tip by remember { mutableStateOf<Tip?>(null) }

    LaunchedEffect(hovering) {
        if (!hovering) {
            tip = null
            return@LaunchedEffect
        }
        delay(TOOLTIP_DELAY_MS)
        describe(clipped.value)
            ?.takeIf { it.isNotBlank() }
            ?.let { tip = Tip(it, pointer.value) }
    }

    Box(
        modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> hovering = true
                            PointerEventType.Exit -> hovering = false
                            // a click puts it away, the way a native one does — and a drag that
                            // starts here and ends elsewhere would leave the bubble behind
                            PointerEventType.Press -> hovering = false
                            PointerEventType.Move ->
                                event.changes.firstOrNull()?.let { pointer.value = it.position }
                            else -> Unit
                        }
                    }
                }
            }
            // after the gesture, so the hover area is the whole cell and the padding only moves
            // what is drawn inside it
            .padding(contentPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        CompositionLocalProvider(LocalCellClipped provides clipped) { content() }
        tip?.let { TooltipBubble(it) }
    }
}

/** what to say and where the pointer was when it became worth saying. */
private data class Tip(val text: String, val at: Offset)

@Composable
private fun TooltipBubble(tip: Tip) {
    val density = LocalDensity.current
    // offset from the cell's own top-left, which is what a Popup composed in here anchors to —
    // the same arrangement the context menus use
    val provider = remember(tip.at, density) {
        PointerPositionProvider(
            with(density) {
                IntOffset(
                    tip.at.x.roundToInt() + Dimens.tooltipInsetX.roundToPx(),
                    tip.at.y.roundToInt() + Dimens.tooltipInsetY.roundToPx(),
                )
            }
        )
    }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false),
    ) {
        val shape = RoundedCornerShape(Dimens.tooltipCorner)
        Box(
            Modifier
                .shadow(Dimens.tooltipShadow, shape)
                .clip(shape)
                .background(Colors.menuBg)
                .border(Dp.Hairline, Colors.menuBorder, shape)
                .widthIn(max = Dimens.tooltipMaxWidth)
                .padding(horizontal = Dimens.tooltipPadding, vertical = Dimens.tooltipPaddingSmall),
        ) {
            Text(
                text = tip.text,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = Dimens.menuTextSize,
                color = Colors.primaryText,
            )
        }
    }
}
