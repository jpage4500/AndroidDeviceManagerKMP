package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.dialog.common.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

// the phone glyph's height in dp — the status bar slider's range, as FileExplorerKMP's icon view has.
// the floor still fits the name, serial and the battery/storage panel; the ceiling is one device
// filling most of a desktop window
const val MIN_DEVICE_ICON = 96
const val MAX_DEVICE_ICON = 480

// a phone is roughly this much narrower than it is tall
private const val PHONE_ASPECT = 0.56f

// how finely Fit searches for the largest height that shows every device
private const val FIT_STEP = 4

// a serial is cut to its ending, at least this long and longer only where two devices share it
private const val SERIAL_TAIL_MIN = 4

/**
 * how much a tile says, by its height: each tier adds lines below the ones before it, so a tile
 * growing past a boundary only gains facts. The boundaries are first guesses, set in the design
 * page's demo — move them here.
 */
private enum class Tier(val minHeight: Int) {
    XS(0),              // name, serial ending, battery and storage
    S(130),             // + model, Android version, phone number, a custom field that tells devices apart
    M(200),             // + carrier, IMEI; storage shows its total
    L(280),             // + both custom fields
    ;

    companion object {
        fun of(height: Dp): Tier = entries.last { height.value >= it.minHeight }
    }
}

/**
 * what tells a device apart from the others on screen: the shortest serial ending no other device
 * has, and a custom field when its value differs between devices of the same name.
 */
private class TileIdent(val tail: String, val tag: String?)

/** the Devices page's two ways of drawing the same devices. */
enum class DeviceView {
    LIST,
    ICONS,
    ;

    companion object {
        fun of(name: String?): DeviceView = entries.firstOrNull { it.name == name } ?: LIST
    }
}

/**
 * the Devices page as icons: a phone per device with its name, serial, Android version and phone
 * number down the screen and its battery and storage in one panel at the bottom.
 *
 * The same rows, selection, menu and drop target as the table, so switching views loses nothing
 * but columns — what the table spends a column on, the status bar says about the one selected
 * device instead. Click semantics are the table's ([TableSelection.onPress]): ⌘ toggles, ⇧ ranges,
 * a double-click mirrors; the arrows move a tile at a time and ↑/↓ a row of them.
 *
 * Nothing per tile polls the device. A screenshot per tile would be 45 `screencap`s on the rig, so
 * the glyph is drawn from what [Device] already carries.
 *
 * **Fit**: with [iconSize] null the grid picks the largest height at which every device fits without
 * scrolling, and reports it through [onFitted] so the status bar's slider can show it. It re-fits as
 * devices come and go and as the window is resized, until the slider is dragged.
 */
