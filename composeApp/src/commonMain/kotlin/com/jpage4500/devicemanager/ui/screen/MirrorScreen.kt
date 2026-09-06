package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import kotlin.math.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlinx.coroutines.*

/**
 * a device's screen, with taps sent back to it.
 *
 * The frame is whatever `screencap` last returned, so a tap has to be mapped from where it landed in
 * the drawn image back to the device's own pixels — the image is scaled to fit and the device's
 * resolution is whatever the PNG says it is, not whatever this pane happens to be.
 */
@Composable
fun MirrorScreen(
    serial: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val source = remember(serial) { MirrorSource(serial) }
    val frameBytes by source.frame.collectAsState()
    val error by source.error.collectAsState()

    LaunchedEffect(serial) { source.run() }

    val image = remember(frameBytes) { frameBytes?.let { decodeImage(it) } }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                image != null -> MirrorImage(image) { x, y -> scope.launch { source.tap(x, y) } }
                error != null -> EmptyState("Can't mirror this device", error ?: "")
                else -> CircularProgressIndicator()
            }
        }
        MirrorButtons { key -> scope.launch { source.key(key) } }
    }
}

/**
 * the frame, and the arithmetic that turns a click on it into a tap on the device.
 *
 * The drawn size is worked out here rather than left to `ContentScale.Fit`, because the mapping back
 * needs the same numbers: without them a tap near the edge of a letterboxed image lands somewhere
 * else entirely on the phone.
 */
@Composable
private fun MirrorImage(image: ImageBitmap, onTap: (Int, Int) -> Unit) {
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
            contentDescription = "device screen",
            modifier = Modifier
                .size(
                    with(density) { drawnWidth.toDp() },
                    with(density) { drawnHeight.toDp() },
                )
                .pointerInput(image) {
                    detectTapGestures { offset ->
                        // back from drawn pixels to the device's own
                        val x = (offset.x / scale).toInt().coerceIn(0, image.width - 1)
                        val y = (offset.y / scale).toInt().coerceIn(0, image.height - 1)
                        onTap(x, y)
                    }
                },
            contentScale = ContentScale.FillBounds,
        )
    }
}

/** the three keys a phone has and a mirrored screen doesn't. */
@Composable
private fun MirrorButtons(onKey: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Dimens.gap),
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap, Alignment.CenterHorizontally),
    ) {
        TextButton(onClick = { onKey(DeviceInput.KEY_BACK) }) { Text("Back") }
        TextButton(onClick = { onKey(DeviceInput.KEY_HOME) }) { Text("Home") }
        TextButton(onClick = { onKey(DeviceInput.KEY_APP_SWITCH) }) { Text("Recents") }
        TextButton(onClick = { onKey(DeviceInput.KEY_POWER) }) { Text("Power") }
    }
}
