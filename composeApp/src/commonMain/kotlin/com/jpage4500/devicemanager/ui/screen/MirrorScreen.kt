package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import kotlin.math.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * a device's screen, with taps, holds, swipes and keys sent back to it.
 *
 * The frame is whatever `screencap` last returned, so a gesture has to be mapped from where it
 * landed in the drawn image back to the device's own pixels — the image is scaled to fit and the
 * device's resolution is whatever the PNG says it is, not whatever this pane happens to be.
 */
@Composable
fun MirrorScreen(
    deviceId: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val source = remember(deviceId) { MirrorSource(deviceId) }
    val frameBytes by source.frame.collectAsState()
    val error by source.error.collectAsState()
    // held here rather than inside [MirrorImage]: a key press comes from the button row below it
    val feedback = rememberMirrorFeedback()

    // a mirror left running in the background is a screencap every 250ms for a frame nobody can
    // see — and on a metered link, rude. it picks up again on the way back — see AppLifecycle
    LaunchedEffect(deviceId) { whileForeground { source.run() } }

    val image = remember(frameBytes) { frameBytes?.let { decodeImage(it) } }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                image != null -> MirrorImage(
                    image = image,
                    feedback = feedback,
                    onTap = { x, y -> scope.launch { source.tap(x, y) } },
                    onLongPress = { x, y -> scope.launch { source.longPress(x, y) } },
                    onSwipe = { x1, y1, x2, y2, durationMs ->
                        scope.launch { source.swipe(x1, y1, x2, y2, durationMs) }
                    },
                )
                error != null -> EmptyState(stringResource(Res.string.mirror_failed), error ?: "")
                else -> CircularProgressIndicator()
            }
        }
        MirrorButtons { key, label ->
            scope.launch { source.key(key) }
            feedback.key(label)
        }
    }
}

/**
 * the frame, the arithmetic that turns a gesture on it into one on the device, and the feedback
 * drawn back over the top.
 *
 * The drawn size is worked out here rather than left to `ContentScale.Fit`, because the mapping back
 * needs the same numbers: without them a tap near the edge of a letterboxed image lands somewhere
 * else entirely on the phone — and the feedback would be drawn in the wrong place too.
 */
@Composable
private fun MirrorImage(
    image: ImageBitmap,
    feedback: MirrorFeedback,
    onTap: (x: Int, y: Int) -> Unit,
    onLongPress: (x: Int, y: Int) -> Unit,
    onSwipe: (x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // explicit Floats throughout: compose has minOf/div overloads for Size and ScaleFactor,
        // and letting either of those win here silently changes what the tap maths is measuring
        val boxWidth: Float = with(density) { maxWidth.toPx() }
        val boxHeight: Float = with(density) { maxHeight.toPx() }
        val scale: Float = min(boxWidth / image.width.toFloat(), boxHeight / image.height.toFloat())
        val drawnWidth: Float = image.width * scale
        val drawnHeight: Float = image.height * scale

        // the gesture goes on the box rather than on the image, so the feedback drawn over the top
        // of it is never in the way of the next tap
        Box(
            Modifier
                .size(
                    with(density) { drawnWidth.toDp() },
                    with(density) { drawnHeight.toDp() },
                )
                .pointerInput(image) {
                    awaitEachGesture {
                        detectMirrorGesture(
                            onTap = { at ->
                                val point = at.toDevice(image, scale)
                                onTap(point.x, point.y)
                                feedback.tap(at)
                            },
                            onLongPress = { at ->
                                val point = at.toDevice(image, scale)
                                onLongPress(point.x, point.y)
                                feedback.longPress(at)
                            },
                            onSwipe = { from, to, heldMs ->
                                val start = from.toDevice(image, scale)
                                val end = to.toDevice(image, scale)
                                onSwipe(
                                    start.x, start.y, end.x, end.y,
                                    heldMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS),
                                )
                                feedback.swipe(from, to)
                            },
                        )
                    }
                },
        ) {
            Image(
                bitmap = image,
                contentDescription = stringResource(Res.string.device_screen),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            // over the frame, not in it: the device won't show the tap for a frame or two yet
            MirrorFeedbackOverlay(feedback, Modifier.matchParentSize())
        }
    }
}