@Composable
fun DeviceGrid(
    rows: List<Device>,
    selection: TableSelection<Any?>,
    busy: Set<String>,
    iconSize: Int?,
    onFitted: (Int) -> Unit,
    onSelectionChange: (TableSelection<Any?>) -> Unit,
    onActivate: (Device) -> Unit,
    dropAt: Offset?,
    onDropRow: (Device?) -> Unit,
    dropKeys: Set<String>,
    menu: @Composable AppMenuScope.(Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val keys: List<Any?> = remember(rows) { rows.map { it.id } }
    // read live inside the handlers rather than restarting them — see DataTable
    val keysNow by rememberUpdatedState(keys)
    val rowsNow by rememberUpdatedState(rows)
    val selectionNow by rememberUpdatedState(selection)
    val onDropRowNow by rememberUpdatedState(onDropRow)
    // each tile's bounds in the window, for a dragged-in file to find the tile under it. not state:
    // nothing draws from it, and a tile laying out shouldn't recompose the grid
    val tileBounds = remember { HashMap<String, Rect>() }

    LaunchedEffect(dropAt) {
        val at = dropAt
        if (at == null) {
            onDropRowNow(null)
            return@LaunchedEffect
        }
        val id = tileBounds.entries.firstOrNull { it.value.contains(at) }?.key
        onDropRowNow(id?.let { hit -> rowsNow.firstOrNull { it.id == hit } })
    }

    fun reveal(index: Int) {
        val visible = gridState.layoutInfo.visibleItemsInfo
        // fully on screen already: leave the scroll alone, or every arrow press would jerk the grid
        val shown = visible.firstOrNull { it.index == index }
        val viewport = gridState.layoutInfo.viewportEndOffset
        if (shown != null && shown.offset.y >= 0 && shown.offset.y + shown.size.height <= viewport) return
        scope.launch { gridState.scrollToItem(index) }
    }

    fun moveTo(index: Int, extend: Boolean) {
        if (index !in keysNow.indices) return
        val key = keysNow[index]
        onSelectionChange(if (extend) selectionNow.rangeTo(key, keysNow) else selectionNow.selectOnly(key))
        reveal(index)
    }

    fun moveBy(delta: Int, extend: Boolean) {
        if (keysNow.isEmpty()) return
        val current = selectionNow.lead?.let { keysNow.indexOf(it) } ?: -1
        // nothing selected yet: the first press lands on the first tile rather than skipping it
        val next = if (current < 0) 0 else (current + delta).coerceIn(0, keysNow.lastIndex)
        moveTo(next, extend)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val shift = event.isShiftPressed
        val meta = event.isMetaPressed || event.isCtrlPressed
        val columns = gridColumns(gridState)
        when {
            event.key == Key.DirectionUp && meta -> moveTo(0, shift)
            event.key == Key.DirectionDown && meta -> moveTo(keysNow.lastIndex, shift)
            event.key == Key.DirectionLeft -> moveBy(-1, shift)
            event.key == Key.DirectionRight -> moveBy(1, shift)
            event.key == Key.DirectionUp -> moveBy(-columns, shift)
            event.key == Key.DirectionDown -> moveBy(columns, shift)
            event.key == Key.MoveHome -> moveTo(0, shift)
            event.key == Key.MoveEnd -> moveTo(keysNow.lastIndex, shift)
            event.key == Key.A && meta -> onSelectionChange(selectionNow.selectAll(keysNow))
            event.key == Key.Escape -> onSelectionChange(selectionNow.clear())
            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                val lead = selectionNow.lead ?: return true
                rowsNow.firstOrNull { it.id == lead }?.let(onActivate)
            }
            // a command key this grid doesn't claim belongs to the menu bar — see DataTable. A
            // printable one is the Devices page's: it starts a search
            else -> return false
        }
        return true
    }

    // the serial endings are only unique against each other, so they are worked out over the whole list
    val idents = remember(rows) { identify(rows) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent(::handleKey)
            // a press no tile claimed landed on empty space: clear, the way a table's blank rows do
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (!event.isRealPress) continue
                        runCatching { focusRequester.requestFocus() }
                        if (event.changes.any { it.isConsumed }) continue
                        if (event.buttons.isPrimaryPressed) onSelectionChange(selectionNow.clear())
                    }
                }
            }
    ) {
        val fitted = remember(rows.size, maxWidth, maxHeight) {
            fitHeight(rows.size, maxWidth - Dimens.gap * 2, maxHeight - Dimens.gap * 2)
        }
        if (iconSize == null) {
            val report by rememberUpdatedState(onFitted)
            LaunchedEffect(fitted) { report(fitted) }
        }
        val height = (iconSize ?: fitted).coerceIn(MIN_DEVICE_ICON, MAX_DEVICE_ICON).dp

        LazyVerticalGrid(
            columns = GridCells.Adaptive(tileWidth(height)),
            state = gridState,
            contentPadding = PaddingValues(Dimens.gap),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows, key = { it.id }) { device ->
                DeviceTile(
                    device = device,
                    ident = idents[device.id],
                    iconSize = height,
                    selected = device.id in selection,
                    active = windowFocused,
                    busy = device.id in busy,
                    dropTarget = device.id in dropKeys,
                    onPress = { toggle, range ->
                        onSelectionChange(selectionNow.onPress(device.id, keysNow, toggle, range))
                    },
                    onContextClick = {
                        // a right-click outside the selection acts on the tile it landed on
                        if (device.id !in selectionNow) onSelectionChange(selectionNow.selectOnly(device.id))
                    },
                    onActivate = { onActivate(device) },
                    onMenuDismissed = { runCatching { focusRequester.requestFocus() } },
                    onPlaced = { bounds -> tileBounds[device.id] = bounds },
                    onDisposed = { tileBounds.remove(device.id) },
                    menu = { menu(device) },
                )
            }
        }
        AppVerticalScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }

    // claim focus once, so the arrows work without clicking a tile first
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

