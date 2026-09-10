package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import kotlin.math.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.ui.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * a device's screen, with taps, holds, swipes and keys sent back to it.
 *
 * A gesture has to be mapped from where it landed in the drawn image back to the device's own
 * pixels: the image is scaled to fit this pane, and the device's resolution is what the frame says
 * it is — which is not always the image's own size, since a server sending a scaled-down frame says
 * so in the header rather than in the JPEG. See [ScreenFrame].
 */
@Composable
fun MirrorScreen(
    deviceId: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val source = remember(deviceId) { MirrorSource(deviceId) }
    val frame by source.frame.collectAsState()
    val error by source.error.collectAsState()
    val fps by source.fps.collectAsState()
    val quality by source.quality.collectAsState()
    val streamed by source.streamed.collectAsState()
    // held here rather than inside [MirrorImage]: a key press comes from the button row below it
    val feedback = rememberMirrorFeedback()
    var typing by remember { mutableStateOf(false) }
    // survives a frame that fails to decode and the empty state that replaces it, which is why it
    // is held here rather than inside [MirrorImage]
    val zoom = remember(deviceId) { MirrorZoom() }
    // unbounded and never closed, so a keystroke is never dropped and never waits on the device to
    // answer the one before it — the send loop is [sendTo], and it is the only thing reading this
    val keystrokes = remember { Channel<Keystroke>(Channel.UNLIMITED) }

    LaunchedEffect(source) { keystrokes.sendTo(source) }

    // a mirror left running in the background is a frame every 250ms nobody can see — and on a
    // metered link, rude. it picks up again on the way back — see AppLifecycle
    LaunchedEffect(deviceId) { whileForeground { source.run() } }

    val image = remember(frame) { frame?.image?.let { decodeImage(it) } }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                image != null -> MirrorImage(
                    image = image,
                    device = frame.deviceSize(image),
                    feedback = feedback,
                    zoom = zoom,
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
        MirrorBar(
            fps = fps,
            quality = quality,
            // a polled mirror is whatever `screencap` hands back, at the size it hands it back
            canSetQuality = streamed,
            zoom = zoom,
            typing = typing,
            onTyping = { typing = !typing },
            onKey = { key, label ->
                scope.launch { source.key(key) }
                feedback.key(label)
            },
            // down the same queue as the letters around it, so a delete lands where it was typed
            onStroke = { stroke ->
                keystrokes.trySend(stroke)
                feedback.key(stroke.label)
            },
            onQuality = { scope.launch { source.setQuality(it) } },
        )
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
    device: IntSize,
    feedback: MirrorFeedback,
    zoom: MirrorZoom,
    onTap: (x: Int, y: Int) -> Unit,
    onLongPress: (x: Int, y: Int) -> Unit,
    onSwipe: (x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) -> Unit,
) {
    // clipped, or a zoomed frame paints over the title bar and the buttons under it
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // explicit Floats throughout: compose has minOf/div overloads for Size and ScaleFactor,
        // and letting either of those win here silently changes what the tap maths is measuring
        val boxWidth: Float = with(density) { maxWidth.toPx() }
        val boxHeight: Float = with(density) { maxHeight.toPx() }
        val scale: Float = min(boxWidth / image.width.toFloat(), boxHeight / image.height.toFloat())
        val drawn = Size(image.width * scale, image.height * scale)
        val view = Size(boxWidth, boxHeight)

        // the numbers the pan is bounded by. they move when the device turns over, and on a phone
        // when the keyboard opens — a pan that was legal before is pulled back inside here
        LaunchedEffect(drawn, view) { zoom.measured(drawn, view) }

        // the gesture goes on the box rather than on the image, so the feedback drawn over the top
        // of it is never in the way of the next tap
        Box(
            Modifier
                .size(
                    with(density) { drawn.width.toDp() },
                    with(density) { drawn.height.toDp() },
                )
                // read in the layer's own block and not in composition: a pinch moves these sixty
                // times a second, and every one of those would otherwise recompose the screen
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
                // the frame's own edge. a screenshot of a white app on a light window has none, and
                // without one there is nothing to say where the device's screen stops
                .border(Dimens.mirrorBorder, Colors.divider)
                // keyed on the two things the maths below reads, and **not** on the frame: a new
                // bitmap arrives several times a second, and restarting the gesture detector on one
                // is a drag chopped into whatever pieces the frames happened to fall between
                .pointerInput(drawn, device) {
                    awaitEachGesture {
                        detectMirrorGesture(
                            zoom = zoom,
                            onTap = { at ->
                                val point = at.toDevice(drawn, device)
                                onTap(point.x, point.y)
                                feedback.tap(at)
                            },
                            onLongPress = { at ->
                                val point = at.toDevice(drawn, device)
                                onLongPress(point.x, point.y)
                                feedback.longPress(at)
                            },
                            onSwipe = { from, to, heldMs ->
                                val start = from.toDevice(drawn, device)
                                val end = to.toDevice(drawn, device)
                                onSwipe(
                                    start.x, start.y, end.x, end.y,
                                    heldMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS),
                                )
                                feedback.swipe(from, to)
                            },
                        )
                    }
                }
                // a second handler, because a scroll never begins with a finger going down and the
                // loop above waits for one
                .pointerInput(zoom) { mirrorScroll(zoom) },
        ) {
            Image(
                bitmap = image,
                contentDescription = stringResource(Res.string.device_screen),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
        // over the frame, not in it: the device won't show the tap for a frame or two yet — and
        // outside its transform, so a ring stays the size of a fingertip at every zoom
        MirrorFeedbackOverlay(feedback, Modifier.matchParentSize(), zoom::onView)
    }
}

/**
 * one finger, told apart into a tap, a hold or a swipe — and two fingers, which move the view.
 *
 * The single-finger three come out of one loop, because that is what they are: `detectTapGestures`
 * on its own reported a drag as a tap wherever the finger happened to lift, which is a scroll
 * turning into a click on whatever was under it.
 *
 * **The hold fires while the finger is still down**, the way the Swing window's 500ms timer does —
 * feedback that waits for the finger to lift is feedback nobody waits for, and the point of it is
 * saying "you can let go now". A pointer scope reports that timeout by throwing rather than by
 * returning, which is why the first phase is wrapped; it is the shape `detectTapGestures` uses.
 *
 * **A second finger hands the whole gesture to the view and never gives it back.** Nothing has been
 * sent to the device until the finger lifts, so a tap or a swipe that turns into a pinch costs
 * nothing to call off — and pinching *on* the device is not something being given up, since `input`
 * has no multi-touch to send. The one that can't be taken back is a hold, which has already gone by
 * the time a pinch started more than [LONG_PRESS_MS] apart becomes one; a pinch that slow is rare
 * enough to live with. Letting go of one finger doesn't hand it back either — that would send the
 * remains of a pinch to the device as a swipe.
 *
 * **While the frame is zoomed in, one finger moves the view too.** A drag then pans instead of
 * reaching the device: at 4x it could only ever cross a quarter of the screen, and what it was
 * reaching for is usually the part that has been panned off it. Taps and holds still go through —
 * it is the swipe that is being spent, and zooming back out hands it straight back.
 */
private suspend fun AwaitPointerEventScope.detectMirrorGesture(
    zoom: MirrorZoom,
    onTap: (at: Offset) -> Unit,
    onLongPress: (at: Offset) -> Unit,
    onSwipe: (from: Offset, to: Offset, heldMs: Int) -> Unit,
) {
    val down = awaitFirstDown()
    var last = down
    var isSwipe = false
    var isHold = false
    var isUp = false
    var isZoom = false
    var isPan = false

    /** @return false once the gesture is over. */
    fun track(event: PointerEvent): Boolean {
        if (isZoom || event.changes.count { it.pressed } > 1) {
            isZoom = true
            zoom.transform(event.calculateCentroid(), event.calculateZoom(), event.calculatePan())
            event.changes.forEach { it.consume() }
            return event.changes.any { it.pressed }
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        last = change
        // against the screen and not the frame: the frame's own pixels are [MirrorZoom.scale] times
        // smaller than they look, so at 4x a swipe would start on a quarter of the movement
        val moved = (change.position - down.position).getDistance() * zoom.scale
        // a drag on a zoomed frame is the view's, not the device's — see the note above
        if (!isSwipe && moved > viewConfiguration.touchSlop) {
            isSwipe = true
            isPan = zoom.zoomed
        }
        // the delta comes in the frame's own pixels, which are [MirrorZoom.scale] times smaller
        // than the ones the offset is in
        if (isPan) zoom.panBy(change.positionChange() * zoom.scale)
        // a swipe or a hold owns the gesture from here; nothing under it should act on it as well
        if (isSwipe || isHold) change.consume()
        return change.pressed
    }

    // phase one: the hold, which a lift, a second finger or any real movement calls off
    try {
        withTimeout(LONG_PRESS_MS.toLong()) {
            while (!isUp && !isSwipe && !isZoom) {
                if (!track(awaitPointerEvent())) isUp = true
            }
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        isHold = true
        onLongPress(down.position)
    }

    // phase two: the rest of it, with the clock off
    while (!isUp) {
        if (!track(awaitPointerEvent())) isUp = true
    }

    when {
        // it belonged to the view; the device was never told any of it
        isZoom || isPan -> Unit
        // the hold was reported the moment it happened; what the finger did after it is nothing
        isHold -> Unit
        // replayed over the time the finger actually took, so a flick reads as a flick; the ceiling
        // stops a slow drag from holding the connection open
        isSwipe -> onSwipe(down.position, last.position, (last.uptimeMillis - down.uptimeMillis).toInt())
        else -> onTap(down.position)
    }
}

/**
 * the desktop's pinch, which is a wheel.
 *
 * A mouse has no second finger and a trackpad's gesture arrives as a scroll, so the modifier is what
 * says zoom: **ctrl or cmd**, both of them, because ctrl-scroll on macOS is the system's own screen
 * zoom and the one nobody there will press. Plain scrolling pans, but only while there is something
 * to pan — at fit size the event is left unconsumed, so the wheel is still free to become what the
 * Swing window does with it and send the device a swipe.
 */
private suspend fun PointerInputScope.mirrorScroll(zoom: MirrorZoom) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull { it.scrollDelta != Offset.Zero } ?: continue
            val modifiers = event.keyboardModifiers
            when {
                modifiers.isCtrlPressed || modifiers.isMetaPressed -> {
                    // a notch is 1.0, and away from the user is negative — which is towards the
                    // screen, which is in
                    zoom.zoomAt(change.position, 1f - change.scrollDelta.y * SCROLL_ZOOM)
                    change.consume()
                }
                zoom.zoomed -> {
                    zoom.panBy(-change.scrollDelta * SCROLL_PAN)
                    change.consume()
                }
            }
        }
    }
}

