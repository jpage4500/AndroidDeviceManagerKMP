package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlin.math.*

/**
 * what a tap, a hold, a swipe or a key looks like *here*, while the device catches up.
 *
 * A frame arrives every 250ms at best and the device takes longer than that to react, so without
 * this a tap is a second of nothing happening — and the natural response to that is to tap again,
 * which is what a run of identical taps in the logs is. Ported from the Swing app's
 * `utils/Animations.java`: the same mint, the same warmer orange for a hold, the same timings, at
 * roughly twice the size, because this is read at arm's length over a screenshot of anything.
 *
 * Coordinates are in the *drawn* image, not the device's: this is drawn over the frame, at the
 * place the finger actually went.
 */
@Stable
class MirrorFeedback {
    internal val active = mutableStateListOf<Feedback>()

    /** the frame clock, read by the overlay's draw so it repaints while anything is running. */
    internal var frameMs by mutableLongStateOf(0L)

    fun tap(at: Offset) = add(Feedback.Tap(at))

    fun longPress(at: Offset) = add(Feedback.Hold(at))

    fun swipe(from: Offset, to: Offset) = add(Feedback.Swipe(from, to))

    /** a key from the button row, named the way the button names it. */
    fun key(label: String) = add(Feedback.Key(label))

    private fun add(feedback: Feedback) {
        // someone pressing fast is asking to see the last few, not all of them
        while (active.size >= MAX_ACTIVE) active.removeAt(0)
        active.add(feedback)
    }
}

@Composable
fun rememberMirrorFeedback(): MirrorFeedback = remember { MirrorFeedback() }

/**
 * the overlay itself, sized to the drawn frame.
 *
 * The frame loop runs only while something is animating and stops when nothing is — the same thing
 * the Swing window's 30ms `Timer` does, except the clock is Compose's, so it idles with the window.
 */
@Composable
fun MirrorFeedbackOverlay(
    feedback: MirrorFeedback,
    modifier: Modifier = Modifier,
    /**
     * where a point on the frame ends up on screen — identity until the frame is zoomed.
     *
     * The overlay is drawn *outside* whatever transform the frame is under, and the marks are moved
     * onto it instead: a ring is a mark on the glass rather than on the picture, and one drawn
     * inside the transform would be four times the size at four times the zoom. Called from the
     * draw, so a pinch repaints the marks without recomposing anything.
     */
    transform: (Offset) -> Offset = { it },
) {
    if (feedback.active.isNotEmpty()) {
        LaunchedEffect(feedback) {
            while (feedback.active.isNotEmpty()) {
                // the frame callback is where a new one starts its clock, **not** the draw. a draw
                // can happen before the loop's first frame — and [frameMs] is 0 until then, while a
                // real frame time is milliseconds-since-boot — so seeding from the draw made every
                // animation start out already expired and vanish in one invisible frame
                withFrameMillis { frame ->
                    feedback.frameMs = frame
                    feedback.active.forEach { it.startAt(frame) }
                }
                feedback.active.removeAll { it.isFinishedAt(feedback.frameMs) }
            }
        }
    }
    val measurer = rememberTextMeasurer()
    // read here, in composition, and handed to the canvas below: every frame is then a new draw
    // lambda and a repaint that follows from it, rather than one riding on the draw phase noticing
    // a state read of its own
    val now = feedback.frameMs
    Canvas(modifier) {
        feedback.active.forEach { item ->
            // null until the loop's next frame has started it, which is a frame away at most
            val elapsed = item.elapsedAt(now) ?: return@forEach
            when (item) {
                is Feedback.Tap -> drawTap(transform(item.at), elapsed)
                is Feedback.Hold -> drawHold(transform(item.at), elapsed)
                is Feedback.Swipe -> drawSwipe(transform(item.from), transform(item.to), elapsed)
                // the bubble has no place on the frame: it centres itself on whatever it is drawn in
                is Feedback.Key -> drawKey(item.label, elapsed, measurer)
            }
        }
    }
}

/**
 * one thing to draw, timed from the first *frame* after it was added.
 *
 * The frame clock rather than a wall clock, so that everything on screen is timed by the same thing
 * that repaints it. [startAt] is called from the frame loop and nowhere else — see the loop for what
 * happens when a draw seeds it instead.
 */
internal sealed class Feedback(private val durationMs: Int) {
    private var startMs = UNSTARTED