// a tile's own padding around the glyph: the selection tint's inset, then its gap to the next tile
private val TILE_EXTRA = Dimens.liveDot * 2 + Dimens.hairline * 4

private fun tileWidth(height: Dp): Dp = maxOf(Dimens.deviceTileMinWidth, height * PHONE_ASPECT + TILE_EXTRA)

/**
 * the largest glyph height at which [count] tiles fit in [width] × [height] without scrolling, or
 * the smallest when nothing does. Mirrors `GridCells.Adaptive`: as many columns as the cell's
 * minimum width allows.
 */
private fun fitHeight(count: Int, width: Dp, height: Dp): Int {
    if (count == 0 || width <= 0.dp || height <= 0.dp) return MIN_DEVICE_ICON
    for (h in MAX_DEVICE_ICON downTo MIN_DEVICE_ICON step FIT_STEP) {
        val columns = (width / tileWidth(h.dp)).toInt().coerceAtLeast(1)
        val rows = (count + columns - 1) / columns
        if ((h.dp + TILE_EXTRA) * rows <= height) return h
    }
    return MIN_DEVICE_ICON
}

/**
 * each device's [TileIdent]. The serial ending grows a character at a time from
 * [SERIAL_TAIL_MIN] until no two devices in [rows] end the same way — 4 is enough for the Swing
 * app's 43 Pixel 3a. A custom field is offered only when it isn't the same on every device of that
 * name, Custom 2 before Custom 1; one that's the same everywhere tells nobody anything.
 */
private fun identify(rows: List<Device>): Map<String, TileIdent> {
    if (rows.isEmpty()) return emptyMap()
    val serials = rows.map { baseSerial(it.serial) }
    val longest = serials.maxOf { it.length }
    var length = SERIAL_TAIL_MIN
    while (length < longest && serials.mapTo(HashSet()) { it.takeLast(length) }.size < serials.size) length++
    val groups = rows.groupBy { it.displayName }
    return rows.indices.associate { i ->
        val device = rows[i]
        val group = groups.getValue(device.displayName)
        fun varying(key: String): String? = device.customProperty(key)?.takeIf { value ->
            value.isNotBlank() && group.size > 1 && group.mapTo(HashSet()) { it.customProperty(key).orEmpty() }.size > 1
        }
        device.id to TileIdent(
            tail = serials[i].takeLast(length),
            tag = varying(Device.CUST_PROP_2) ?: varying(Device.CUST_PROP_1),
        )
    }
}

/**
 * the device's own serial: a wireless debugging serial
 * (`adb-RFCX20NB5TY-DBjlg4._adb-tls-connect._tcp`) carries it between `adb-` and the next dash.
 */
private fun baseSerial(serial: String): String =
    if (serial.contains("_adb-tls-connect")) serial.removePrefix("adb-").substringBefore('-') else serial

/** how many tiles a row holds right now — what ↑ and ↓ step by. */
private fun gridColumns(state: LazyGridState): Int {
    val items = state.layoutInfo.visibleItemsInfo
    val firstRow = items.firstOrNull()?.row ?: return 1
    return items.count { it.row == firstRow }.coerceAtLeast(1)
}