/**
 * how much of the frame is being looked at, and which part of it.
 *
 * Client-side and nothing to do with the device: it is the picture that is being scaled, so this
 * works the same over every transport and asks nobody for anything.
 *
 * The scale is about the **centre**, which is where a `graphicsLayer` puts it, and the offset is in
 * the parent's pixels — so a point `p` on the frame is drawn at `centre + (p - frameCentre) × scale
 * + offset`, which is [onView] and the arithmetic every method here is doing. Held as state, and
 * deliberately **not** part of the gesture handler's `pointerInput` key: a key that changed with the
 * zoom would tear the detector down in the middle of the pinch that changed it.
 */
@Stable
private class MirrorZoom {
    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > MIN_SCALE

    // the letterboxed frame, and the space it is letterboxed into. plain fields: they change with
    // the layout, which recomposes everything that reads them anyway
    private var frame = Size.Zero
    private var view = Size.Zero

    /** the sizes the pan is bounded by, whenever the layout changes them. */
    fun measured(frame: Size, view: Size) {
        this.frame = frame
        this.view = view
        offset = bounded(offset, scale)
    }

    /**
     * one step of a pinch: [by] times bigger about [centroid], then moved by [pan].
     *
     * The point under the fingers is the one that stays put, which is what makes a pinch feel like
     * it is holding the picture rather than driving it. [centroid] and [pan] are in the frame's own
     * pixels, as the gesture reports them, so the pan is scaled on its way into the offset.
     */
    fun transform(centroid: Offset, by: Float, pan: Offset) {
        // the event that lifts the last finger reports no centroid at all, and the NaN in it would
        // go through this arithmetic into the offset and leave the frame somewhere nobody can pan
        // it back from
        if (centroid.isUnspecified) return
        val next = (scale * by).coerceIn(MIN_SCALE, MAX_SCALE)
        val held = (centroid - Offset(frame.width / 2f, frame.height / 2f)) * (scale - next)
        scale = next
        offset = bounded(offset + held + pan * next, next)
    }

