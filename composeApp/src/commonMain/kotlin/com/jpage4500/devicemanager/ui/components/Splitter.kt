package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * the draggable divider between two panes.
 *
 * The visible rule stays a hairline; the drag target around it is [Dimens.splitterHandle], because
 * a 1px target is a target you miss. Dragging is reported as a new width for the pane on the
 * **left**, clamped, so the caller has one number to persist.
 */
@Composable
fun VerticalSplitter(
    width: Dp,
    onWidthChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    min: Dp = Dimens.splitterMin,
    max: Dp = Dimens.splitterMax,
) {
    val density = LocalDensity.current
    Box(
        modifier
            .width(Dimens.splitterHandle)
            .fillMaxHeight()
            .resizeCursor()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    onWidthChange((width + with(density) { delta.toDp() }).coerceIn(min, max))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = Colors.divider)
    }
}

/**
 * the draggable divider between a pane and the one under it — [VerticalSplitter] turned on its side.
 *
 * Reports each drag as a change in px for the caller to apply to whatever it sizes by (a fraction,
 * here), and [onDragStopped] once the drag ends — the moment to persist, rather than every frame.
 */
@Composable
fun HorizontalSplitter(onDrag: (Float) -> Unit, onDragStopped: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(Dimens.splitterHandle)
            .fillMaxWidth()
            .rowResizeCursor()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState(onDrag),
                onDragStopped = { onDragStopped() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalDivider(color = Colors.divider)
    }
}
