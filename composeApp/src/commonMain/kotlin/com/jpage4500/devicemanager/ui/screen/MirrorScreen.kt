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
import org.jetbrains.compose.resources.stringResource

/**
 * a device's screen, with taps and swipes sent back to it.
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

    // a mirror left running in the background is a screencap every 250ms for a frame nobody can
    // see — and on a metered link, rude. it picks up again on the way back — see AppLifecycle
    LaunchedEffect(deviceId) { whileForeground { source.run() } }

    val image = remember(frameBytes) { frameBytes?.let { decodeImage(it) } }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                image != null -> MirrorImage(
                    image = image,
                    onTap = { x, y -> scope.launch { source.tap(x, y) } },
                    onSwipe = { x1, y1, x2, y2, durationMs ->
                        scope.launch { source.swipe(x1, y1, x2, y2, durationMs) }
                    },
                )
                error != null -> EmptyState(stringResource(Res.string.mirror_failed), error ?: "")
                else -> CircularProgressIndicator()
            }
        }
        MirrorButtons { key -> scope.launch { source.key(key) } }
    }
}

/**
 * the frame, and the arithmetic that turns a gesture on it into one on the device.
 *
 * The drawn size is worked out here rather than left to `ContentScale.Fit`, because the mapping back
 * needs the same numbers: without them a tap near the edge of a letterboxed image lands somewhere
 * else entirely on the phone.
 */
@Composable
private fun MirrorImage(
    image: ImageBitmap,
    onTap: (x: Int, y: Int) -> Unit,
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

        Image(
            bitmap = image,
            contentDescription = stringResource(Res.string.device_screen),
            modifier = Modifier
                .size(
                    with(density) { drawnWidth.toDp() },
                    with(density) { drawnHeight.toDp() },
                )
                .pointerInput(image) {
                    // a tap and a swipe are the same gesture until the finger has moved past the
                    // touch slop, so one loop handles both: detectTapGestures on its own reported a
                    // drag as a tap wherever the finger happened to lift, which is a scroll turning
                    // into a click on whatever was under it
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var last = down
                        var isSwipe = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            last = change
                            if (!isSwipe &&
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                isSwipe = true
                            }
                            if (isSwipe) change.consume()
                            if (!change.pressed) break
                        }
                        val start = down.position.toDevice(image, scale)
                        val end = last.position.toDevice(image, scale)
                        if (isSwipe) {
                            // replayed over the time the finger actually took, so a flick reads as a
                            // flick; the ceiling stops a slow drag holding the connection open
                            val heldMs = (last.uptimeMillis - down.uptimeMillis).toInt()
                            onSwipe(start.x, start.y, end.x, end.y, heldMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS))
                        } else {
                            onTap(start.x, start.y)
                        }
                    }
                },
            contentScale = ContentScale.FillBounds,
        )
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

/** the three keys a phone has and a mirrored screen doesn't. */
@Composable
private fun MirrorButtons(onKey: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Dimens.gap),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap, Alignment.CenterHorizontally),
    ) {
        TextButton(onClick = { onKey(DeviceInput.KEY_BACK) }) { Text(stringResource(Res.string.key_back)) }
        TextButton(onClick = { onKey(DeviceInput.KEY_HOME) }) { Text(stringResource(Res.string.key_home)) }
        TextButton(onClick = { onKey(DeviceInput.KEY_APP_SWITCH) }) { Text(stringResource(Res.string.key_app_switch)) }
        TextButton(onClick = { onKey(DeviceInput.KEY_POWER) }) { Text(stringResource(Res.string.key_power)) }
    }
}