    /** the wheel's zoom, about the pointer rather than about two fingers. */
    fun zoomAt(point: Offset, by: Float) = transform(point, by, Offset.Zero)

    /** and its pan, which is already in the parent's pixels. */
    fun panBy(screen: Offset) {
        offset = bounded(offset + screen, scale)
    }

    fun reset() {
        scale = MIN_SCALE
        offset = Offset.Zero
    }

    /** where a point on the frame is drawn — the marks over the top are the ones that need this. */
    fun onView(at: Offset): Offset =
        Offset(view.width / 2f, view.height / 2f) +
            (at - Offset(frame.width / 2f, frame.height / 2f)) * scale +
            offset

    /**
     * as far as the frame can be moved and still cover the view.
     *
     * At fit size this is zero on both axes, so letting go of a zoom puts the picture back in the
     * middle by itself — and in the letterboxed axis it stays zero until the zoom is enough to fill
     * it, so nothing can be dragged into the empty band beside the screen.
     */
    private fun bounded(at: Offset, scale: Float): Offset {
        val x = ((frame.width * scale - view.width) / 2f).coerceAtLeast(0f)
        val y = ((frame.height * scale - view.height) / 2f).coerceAtLeast(0f)
        return Offset(at.x.coerceIn(-x, x), at.y.coerceIn(-y, y))
    }
}