/**
 * one finger, told apart into a tap, a hold or a swipe.
 *
 * All three come out of one loop, because that is what they are: `detectTapGestures` on its own
 * reported a drag as a tap wherever the finger happened to lift, which is a scroll turning into a
 * click on whatever was under it.
 *
 * **The hold fires while the finger is still down**, the way the Swing window's 500ms timer does —
 * feedback that waits for the finger to lift is feedback nobody waits for, and the point of it is
 * saying "you can let go now". A pointer scope reports that timeout by throwing rather than by
 * returning, which is why the first phase is wrapped; it is the shape `detectTapGestures` uses.
 */
private suspend fun AwaitPointerEventScope.detectMirrorGesture(
    onTap: (at: Offset) -> Unit,
    onLongPress: (at: Offset) -> Unit,
    onSwipe: (from: Offset, to: Offset, heldMs: Int) -> Unit,
) {
    val down = awaitFirstDown()
    var last = down
    var isSwipe = false
    var isHold = false
    var isUp = false

    fun track(change: PointerInputChange) {
        last = change
        if (!isSwipe && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            isSwipe = true
        }
        // a swipe or a hold owns the gesture from here; nothing under it should act on it as well
        if (isSwipe || isHold) change.consume()
        if (!change.pressed) isUp = true
    }

    // phase one: the hold, which a lift or any real movement calls off
    try {
        withTimeout(LONG_PRESS_MS.toLong()) {
            while (!isUp && !isSwipe) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change == null) isUp = true else track(change)
            }
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        isHold = true
        onLongPress(down.position)
    }

    // phase two: the rest of it, with the clock off
    while (!isUp) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        track(change)
    }

    when {
        // the hold was reported the moment it happened; what the finger did after it is nothing
        isHold -> Unit
        // replayed over the time the finger actually took, so a flick reads as a flick; the ceiling
        // stops a slow drag from holding the connection open
        isSwipe -> onSwipe(down.position, last.position, (last.uptimeMillis - down.uptimeMillis).toInt())
        else -> onTap(down.position)
    }
}

/** back from drawn pixels to the device's own. */
private fun Offset.toDevice(image: ImageBitmap, scale: Float): IntOffset = IntOffset(
    (x / scale).toInt().coerceIn(0, image.width - 1),
    (y / scale).toInt().coerceIn(0, image.height - 1),
)

// `input swipe 0` is ignored by some devices, and a minute-long drag would hold the shell that long
private const val MIN_SWIPE_MS = 50
private const val MAX_SWIPE_MS = 2000

/** how long a press has to be held to become one — the Swing window's own threshold. */
private const val LONG_PRESS_MS = 500

/** the three keys a phone has and a mirrored screen doesn't. */
@Composable
private fun MirrorButtons(onKey: (keyCode: Int, label: String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Dimens.gap),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap, Alignment.CenterHorizontally),
    ) {
        MirrorKey(Res.string.key_back, DeviceInput.KEY_BACK, onKey)
        MirrorKey(Res.string.key_home, DeviceInput.KEY_HOME, onKey)
        MirrorKey(Res.string.key_app_switch, DeviceInput.KEY_APP_SWITCH, onKey)
        MirrorKey(Res.string.key_power, DeviceInput.KEY_POWER, onKey)
    }
}

/** the label goes back up with the key code: it is what the feedback bubble says. */
@Composable
private fun MirrorKey(text: StringResource, keyCode: Int, onKey: (Int, String) -> Unit) {
    val label = stringResource(text)
    TextButton(onClick = { onKey(keyCode, label) }) { Text(label) }
}