    class Tap(val at: Offset) : Feedback(TAP_GROW_MS + TAP_HOLD_MS + TAP_FADE_MS)
    class Hold(val at: Offset) : Feedback(HOLD_GROW_MS + HOLD_HOLD_MS + HOLD_FADE_MS)
    class Swipe(val from: Offset, val to: Offset) :
        Feedback(SWIPE_GROW_MS + SWIPE_HOLD_MS + SWIPE_FADE_MS)

    class Key(val label: String) : Feedback(KEY_HOLD_MS + KEY_FADE_MS)

    /** start the clock, on the first frame this is seen by the loop. does nothing after that. */
    fun startAt(frameMs: Long) {
        if (startMs == UNSTARTED) startMs = frameMs
    }

    /** null until it has started, which is what says there is nothing to draw yet. */
    fun elapsedAt(frameMs: Long): Long? =
        if (startMs == UNSTARTED) null else frameMs - startMs

    /** never finished before it has started, however long it has been in the list. */
    fun isFinishedAt(frameMs: Long): Boolean =
        startMs != UNSTARTED && frameMs - startMs >= durationMs

    private companion object {
        const val UNSTARTED = -1L
    }
}

/** an expanding ring with a glow behind it, and an inner dot that pulses and shrinks away. */
private fun DrawScope.drawTap(center: Offset, elapsed: Long) {
    val alpha = fadeAlpha(elapsed, TAP_GROW_MS + TAP_HOLD_MS, TAP_FADE_MS)
    if (alpha <= 0f) return
    val grow = min(1f, elapsed / TAP_GROW_MS.toFloat())
    val outer = Dimens.mirrorRing.toPx() * grow
    if (outer <= 0f) return
    drawRing(Colors.mirrorGlow, Colors.mirrorInk, center, outer, alpha)
    // the dot grows with the ring, holds, then shrinks as the whole thing fades
    val pulse = when {
        elapsed < TAP_GROW_MS -> grow
        elapsed < TAP_GROW_MS + TAP_HOLD_MS -> 1f
        else -> 1f - ((elapsed - TAP_GROW_MS - TAP_HOLD_MS) / TAP_FADE_MS.toFloat()) * 0.3f
    }
    val inner = max(Dimens.mirrorRingInner.toPx(), outer * 0.4f)
    drawCircle(Colors.mirrorInk, inner * pulse, center, alpha * INNER_ALPHA)
}

/**
 * the same ring, bigger, warmer and slower, with the middle filled in.
 *
 * Different in every way a glance can pick up, because it is drawn at the moment a press *becomes*
 * a hold — while the finger is still down — and the whole point of it is telling someone they can
 * let go now.
 */
private fun DrawScope.drawHold(center: Offset, elapsed: Long) {
    val alpha = fadeAlpha(elapsed, HOLD_GROW_MS + HOLD_HOLD_MS, HOLD_FADE_MS)
    if (alpha <= 0f) return
    val grow = min(1f, elapsed / HOLD_GROW_MS.toFloat())
    val outer = Dimens.mirrorHoldRing.toPx() * grow
    if (outer <= 0f) return
    drawRing(Colors.mirrorHoldGlow, Colors.mirrorHoldInk, center, outer, alpha)
    // inside the ring rather than under it, so the outline still reads
    drawCircle(Colors.mirrorHoldFill, outer * 0.65f, center, alpha * HOLD_FILL_ALPHA)
}

/** an arrow drawn from where the finger went down to where it lifted, growing along its own path. */
private fun DrawScope.drawSwipe(from: Offset, to: Offset, elapsed: Long) {
    val alpha = fadeAlpha(elapsed, SWIPE_GROW_MS + SWIPE_HOLD_MS, SWIPE_FADE_MS)
    if (alpha <= 0f) return
    val delta = to - from
    val length = delta.getDistance()
    if (length < 2f) return

    val grow = min(1f, elapsed / SWIPE_GROW_MS.toFloat())
    val drawn = length * grow
    val head = min(Dimens.mirrorArrowHead.toPx(), drawn)
    val unit = delta / length
    val tip = from + unit * drawn
    val back = tip - unit * head

    val shaft = drawn - head
    if (shaft > 0f) {
        val lineEnd = from + unit * shaft
        drawLine(Colors.mirrorGlow, from, lineEnd, Dimens.mirrorGlowStroke.toPx(), StrokeCap.Round, alpha = alpha)
        drawLine(Colors.mirrorInk, from, lineEnd, Dimens.mirrorStroke.toPx(), StrokeCap.Round, alpha = alpha)
    }

    // the head, as a triangle across the direction of travel
    val across = Offset(-unit.y, unit.x) * (Dimens.mirrorArrowWidth.toPx() / 2f)
    val arrow = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + across.x, back.y + across.y)
        lineTo(back.x - across.x, back.y - across.y)
        close()
    }
    drawPath(arrow, Colors.mirrorInk, alpha)

    // a dot left at the start, fading as the arrow leaves it — what says which end it started from
    val startAlpha = (1f - grow) * alpha
    if (startAlpha > 0.05f) {
        drawCircle(Colors.mirrorInk, Dimens.mirrorSwipeDot.toPx(), from, startAlpha)
    }
}