/** fit, and as far in as a device pixel is still worth aiming at. */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f

/** a wheel notch: a tenth bigger, or this many pixels across. */
private const val SCROLL_ZOOM = 0.1f
private const val SCROLL_PAN = 40f

/**
 * back from where a finger landed in the drawn image to the device's own pixels.
 *
 * Against [device] rather than against the bitmap: the drawn image covers the whole screen whatever
 * size it arrived at, so a frame the server scaled down still has to answer in device coordinates.
 */
private fun Offset.toDevice(drawn: Size, device: IntSize): IntOffset = IntOffset(
    (x / drawn.width * device.width).toInt().coerceIn(0, device.width - 1),
    (y / drawn.height * device.height).toInt().coerceIn(0, device.height - 1),
)

/**
 * what this frame says the device's screen measures, falling back to the image's own size.
 *
 * A polled `screencap` PNG *is* the screen, so it is its own measure; a pushed frame carries the
 * device's dimensions in its header and may itself be smaller than them.
 */
private fun ScreenFrame?.deviceSize(image: ImageBitmap): IntSize = IntSize(
    this?.deviceWidth?.takeIf { it > 0 } ?: image.width,
    this?.deviceHeight?.takeIf { it > 0 } ?: image.height,
)

// `input swipe 0` is ignored by some devices, and a minute-long drag would hold the shell that long
private const val MIN_SWIPE_MS = 50
private const val MAX_SWIPE_MS = 2000

/** how long a press has to be held to become one — the Swing window's own threshold. */
private const val LONG_PRESS_MS = 500

/**
 * the bar under the screen: the keys a mirrored device doesn't have, what it is costing, and
 * everything else behind one button.
 *
 * The three navigation keys as icons, because they are the three every Android device draws as
 * icons — a row of blue words was the widest thing on the screen and named what it did twice, once
 * on the button and again in the bubble the press raises. Power is inside [MirrorMore] instead: it
 * is the one key here that turns the screen being watched off, and a thumb reaching for Recents
 * shouldn't be one target away from it.
 */
@Composable
private fun MirrorBar(
    fps: Int,
    quality: ScreenQuality,
    canSetQuality: Boolean,
    zoom: MirrorZoom,
    typing: Boolean,
    onTyping: () -> Unit,
    onKey: (keyCode: Int, label: String) -> Unit,
    onStroke: (Keystroke) -> Unit,
    onQuality: (ScreenQuality) -> Unit,
) {
    HorizontalDivider(color = Colors.divider)
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.mirrorBarHeight)
            .background(Colors.statusBarBg),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(Dimens.mirrorBarSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MirrorKey(Res.drawable.back, Res.string.key_back, DeviceInput.KEY_BACK, onKey)
            MirrorKey(Res.drawable.home, Res.string.key_home, DeviceInput.KEY_HOME, onKey)
            MirrorKeyboard(typing, onTyping, onStroke)
            MirrorMore(quality, canSetQuality, zoom, onKey, onQuality)
        }
        // out of the way of the keys rather than under the frame: over the screen it would sit on
        // top of whatever the device is showing, and this is a number to glance at, not to read
        Text(
            text = stringResource(Res.string.mirror_fps, fps),
            style = MaterialTheme.typography.bodySmall,
            fontSize = Dimens.mirrorFpsSize,
            color = Colors.secondaryText,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = Dimens.gapLarge),
        )
    }
}