/** one device: its phone glyph, whose frame and bezel carry the selection. */
@Composable
private fun DeviceTile(
    device: Device,
    ident: TileIdent?,
    iconSize: Dp,
    selected: Boolean,
    active: Boolean,
    busy: Boolean,
    dropTarget: Boolean,
    onPress: (toggle: Boolean, range: Boolean) -> Unit,
    onContextClick: () -> Unit,
    onActivate: () -> Unit,
    onMenuDismissed: () -> Unit,
    onPlaced: (Rect) -> Unit,
    onDisposed: () -> Unit,
    menu: @Composable AppMenuScope.() -> Unit,
) {
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf(DpOffset.Zero) }
    val selectedNow by rememberUpdatedState(selected)
    // a press on a selected tile holds the whole selection until release, so a plain click on one
    // of several narrows to it without breaking ⌘-click — the table's behaviour
    var pendingCollapse by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose(onDisposed) }

    val shape = RoundedCornerShape(Dimens.tileCorner)
    val selection = when {
        dropTarget || (selected && active) -> GlyphSelection.ACTIVE
        selected -> GlyphSelection.INACTIVE
        else -> GlyphSelection.NONE
    }

    Box(Modifier.padding(Dimens.hairline * 2)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(if (dropTarget) Modifier.border(Dimens.hairline * 2, Colors.tabAccent, shape) else Modifier)
                .onGloballyPositioned { onPlaced(it.boundsInRoot()) }
                .pointerInput(Unit) {
                    val doubleClickMs = viewConfiguration.doubleTapTimeoutMillis
                    var lastPress = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) continue
                            if (event.type == PointerEventType.Release) {
                                if (pendingCollapse) onPress(false, false)
                                pendingCollapse = false
                                continue
                            }
                            if (!event.isRealPress) continue

                            if (event.buttons.isSecondaryPressed) {
                                event.changes.firstOrNull()?.position?.let { at ->
                                    menuAt = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) }
                                }
                                onContextClick()
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val modifiers = event.keyboardModifiers
                            val toggle = modifiers.isMetaPressed || modifiers.isCtrlPressed
                            val range = modifiers.isShiftPressed
                            if (selectedNow && !toggle && !range) pendingCollapse = true else onPress(toggle, range)
                            event.changes.forEach { it.consume() }

                            val now = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                            if (lastPress != 0L && now - lastPress <= doubleClickMs) {
                                onActivate()
                                pendingCollapse = false
                                lastPress = 0L
                            } else {
                                lastPress = now
                            }
                        }
                    }
                }
                .padding(Dimens.liveDot),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PhoneGlyph(device = device, ident = ident, height = iconSize, busy = busy, selection = selection)
        }
        AppPopupMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                onMenuDismissed()
            },
            offset = menuAt,
            content = menu,
        )
    }
}

/**
 * a device drawn as a phone. Down the screen: its name in bold, the model, the serial ending, then
 * a line per detail, each with its icon — Android version, phone number, carrier and so on, more of
 * them as the tile grows (see [Tier]). Battery and storage are one panel pinned to the
 * bottom, so it lines up across a row whatever is above it.
 *
 * The earpiece is the state light — the table's state dot. An offline or unauthorized device shows
 * its state's icon instead of the panel: the numbers it last reported aren't worth showing as if
 * they were current.
 */