/**
 * a bubble in the middle of the frame naming the key that was pressed.
 *
 * In the middle because a key has nowhere else to be: Back and Home happen to the whole screen, not
 * to a point on it. Sized off the frame's width, so it is the same fraction of a phone in the hand
 * as of a mirror window on a desk.
 */
private fun DrawScope.drawKey(label: String, elapsed: Long, measurer: TextMeasurer) {
    val alpha = fadeAlpha(elapsed, KEY_HOLD_MS, KEY_FADE_MS)
    if (alpha <= 0f) return
    val fontSize = max(Dimens.mirrorKeyTextMin.toPx(), size.width / 9f).toSp()
    val text = measurer.measure(label, TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold))

    val pad = Dimens.mirrorKeyPadding.toPx()
    val bubble = Size(text.size.width + pad * 2, text.size.height + pad)
    val at = Offset((size.width - bubble.width) / 2f, (size.height - bubble.height) / 2f)
    val corner = CornerRadius(Dimens.mirrorKeyCorner.toPx())

    val glow = Dimens.mirrorKeyGlow.toPx()
    drawRoundRect(
        color = Colors.mirrorGlow,
        topLeft = Offset(at.x - glow, at.y - glow),
        size = Size(bubble.width + glow * 2, bubble.height + glow * 2),
        cornerRadius = corner,
        alpha = alpha * GLOW_ALPHA,
    )
    drawRoundRect(
        color = Colors.mirrorInk,
        topLeft = at,
        size = bubble,
        cornerRadius = corner,
        alpha = alpha * BUBBLE_ALPHA,
    )
    // black on mint, as the Swing bubble is: the label has to read over whatever is behind it
    drawText(text, Color.Black, Offset(at.x + pad, at.y + pad / 2f), alpha = alpha)
}

/** the glow ring and the ring itself, which is every one of these but the arrow. */
private fun DrawScope.drawRing(glow: Color, ink: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        color = glow,
        radius = radius,
        center = center,
        alpha = alpha,
        style = Stroke(Dimens.mirrorGlowStroke.toPx()),
    )
    drawCircle(
        color = ink,
        radius = radius,
        center = center,
        alpha = alpha,
        style = Stroke(Dimens.mirrorStroke.toPx()),
    )
}

/** solid until [holdMs] has passed, then a straight fade over [fadeMs]. */
private fun fadeAlpha(elapsed: Long, holdMs: Int, fadeMs: Int): Float =
    if (elapsed < holdMs) 1f
    else (1f - (elapsed - holdMs) / fadeMs.toFloat()).coerceIn(0f, 1f)

// the Swing window's own timings, in ms — Animations.java
private const val TAP_GROW_MS = 200
// the Swing tap holds for 50ms; this one lingers, because a mirrored screen is watched rather than
// operated and the ring is the only thing saying the tap was sent at all
private const val TAP_HOLD_MS = 350
private const val TAP_FADE_MS = 350
private const val HOLD_GROW_MS = 400
private const val HOLD_HOLD_MS = 400
private const val HOLD_FADE_MS = 600
private const val SWIPE_GROW_MS = 250
private const val SWIPE_HOLD_MS = 700
private const val SWIPE_FADE_MS = 350

// the key bubble holds before it goes, where the Swing one fades from its first frame: a label
// that starts disappearing as it appears is one nobody finishes reading
private const val KEY_HOLD_MS = 300
private const val KEY_FADE_MS = 400

/** the tap's inner dot, at the Swing app's 150 of 255; the hold's fill at its 160. */
private const val INNER_ALPHA = 0.6f
private const val HOLD_FILL_ALPHA = 0.63f

/** the key bubble, at the Swing app's 195 of 255, over a glow at its 0.55. */
private const val BUBBLE_ALPHA = 0.76f
private const val GLOW_ALPHA = 0.55f

private const val MAX_ACTIVE = 8