/** the label goes back up with the key code: it is what the feedback bubble says. */
@Composable
private fun MirrorKey(
    icon: DrawableResource,
    text: StringResource,
    keyCode: Int,
    onKey: (Int, String) -> Unit,
) {
    val label = stringResource(text)
    MirrorBarButton(icon, label) { onKey(keyCode, label) }
}

@Composable
private fun MirrorBarButton(
    icon: DrawableResource,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Dimens.mirrorBarButton)
            // clipped first, so the press ripples as a circle rather than as the square it is in
            .clip(CircleShape)
            // filled while it is on — the keyboard is the one button here with a state, and a tint
            // that only changes shade doesn't say which
            .background(if (active) Colors.accent.copy(alpha = Dimens.backgroundAlpha) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            res = icon,
            size = Dimens.mirrorBarIcon,
            tint = Colors.accent,
            contentDescription = label,
        )
    }
}

/**
 * everything the bar has no room for: Recents, Power, and how much picture to ask for.
 *
 * Recents is in here rather than on the bar because of what sits next to it: it is the least-pressed
 * of the three navigation keys, and the bar is where the keyboard now goes.
 *
 * The menu is composed **inside the button**, because [AppPopupMenu] is placed against whatever it
 * sits in — and from the foot of a window it opens upwards of its own accord.
 */
@Composable
private fun MirrorMore(
    quality: ScreenQuality,
    canSetQuality: Boolean,
    zoom: MirrorZoom,
    onKey: (keyCode: Int, label: String) -> Unit,
    onQuality: (ScreenQuality) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val recents = stringResource(Res.string.key_app_switch)
    val power = stringResource(Res.string.key_power)
    Box {
        MirrorBarButton(Res.drawable.more, stringResource(Res.string.mirror_more)) { open = true }
        AppPopupMenu(expanded = open, onDismissRequest = { open = false }) {
            Item(
                text = recents,
                leading = { color -> AppIcon(Res.drawable.recent, size = Dimens.icon, tint = color) },
            ) { onKey(DeviceInput.KEY_APP_SWITCH, recents) }
            Item(
                text = power,
                leading = { color -> AppIcon(Res.drawable.power, size = Dimens.icon, tint = color) },
            ) { onKey(DeviceInput.KEY_POWER, power) }
            Separator()
            // the zoom is read here rather than in the bar: this runs only while the menu is open,
            // and a read up there would recompose the screen on every frame of a pinch
            Item(
                text = stringResource(Res.string.mirror_reset_zoom),
                enabled = zoom.zoomed,
                leading = { color -> AppIcon(Res.drawable.size, size = Dimens.icon, tint = color) },
            ) { zoom.reset() }
            Separator()
            Header(stringResource(Res.string.mirror_quality))
            // disabled rather than absent while polling: the levels are a thing this mirror has,
            // and there is nothing to ask for them on this route — see MirrorSource
            ScreenQuality.entries.forEach { level ->
                Item(
                    text = stringResource(level.label),
                    enabled = canSetQuality,
                    leading = { color -> QualityTick(on = level == quality, color = color) },
                ) { onQuality(level) }
            }
        }
    }
}

/**
 * the keyboard of the device in your hand, typing onto the one on the screen.
 *
 * **The field is a dp of nothing.** A focus target has to be laid out to take focus, and there is
 * nothing worth showing: what is being typed is already on the screen above — the device draws it a
 * frame or two later, and [MirrorFeedback] puts each key up the moment it is pressed, which is the
 * same thing the Swing window does with a physical keyboard.
 *
 * **It holds what the IME thinks has been typed** rather than being emptied after each keystroke. A
 * field that is always empty reports a backspace as no change at all, and the device never sees it;
 * with the text still there, what the field lost is what to delete. Everything up to the first
 * difference is what the two agree on — so an autocorrect that rewrites a word comes out as the
 * deletes and the letters that turn one into the other.
 *
 * Enter is the IME's action key, because `input text` can't carry a newline and a single-line field
 * is what turns Return into something to send rather than something to insert.
 */
