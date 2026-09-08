package com.jpage4500.devicemanager.ui.dialog.common

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * an integer slider with tick marks, a haptic tick per step, and a red mark at the default value.
 *
 * Material's `Slider` jumps to wherever the track is tapped, which on a phone means a stray thumb
 * anywhere near the row throws the value away — so a press further than [TOUCH_RADIUS] from the
 * thumb is swallowed and only a real drag moves it. ported from DashboardWeatherKMP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSlider(
    startValue: Int,
    endValue: Int,
    value: Int,
    steps: Int = 0,
    defaultValue: Int? = null,
    primaryColor: Color? = null,
    innerCircleColor: Color = Color.White,
    onValueChangeFinished: ((Int) -> Unit)? = null,
    onValueChange: ((Int) -> Unit)? = null,
) {
    val accent = primaryColor ?: Colors.accent
    // internal state stays Float because Material's Slider needs one to drag smoothly
    var internalValue by remember(value) { mutableFloatStateOf(value.toFloat()) }

    val safeSteps = max(0, steps)
    var trackWidth by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var previousStep by remember { mutableIntStateOf(-1) }
    val valueRange = endValue.toFloat() - startValue.toFloat()

    val isDefaultSelected = defaultValue != null && internalValue.roundToInt() == defaultValue
    val currentInnerCircleColor = if (isDefaultSelected) Colors.settingValue else innerCircleColor

    fun updateHaptics(newValue: Float) {
        val progress = if (valueRange > 0f) ((newValue - startValue) / valueRange).coerceIn(0f, 1f) else 0f
        val selectedIndex = if (safeSteps > 0) {
            val totalPositions = safeSteps + 2
            (progress * (totalPositions - 1)).roundToInt().coerceIn(0, totalPositions - 1)
        } else {
            if (progress >= .5f) 1 else 0
        }
        if (selectedIndex != previousStep) {
            if (previousStep != -1) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            previousStep = selectedIndex
        }
    }

    LaunchedEffect(internalValue) { updateHaptics(internalValue) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(internalValue, trackWidth) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val downChange = event.changes.firstOrNull { it.changedToDown() }
                        if (downChange != null && trackWidth.width > 0) {
                            val touchX = downChange.position.x
                            val progress =
                                if (valueRange > 0f) ((internalValue - startValue) / valueRange).coerceIn(0f, 1f) else 0f
                            val trackStartPx = (size.width - trackWidth.width) / 2f
                            val thumbCenterPx = trackStartPx + (progress * trackWidth.width)
                            if (abs(touchX - thumbCenterPx) > TOUCH_RADIUS.toPx()) downChange.consume()
                        }
                    }
                }
            }
    ) {
        Slider(
            value = internalValue,
            onValueChange = { newValue ->
                internalValue = newValue
                onValueChange?.invoke(newValue.roundToInt())
                updateHaptics(newValue)
            },
            onValueChangeFinished = { onValueChangeFinished?.invoke(internalValue.roundToInt()) },
            valueRange = startValue.toFloat()..endValue.toFloat(),
            steps = safeSteps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Colors.divider,
            ),
            thumb = { SliderThumb(accent, currentInnerCircleColor) },
            track = { sliderState ->
                SliderTrack(
                    sliderState = sliderState,
                    trackWidth = trackWidth,
                    onTrackSized = { trackWidth = it },
                    density = density,
                    startValue = startValue,
                    endValue = endValue,
                    valueRange = valueRange,
                    steps = safeSteps,
                    primaryColor = accent,
                    defaultValue = defaultValue,
                )
            },
        )
    }
}

// how close a press has to land to count as grabbing the thumb rather than a stray touch
private val TOUCH_RADIUS = 24.dp
private val THUMB_SIZE = 25.dp
private val THUMB_INNER = 10.dp
private val TRACK_HEIGHT = 4.dp
private val TRACK_ROW_HEIGHT = 14.dp
private val TRACK_CAP = 8.dp

@Composable
private fun SliderThumb(primaryColor: Color, innerCircleColor: Color) {
    Box(
        modifier = Modifier.size(THUMB_SIZE).clip(CircleShape).background(primaryColor),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(THUMB_INNER).clip(CircleShape).background(innerCircleColor))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderTrack(
    sliderState: SliderState,
    trackWidth: IntSize,
    onTrackSized: (IntSize) -> Unit,
    density: Density,
    startValue: Int,
    endValue: Int,
    valueRange: Float,
    steps: Int,
    primaryColor: Color,
    defaultValue: Int?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(5.dp).height(TRACK_CAP).background(primaryColor))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(TRACK_ROW_HEIGHT)
                .onGloballyPositioned { onTrackSized(it.size) },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (trackWidth != IntSize.Zero) {
                val trackWidthDp = with(density) { trackWidth.width.toDp() }
                val progress =
                    if (valueRange > 0f) ((sliderState.value - startValue) / valueRange).coerceIn(0f, 1f) else 0f

                Box(Modifier.fillMaxWidth().height(TRACK_HEIGHT).background(Colors.divider))
                Box(Modifier.fillMaxWidth(progress).height(TRACK_HEIGHT).background(primaryColor))

                // past 20 ticks the marks are closer together than they are wide, and read as noise
                if (steps in 1..20) {
                    val totalSegments = steps + 1
                    for (step in 1..steps) {
                        val pos = step.toFloat() / totalSegments
                        Box(
                            Modifier
                                .offset(x = trackWidthDp * pos - 2.dp)
                                .size(4.dp, TRACK_CAP)
                                .background(Colors.secondaryText)
                        )
                    }
                }

                if (defaultValue != null && defaultValue in startValue..endValue) {
                    val defaultProgress = if (valueRange > 0f) (defaultValue - startValue).toFloat() / valueRange else 0f
                    Box(
                        Modifier
                            .offset(x = trackWidthDp * defaultProgress - 1.5.dp)
                            .size(3.dp, TRACK_ROW_HEIGHT)
                            .background(Colors.settingValue)
                    )
                }
            }
        }
        Box(modifier = Modifier.width(5.dp).height(TRACK_CAP).background(Colors.divider))
    }
}