@Composable
private fun PhoneGlyph(device: Device, ident: TileIdent?, height: Dp, busy: Boolean, selection: GlyphSelection) {
    val width = height * PHONE_ASPECT
    val tier = Tier.of(height)
    val idleFrame = if (device.isOnline) Colors.secondaryText else Colors.secondaryText.copy(alpha = 0.45f)
    // selected: the frame takes the accent and the bezel between it and the screen a tint of it —
    // the screen itself stays plain so the text on it reads the same either way
    val frame = when (selection) {
        GlyphSelection.ACTIVE -> Colors.tabAccent
        GlyphSelection.INACTIVE -> Colors.secondaryText
        GlyphSelection.NONE -> idleFrame
    }
    val screen = Colors.listBg
    val bezel = when (selection) {
        GlyphSelection.ACTIVE -> Colors.rowSelectedBg
        GlyphSelection.INACTIVE -> Colors.rowSelectedInactive
        GlyphSelection.NONE -> screen
    }
    val stateColor = device.stateColor(busy)
    val sizes = GlyphSizes(height)
    val density = LocalDensity.current
    val stroke = with(density) { maxOf(1.5.dp.toPx(), width.toPx() / 26f).toDp() }

    Box(Modifier.size(width, height)) {
        Canvas(Modifier.fillMaxSize()) {
            val line = stroke.toPx()
            val corner = size.width * 0.17f
            drawRoundRect(bezel, cornerRadius = CornerRadius(corner))
            // the screen, inside the bezels
            val side = line * 2.2f
            val top = size.height * 0.09f
            val bottom = size.height * 0.07f
            if (bezel != screen) {
                drawRoundRect(
                    screen,
                    topLeft = Offset(side, top),
                    size = Size(size.width - side * 2, size.height - top - bottom),
                    cornerRadius = CornerRadius(corner * 0.45f),
                )
            }
            drawRoundRect(
                frame.copy(alpha = frame.alpha * 0.35f),
                topLeft = Offset(side, top),
                size = Size(size.width - side * 2, size.height - top - bottom),
                cornerRadius = CornerRadius(corner * 0.45f),
                style = Stroke(width = Dimens.hairline.toPx()),
            )
            // the earpiece, lit in the device's state colour — thicker than the frame so the colour
            // reads at a glance across a grid of them
            val slot = size.width * 0.26f
            drawLine(
                stateColor,
                start = Offset((size.width - slot) / 2f, top / 2f + line / 4f),
                end = Offset((size.width + slot) / 2f, top / 2f + line / 4f),
                strokeWidth = line * 1.8f,
                cap = StrokeCap.Round,
            )
            drawRoundRect(
                frame,
                topLeft = Offset(line / 2f, line / 2f),
                size = Size(size.width - line, size.height - line),
                cornerRadius = CornerRadius(corner),
                style = Stroke(width = line),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = stroke * 2.2f + height * 0.03f,
                    end = stroke * 2.2f + height * 0.03f,
                    top = height * 0.12f,
                    bottom = height * 0.1f,
                ),
        ) {
            // the lines take what the panel leaves, and any that don't fit whole are left off — a
            // tier's guess at the room it has can be a line out, and the panel must never be pushed off
            FitColumn(Modifier.weight(1f).fillMaxWidth()) {
                TileLines(device, ident, tier, sizes)
            }
            if (device.isOnline) {
                BatteryStoragePanel(device, tier, sizes)
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AppIcon(
                        res = if (device.state == DeviceState.OFFLINE || device.state == DeviceState.DETACHED) {
                            Res.drawable.status_offline
                        } else {
                            Res.drawable.status_busy
                        },
                        size = width * 0.28f,
                        tint = stateColor,
                    )
                }
                val needsAccepting = device.state == DeviceState.UNAUTHORIZED || device.state == DeviceState.AUTHORIZING
                Text(
                    text = stringResource(if (needsAccepting) Res.string.state_unauthorized_short else Res.string.state_offline),
                    fontSize = sizes.small,
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** how a tile's phone shows the selection: the window's focus decides active from inactive, as in the table. */
private enum class GlyphSelection { NONE, ACTIVE, INACTIVE }

/**
 * the tile's type sizes and spacing, from its height — held between floors that stay readable on a
 * 45-device grid and ceilings that don't balloon on a one-device one.
 */
private class GlyphSizes(height: Dp) {
    val name = (height.value * 0.074f).coerceIn(10.5f, 17f).sp
    val small = (height.value * 0.058f).coerceIn(8.5f, 13f).sp
    val gap = maxOf(2.dp, height * 0.022f)
    val row = (height * 0.085f).coerceIn(11.dp, 24.dp)
    val panelText = (height.value * 0.05f).coerceIn(8f, 13f).sp
}

/**
 * its children top to bottom, as many as fit whole — the rest aren't drawn at all, rather than
 * drawn cut in half or pushing whatever comes after this off the bottom.
 */
@Composable
private fun FitColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = ArrayList<Placeable>()
        var used = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            if (used + placeable.height > constraints.maxHeight) break
            placeables += placeable
            used += placeable.height
        }
        layout(constraints.maxWidth, if (constraints.hasBoundedHeight) constraints.maxHeight else used) {
            var y = 0
            placeables.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

/**
 * name, model, serial, then a line per detail with its icon — each from the tier that has room.
 * Every one is its own child, so [FitColumn] drops the last lines first when it runs out of room.
 */
@Composable
private fun TileLines(device: Device, ident: TileIdent?, tier: Tier, sizes: GlyphSizes) {
    Text(
        text = searchHighlighted(device.displayName),
        fontSize = sizes.name,
        fontWeight = FontWeight.Bold,
        color = if (device.isOnline) Colors.primaryText else Colors.secondaryText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (tier >= Tier.S) {
        // always shown, even when it's the name again, so every tile's lines start at the same height
        Text(
            text = searchHighlighted(device.model ?: device.serial),
            fontSize = sizes.small,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (ident != null) DetailLine(GlyphPaths.SERIAL, ident.tail, sizes, filled = true, mono = true)
    if (!device.isOnline || tier < Tier.S) return

    device.os?.let { os ->
        DetailLine(null, os, sizes) { icon -> AppIcon(Res.drawable.android, size = icon, tint = Colors.secondaryText) }
    }
    device.phone?.takeIf { it.isNotBlank() }?.let { DetailLine(GlyphPaths.PHONE, it, sizes) }
    if (tier < Tier.L) {
        // only the custom field that tells same-named devices apart; at L every custom field shows
        ident?.tag?.let { DetailLine(GlyphPaths.TAG, it, sizes) }
    }
    if (tier < Tier.M) return
    device.carrier?.takeIf { it.isNotBlank() }?.let { DetailLine(GlyphPaths.SIGNAL, it, sizes) }
    device.imei?.takeIf { it.isNotBlank() }?.let { DetailLine(GlyphPaths.SIM, it, sizes, mono = true) }
    if (tier < Tier.L) return
    device.customProperty(Device.CUST_PROP_1)?.takeIf { it.isNotBlank() }?.let { DetailLine(GlyphPaths.TAG, it, sizes) }
    device.customProperty(Device.CUST_PROP_2)?.takeIf { it.isNotBlank() }?.let { DetailLine(GlyphPaths.TAG, it, sizes) }
}

/** one detail: its icon — a [GlyphPaths] path, or [icon] for one of the app's PNGs — then its value. */
@Composable
private fun DetailLine(
    path: String?,
    text: String,
    sizes: GlyphSizes,
    filled: Boolean = false,
    mono: Boolean = false,
    icon: (@Composable (Dp) -> Unit)? = null,
) {
    val iconSize = with(LocalDensity.current) { sizes.small.toDp() } * 1.1f
    Row(Modifier.padding(top = sizes.gap), verticalAlignment = Alignment.CenterVertically) {
        if (path != null) PathIcon(path, size = iconSize, tint = Colors.secondaryText, filled = filled) else icon?.invoke(iconSize)
        Spacer(Modifier.width(Dimens.hairline * 3))
        Text(
            text = searchHighlighted(text),
            fontSize = sizes.small,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = Colors.primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * battery over storage, as one outlined box split by a hairline. Both rows are drawn alike — an
 * icon, the figure written over a fill from the left, a detail at the right — and differ only in
 * the battery's fill colour and its charging bolt. A reading not taken yet leaves its row empty
 * rather than closing the gap, so every tile's panel is the same height.
 */
@Composable
private fun BatteryStoragePanel(device: Device, tier: Tier, sizes: GlyphSizes) {
    val shape = RoundedCornerShape(sizes.row * 0.32f)
    val icon = with(LocalDensity.current) { sizes.panelText.toDp() } * 1.15f
    val level = device.batteryLevel
    val free = device.freeSpace
    val total = device.totalSpace?.takeIf { it > 0 }
    Column(
        Modifier
            .padding(top = sizes.gap)
            .fillMaxWidth()
            .clip(shape)
            .border(Dimens.hairline * 1.5f, Colors.secondaryText, shape)
    ) {
        PanelRow(
            glyph = GlyphPaths.BATTERY,
            text = level?.let { stringResource(Res.string.battery_percent, it) }.orEmpty(),
            fraction = (level ?: 0) / 100f,
            fill = level?.let { batteryColor(it).copy(alpha = 0.42f) } ?: Color.Transparent,
            sizes = sizes,
            icon = icon,
        ) {
            if (device.powerStatus.isCharging) AppIcon(Res.drawable.charging, size = icon, tint = Colors.busy)
        }
        HorizontalDivider(thickness = Dimens.hairline, color = Colors.secondaryText.copy(alpha = 0.4f))
        PanelRow(
            glyph = GlyphPaths.STORAGE,
            text = free?.let { stringResource(Res.string.device_free_space, Utils.bytesToDisplayString(it)) }.orEmpty(),
            fraction = if (free != null && total != null) 1f - free.toFloat() / total else 0f,
            fill = Colors.toolbarIcon.copy(alpha = 0.22f),
            sizes = sizes,
            icon = icon,
        ) {
            if (tier >= Tier.M && total != null) {
                Text(
                    text = stringResource(Res.string.device_of_total, Utils.bytesToDisplayString(total)),
                    fontSize = sizes.panelText,
                    color = Colors.secondaryText,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PanelRow(
    glyph: String,
    text: String,
    fraction: Float,
    fill: Color,
    sizes: GlyphSizes,
    icon: Dp,
    trailing: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(sizes.row)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(fill))
        Row(
            Modifier.fillMaxSize().padding(horizontal = sizes.row * 0.28f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PathIcon(glyph, size = icon, tint = Colors.secondaryText)
            Spacer(Modifier.width(Dimens.hairline * 3))
            Text(
                text = text,
                fontSize = sizes.panelText,
                fontWeight = FontWeight.SemiBold,
                color = Colors.primaryText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
    }
}

/**
 * List and Icons, as two glyph buttons in one outlined control — FileExplorerKMP's view segments.
 */
@Composable
fun DeviceViewToggle(view: DeviceView, onView: (DeviceView) -> Unit) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        Modifier
            .height(Dimens.controlHeight)
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.divider, shape)
            .padding(Dimens.hairline * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceView.entries.forEach { mode ->
            val on = mode == view
            val label = stringResource(if (mode == DeviceView.LIST) Res.string.view_as_list else Res.string.view_as_icons)
            CellTooltip(text = { label }) {
                Box(
                    Modifier
                        .width(Dimens.viewToggleSegment)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Dimens.navCorner - Dimens.hairline * 2))
                        .background(if (on) Colors.chipOn else Color.Transparent)
                        .clickable { onView(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    ViewGlyph(mode, if (on) Colors.chipOnText else Colors.toolbarIcon, Modifier.size(Dimens.icon - Dimens.hairline * 2))
                }
            }
        }
    }
}

/** a 2×2 of squares for icons, three bulleted lines for the list — FileExplorerKMP's glyphs. */
@Composable
private fun ViewGlyph(mode: DeviceView, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        when (mode) {
            DeviceView.ICONS -> {
                val cell = w * 0.4f
                val gap = w * 0.2f
                listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (cx, cy) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(cx * (cell + gap), cy * (cell + gap)),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(cell * 0.25f),
                    )
                }
            }

            DeviceView.LIST -> {
                val spacing = h / 3
                repeat(3) { i ->
                    val y = spacing * i + spacing / 2f
                    drawCircle(color, radius = w * 0.08f, center = Offset(w * 0.08f, y))
                    drawLine(
                        color = color,
                        start = Offset(w * 0.3f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

/**
 * the icon view's size controls, for the status bar's right-hand corner: a Fit button, lit while
 * the grid is fitting itself, and the slider, which shows the fitted size until it's dragged.
 */
@Composable
fun DeviceIconSizeSlider(
    value: Int,
    fitting: Boolean,
    onFit: () -> Unit,
    onChange: (Int) -> Unit,
    onChangeFinished: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val fitLabel = stringResource(Res.string.icon_fit_hint)
        CellTooltip(text = { fitLabel }) {
            val shape = RoundedCornerShape(Dimens.checkboxCorner)
            Box(
                Modifier
                    .height(Dimens.statusBarHeight - Dimens.gap)
                    .clip(shape)
                    .background(if (fitting) Colors.chipOn else Color.Transparent)
                    .border(Dimens.hairline, if (fitting) Colors.chipOn else Colors.divider, shape)
                    .clickable(onClick = onFit)
                    .padding(horizontal = Dimens.liveDot),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.icon_fit),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (fitting) Colors.chipOnText else Colors.secondaryText,
                )
            }
        }
        Spacer(Modifier.width(Dimens.gap))
        val label = stringResource(Res.string.icon_size)
        CellTooltip(text = { label }) {
            Box(Modifier.width(Dimens.deviceIconSliderWidth)) {
                CustomSlider(
                    startValue = MIN_DEVICE_ICON,
                    endValue = MAX_DEVICE_ICON,
                    value = value,
                    onValueChange = onChange,
                    onValueChangeFinished = onChangeFinished,
                )
            }
        }
    }
}