@Composable
private fun MirrorKeyboard(active: Boolean, onToggle: () -> Unit, onStroke: (Keystroke) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val delete = stringResource(Res.string.input_delete)
    val enter = stringResource(Res.string.input_enter)

    LaunchedEffect(active) {
        // what the last session typed is nothing to delete now: the device has moved on
        typed = ""
        if (active) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            // a desktop has no keyboard to hide, and a field left focused there goes on taking
            // every key pressed anywhere in the window
            focusManager.clearFocus()
        }
    }

    Box(contentAlignment = Alignment.Center) {
        BasicTextField(
            value = typed,
            onValueChange = { next ->
                strokes(typed, next, delete, enter).forEach(onStroke)
                typed = next
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { onStroke(Keystroke.Pressed(DeviceInput.KEY_ENTER, enter)) },
            ),
            modifier = Modifier
                .size(Dimens.mirrorTypeField)
                .alpha(0f)
                .focusRequester(focus),
        )
        MirrorBarButton(
            icon = Res.drawable.keyboard,
            label = stringResource(Res.string.mirror_keyboard),
            active = active,
            onClick = onToggle,
        )
    }
}

/** what the field lost and gained, as what to send: the deletes first, then the letters. */
private fun strokes(
    old: String,
    new: String,
    deleteLabel: String,
    enterLabel: String,
): List<Keystroke> {
    val shared = old.commonPrefixWith(new).length
    return buildList {
        repeat(old.length - shared) { add(Keystroke.Pressed(DeviceInput.KEY_DEL, deleteLabel)) }
        // a paste arrives as one change and can carry newlines, which `input text` can't: each one
        // is the enter key with the rest of the line after it
        new.substring(shared).split('\n').forEachIndexed { index, line ->
            if (index > 0) add(Keystroke.Pressed(DeviceInput.KEY_ENTER, enterLabel))
            if (line.isNotEmpty()) add(Keystroke.Typed(line))
        }
    }
}

/** one keystroke on its way to the device, and what the bubble over the frame calls it. */
private sealed interface Keystroke {
    /** drawn where a tap's ring would be, so a key that went is a key you can see went. */
    val label: String

    /** printable characters, which go as one `input text`. */
    data class Typed(val text: String) : Keystroke {
        override val label: String get() = text
    }

    /** and everything the IME reports as an action instead — delete, enter. */
    data class Pressed(val keyCode: Int, override val label: String) : Keystroke
}

/**
 * keystrokes to the device, in the order they were typed and a gap's worth at a time.
 *
 * **One consumer, because the order is the whole point.** Every send suspends on its way to the
 * device, so two coroutines racing would put "ab" on the screen as "ba" often enough to notice.
 *
 * Letters wait [TYPE_BATCH_MS] for the next one and go as a single `input text` — on the polled
 * route that is one shell command instead of one per character, and the Swing window batches on the
 * same timer for the same reason. A key that isn't a letter goes *after* whatever was waiting,
 * never ahead of it.
 */
private suspend fun ReceiveChannel<Keystroke>.sendTo(source: MirrorSource) {
    val batch = StringBuilder()
    for (first in this) {
        var stroke: Keystroke? = first
        while (stroke is Keystroke.Typed) {
            batch.append(stroke.text)
            stroke = withTimeoutOrNull(TYPE_BATCH_MS) { receive() }
        }
        if (batch.isNotEmpty()) {
            source.text(batch.toString())
            batch.clear()
        }
        (stroke as? Keystroke.Pressed)?.let { source.key(it.keyCode) }
    }
}

/** how long a letter waits for the next one before it is sent on its own. the Swing window's own. */
private const val TYPE_BATCH_MS = 50L

/** the level in force, with the same space kept for the ones that aren't so the labels line up. */
@Composable
private fun QualityTick(on: Boolean, color: Color) {
    Box(Modifier.size(Dimens.icon), contentAlignment = Alignment.Center) {
        if (on) AppIcon(Res.drawable.success, size = Dimens.icon, tint = color)
    }
}

/** named the way the Swing window's combo box names them. */
private val ScreenQuality.label: StringResource
    get() = when (this) {
        ScreenQuality.HIGH -> Res.string.mirror_quality_high
        ScreenQuality.MEDIUM -> Res.string.mirror_quality_mid
        ScreenQuality.LOW -> Res.string.mirror_quality_low
    }
