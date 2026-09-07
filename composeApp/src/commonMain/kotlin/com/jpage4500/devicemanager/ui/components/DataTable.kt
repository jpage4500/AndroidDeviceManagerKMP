package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

// how far PageUp/PageDown jump before the viewport height is known
private const val PAGE_ROWS = 20

/**
 * the row text size in force.
 *
 * a table-wide value rather than a parameter on every cell: the log viewer scales its rows with
 * ⌘+/⌘-, and every cell has to follow without each one being handed the number.
 */
val LocalTableTextSize = compositionLocalOf { Dimens.tableTextSize }

/**
 * the table's own focus, reachable from a row or a header.
 *
 * a context menu is a focusable Popup, so opening one takes the keyboard away from the table — and
 * closing it doesn't hand it back. every menu asks for it again on dismiss, or the arrow keys would
 * stop working until the next click.
 */
private val LocalTableFocus = staticCompositionLocalOf<FocusRequester?> { null }

// a typed prefix stops describing the selection once this long has passed with no further keys
private const val TYPE_BUFFER_TIMEOUT_MS = 900L

// how far the pointer must travel over blank space before a rubber-band starts, rather than the
// gesture being a click that happened to wobble
private val MARQUEE_SLOP = 4.dp

// and how far a header must travel before the press is a reorder rather than a sort
private val COLUMN_DRAG_SLOP = 4.dp

/**
 * the column being dragged, shared by the header and every row.
 *
 * A JTable moves the **whole column** — header and cells together — over its neighbours while the
 * rest shift underneath it, and a cell can only join in if something above it holds the state. One
 * object, handed down, so nothing has to be re-keyed to reach it.
 *
 * [column] is the column's own identity rather than an index, because the order changes mid-drag and
 * an index would point at whichever column had just moved into the slot. [offset] is written on
 * every pointer move, which is why it is only ever read inside a placement lambda: read while
 * composing, one drag would recompose every visible cell sixty times a second.
 */
@Stable
private class ColumnDrag<C> {
    var column by mutableStateOf<C?>(null)
    val offset = mutableFloatStateOf(0f)
}

/**
 * a real data table: keyboard-navigable, multi-selectable, sortable, resizable.
 *
 * Compose has no table — `LazyColumn` gives scrolling and nothing else — so everything a native one
 * hands over for free is built here, once. The approach is FileExplorerKMP's file list, which is
 * where each of the awkward details below was already paid for:
 *
 * - **`onKeyEvent` before `focusable()`.** Key events dispatch to the focused node and bubble *up*
 *   through its parents, so a handler placed after `focusable()` sits below the focus target and
 *   never sees a single press.
 * - **Hand-rolled press handling rather than `combinedClickable`**, which defers the single click by
 *   the double-tap timeout — that delay is exactly what makes a list feel laggy to select in.
 * - **Pressing an already-selected row doesn't collapse the selection until release**, so a future
 *   drag can carry the whole group. Every native file manager does this.
 * - **Stripes are painted by the container**, not per row, so they carry on past the last row to the
 *   bottom of the viewport instead of stopping in mid-air.
 * - **[isRealPress]**, because Compose invents presses for a pointer that entered the window already
 *   held down.
 *
 * Generic over the row type [T] and column type [C]: the device list is the first table, the file
 * explorer and the log viewer are the next two, and this is the difference between three of these
 * and one.
 *
 * @param rows every row, already sorted. Keep this list stable — it is the input a 100,000-line log
 *   view will be handing over on every append.
 * @param widths in Dp, held by the caller so they can be persisted
 */
@Composable
fun <T, C> DataTable(
    rows: List<T>,
    columns: List<C>,
    widths: Map<C, Dp>,
    sortedBy: C?,
    sortAscending: Boolean,
    selection: TableSelection<Any?>,
    rowKey: (T) -> Any,
    header: (C) -> String,
    modifier: Modifier = Modifier,
    rowHeight: Dp = Dimens.tableRowHeight,
    /** what type-to-select matches against; null turns it off. */
    typeAheadText: ((T) -> String)? = null,
    /**
     * the colour a row's text draws in — a log level, say. A selected row ignores it and uses the
     * selection's own foreground, or it would be unreadable on the accent.
     */
    rowContentColor: ((T) -> Color)? = null,
    /** hoisted so a caller can drive the scroll — the log viewer follows the tail. */
    listState: LazyListState = rememberLazyListState(),
    /** row text size; the log viewer scales it with ⌘+/⌘-. */
    textSize: TextUnit = Dimens.tableTextSize,
    /**
     * how many rows have already dropped off the **front** of [rows].
     *
     * banding comes from a row's index, and an index is not a property of the row — a log that drops
     * its oldest line shifts every row up by one and the whole table's stripes invert. Adding back
     * what was dropped is what makes the band the row's own: the offset rises by exactly what the
     * index fell by. A list that only ever grows at the end leaves this at 0.
     *
     * the row's own id can't do this job either. With a filter on, what reaches the table is
     * whichever lines matched, so their ids skip — and parity taken from a number that skips is
     * banding at random.
     */
    stripeOffset: Long = 0L,
    /**
     * the tiled background behind the rows.
     *
     * off for the log table: at 30,000 dense lines the wash is one more thing between the eye and
     * the text, and the pattern shows through every un-banded row.
     */
    showBackground: Boolean = true,
    onSelectionChange: (TableSelection<Any?>) -> Unit = {},
    onSort: (C) -> Unit = {},
    onResize: (C, Dp) -> Unit = { _, _ -> },
    /** the user dragged the header at [from] to sit at [to]. */
    onReorder: (from: Int, to: Int) -> Unit = { _, _ -> },
    onActivate: (T) -> Unit = {},
    /**
     * the full text for a cell the pointer is resting on, or null for no tooltip.
     *
     * told whether that cell's own text had to be clipped, so "answer only when the value doesn't
     * fit" is one `takeIf` — the Swing table's `getTextIfTruncated`. A column whose cell can't show
     * its value at any width answers regardless: a battery bar has no number on it. Null turns
     * tooltips off for the whole table, and costs nothing per cell.
     */
    tooltip: ((row: T, column: C, clipped: Boolean) -> String?)? = null,
    /**
     * the right-click menu for a row, composed **inside that row** so it opens at the pointer — see
     * [AppPopupMenu]. the selection has already been adjusted by the time this is called.
     *
     * [column] is the one the click landed on, so a menu can offer what only makes sense there —
     * "Filter by this tag" on the tag column and nowhere else. Null only for a table with no
     * columns at all.
     */
    rowMenu: (@Composable AppMenuScope.(row: T, column: C?) -> Unit)? = null,
    /** the right-click menu for a column header, composed inside that header. */
    headerMenu: (@Composable AppMenuScope.(column: C) -> Unit)? = null,
    cell: @Composable (row: T, column: C) -> Unit,
) {
    val horizontalScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val drag = remember { ColumnDrag<C>() }
    // read once for the whole table rather than per row: a dragged cell has to paint its own row's
    // background, and these are CompositionLocal reads
    val stripeColor = Colors.rowStripe
    val listBackground = Colors.listBg
    // a selection in an unfocused window is drawn grey, the way every native list does it — so it's
    // still visible, but it doesn't claim to be where the keys are going
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    // keys are what selection is expressed in, and index lookups need them in row order
    val keys = remember(rows) { rows.map(rowKey) }
    val totalWidth = columns.fold(0.dp) { sum, column -> sum + (widths[column] ?: Dimens.columnDefault) }

    // read inside gestures and key handlers without keying them on it — a press changes the
    // selection, and re-keying `pointerInput` would cancel the very gesture that changed it
    val selectionNow by rememberUpdatedState(selection)
    val keysNow by rememberUpdatedState(keys)

    // scroll a row into view after the keyboard moves to it. carries a token as well as the index so
    // asking twice for the same row still fires — the effect keys on the pair, not the index
    var reveal by remember { mutableStateOf(0 to -1) }
    LaunchedEffect(reveal) {
        if (reveal.second >= 0) listState.requestReveal(reveal.second)
    }


    // type-to-select buffer. plain vars, not state: nothing draws them, and making them state would
    // recompose the whole table on every keystroke
    var typeBuffer by remember { mutableStateOf("") }
    var typeBufferAt by remember { mutableStateOf(0L) }

    // the rubber-band rectangle, in list-container coordinates; null when one isn't being dragged
    var marqueeStart by remember { mutableStateOf<Offset?>(null) }
    var marqueeEnd by remember { mutableStateOf(Offset.Zero) }

    fun apply(next: TableSelection<Any?>, revealAt: Int = -1) {
        onSelectionChange(next)
        if (revealAt >= 0) reveal = (reveal.first + 1) to revealAt
    }

    fun moveTo(index: Int, extend: Boolean) {
        if (index !in keysNow.indices) return
        // whatever was half-typed described the row the selection *was* on, so moving it any other
        // way ends the type-ahead — [typeAhead] re-arms it straight after the move it makes itself
        typeBuffer = ""
        val key = keysNow[index]
        val current = selectionNow
        apply(
            if (extend) current.rangeTo(key, keysNow) else current.selectOnly(key),
            revealAt = index,
        )
    }

    fun moveBy(delta: Int, extend: Boolean) {
        if (keysNow.isEmpty()) return
        val current = selectionNow.lead?.let { keysNow.indexOf(it) } ?: -1
        moveTo((current + delta).coerceIn(0, keysNow.lastIndex), extend)
    }

    fun typeAhead(char: Char) {
        val match = typeAheadText ?: return
        val now = DateUtils.nowMs()
        val buffer = if (now - typeBufferAt > TYPE_BUFFER_TIMEOUT_MS) char.toString() else typeBuffer + char
        val index = rows.indexOfFirst { match(it).startsWith(buffer, ignoreCase = true) }
        if (index >= 0) moveTo(index, extend = false)
        // armed after the move, because moveTo clears it for every other caller and this is the one
        // that is continuing a type-ahead rather than interrupting one
        typeBuffer = buffer
        typeBufferAt = now
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val shift = event.isShiftPressed
        val meta = event.isMetaPressed || event.isCtrlPressed
        val alt = event.isAltPressed
        val page = visibleRows(listState)
        when {
            // the modified arrows come first, or the plain ones below would claim them and ⌘↓ would
            // move the selection one row. ⌘↑/⌘↓ for the ends and ⌥↑/⌥↓ for a page is what a mac
            // list does; Home/End and PageUp/PageDown still work for a keyboard that has them
            event.key == Key.DirectionUp && meta -> moveTo(0, shift)
            event.key == Key.DirectionDown && meta -> moveTo(keysNow.lastIndex, shift)
            event.key == Key.DirectionUp && alt -> moveBy(-page, shift)
            event.key == Key.DirectionDown && alt -> moveBy(page, shift)
            event.key == Key.DirectionUp -> moveBy(-1, shift)
            event.key == Key.DirectionDown -> moveBy(1, shift)
            event.key == Key.MoveHome -> moveTo(0, shift)
            event.key == Key.MoveEnd -> moveTo(keysNow.lastIndex, shift)
            event.key == Key.PageUp -> moveBy(-page, shift)
            event.key == Key.PageDown -> moveBy(page, shift)
            event.key == Key.A && meta -> apply(selectionNow.selectAll(keysNow))
            event.key == Key.Escape -> apply(selectionNow.clear())
            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                val index = selectionNow.lead?.let { keysNow.indexOf(it) } ?: return true
                rows.getOrNull(index)?.let(onActivate)
            }
            // a command-key press this table doesn't claim belongs to a menu-bar item; letting it
            // through is what keeps a shortcut from firing twice
            meta || alt -> return false
            else -> {
                // printable characters only. keys with no character — arrows, modifiers, F-keys —
                // report 0xFFFF
                val codePoint = event.utf16CodePoint
                if (codePoint == 0xFFFF || codePoint < 0x20 || codePoint in 0x7F..0x9F) return false
                typeAhead(codePoint.toChar())
            }
        }
        return true
    }

    CompositionLocalProvider(
        LocalTableFocus provides focusRequester,
        LocalTableTextSize provides textSize,
    ) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(horizontalScroll)) {
            TableHeader(
                columns = columns,
                widths = widths,
                sortedBy = sortedBy,
                sortAscending = sortAscending,
                header = header,
                onSort = onSort,
                onResize = onResize,
                onReorder = onReorder,
                drag = drag,
                menu = headerMenu,
            )
        }
        HorizontalDivider(color = Colors.headerDivider)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Colors.listBg)
                // the stripes would otherwise paint over the header when a row is half-scrolled
                .clipToBounds()
                .stripes(listState, rowHeight, stripeOffset)
                // **after** the bands, not before. Drawn under them the tile only showed through the
                // un-banded rows, which read as the background flashing on alternate lines. Over
                // both, every row gets the same wash and the banding still reads through it
                .then(if (showBackground) Modifier.watermark() else Modifier)
                // onKeyEvent MUST come before focusable(): key events dispatch to the focused node
                // and bubble up through its parents, so a handler after focusable() never sees them
                .onKeyEvent { handleKey(it) }
                .focusRequester(focusRequester)
                .focusable()
                // a press anywhere takes focus back, so the arrows keep working after clicking a
                // toolbar button; on empty space below the last row it also drops the selection
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.isRealPress) continue
                            runCatching { focusRequester.requestFocus() }
                            // rows consume their own press, so anything left is empty space
                            if (event.changes.any { it.isConsumed }) continue
                            onSelectionChange(selectionNow.clear())
                        }
                    }
                }
                // rubber-band select, for gestures no row claimed — a drag that began on blank space
                // rather than on a row, which consumes its own press
                .pointerInput(rows) {
                    val slopPx = MARQUEE_SLOP.toPx()
                    awaitEachGesture {
                        // awaitRealPress rather than awaitFirstDown: dragging another window across
                        // this one hands the list a press Compose made up, and banding on it would
                        // select every row that window passed over
                        val down = awaitRealPress()
                        var travelled = Offset.Zero
                        var banding = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // a row claimed this gesture; leave it alone
                            if (change.isConsumed && !banding) break
                            travelled += change.positionChange()
                            if (!banding && travelled.getDistance() > slopPx) {
                                banding = true
                                marqueeStart = down.position
                            }
                            if (banding) {
                                marqueeEnd = change.position
                                val span = rowsIntersecting(listState, down.position.y, change.position.y)
                                onSelectionChange(
                                    if (span.isEmpty()) selectionNow.clear()
                                    else selectionNow.selectAll(span.mapNotNull { keysNow.getOrNull(it) })
                                )
                                change.consume()
                            }
                        }
                        marqueeStart = null
                    }
                },
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
                itemsIndexed(rows, key = { _, row -> rowKey(row) }) { index, row ->
                    val key = rowKey(row)
                    val band = stripeOffset + index
                    TableRow(
                        columns = columns,
                        widths = widths,
                        height = rowHeight,
                        width = totalWidth,
                        selected = key in selection,
                        windowFocused = windowFocused,
                        contentColor = rowContentColor?.invoke(row) ?: Colors.primaryText,
                        // the band this row is painted in, which a dragged cell has to repaint for
                        // itself — it is travelling over cells it would otherwise show through
                        bandColor = if (band % 2L != 0L) stripeColor else listBackground,
                        drag = drag,
                        tooltip = tooltip?.let { answer -> { column, clipped -> answer(row, column, clipped) } },
                        onPress = { toggle, range ->
                            onSelectionChange(selectionNow.onPress(key, keysNow, toggle, range))
                        },
                        onActivate = { onActivate(row) },
                        onContextClick = {
                            // right-click keeps an existing multi-selection and otherwise selects
                            // the row under the cursor — what every native table does
                            if (key !in selectionNow) onSelectionChange(selectionNow.selectOnly(key))
                        },
                        menu = rowMenu?.let { build -> { column -> build(row, column) } },
                    ) { column ->
                        cell(row, column)
                    }
                }
            }
            marqueeStart?.let { start -> MarqueeRect(start, marqueeEnd) }
            AppVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
            // maxValue is 0 when every column already fits, which is the only reliable way to ask —
            // the window's width isn't the table's
            if (horizontalScroll.maxValue > 0) {
                AppHorizontalScrollbar(horizontalScroll, Modifier.align(Alignment.BottomCenter))
            }
        }
    }

    }

    // claim focus once, so the arrows work without having to click a row first
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/** close a menu and give the keyboard back to the table underneath it. */
@Composable
private fun menuDismiss(close: () -> Unit): () -> Unit {
    val focus = LocalTableFocus.current
    return {
        close()
        runCatching { focus?.requestFocus() }
    }
}

/**
 * alternating stripes painted by the container rather than per row, so they carry on past the last
 * row to the bottom of the viewport — which is what a native table does, and what stops a short list
 * ending in a hard edge.
 *
 * [dropped] is the caller's [DataTable] `stripeOffset`: what a row's index has already lost off the
 * front of the list, added back so a band belongs to its row rather than to a position.
 */
@Composable
private fun Modifier.stripes(
    listState: LazyListState,
    rowHeight: Dp,
    dropped: Long,
): Modifier {
    val stripe = Colors.rowStripe
    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.toPx() }
    val insetPx = with(density) { Dimens.rowStripeInset.toPx() }
    val cornerPx = with(density) { Dimens.rowStripeCorner.toPx() }

    return drawBehind {
        fun band(y: Float, height: Float) = drawRoundRect(
            color = stripe,
            topLeft = Offset(insetPx, y),
            size = Size(size.width - insetPx * 2, height),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
        )

        // drawn from the laid-out rows rather than from `firstVisibleItemIndex` and a row height,
        // and in each row's **own** height: a row height is a Dp and a laid-out row is a whole
        // number of pixels, and at a fractional row — the log's, which follows its font size — the
        // difference accumulates into a hairline of the wrong colour between one band and the next
        val visible = listState.layoutInfo.visibleItemsInfo
        visible.forEach { item ->
            if ((dropped + item.index) % 2L != 0L) band(item.offset.toFloat(), item.size.toFloat())
        }

        // carry on past the last row to the bottom of the viewport, so a short list doesn't end in
        // a hard edge — continuing the alternation from whatever the last row was
        val last = visible.lastOrNull() ?: return@drawBehind
        var y = (last.offset + last.size).toFloat()
        var band = dropped + last.index + 1
        while (y < size.height) {
            if (band % 2L != 0L) band(y, rowPx)
            y += rowPx
            band++
        }
    }
}

@Composable
private fun <C> TableHeader(
    columns: List<C>,
    widths: Map<C, Dp>,
    sortedBy: C?,
    sortAscending: Boolean,
    header: (C) -> String,
    onSort: (C) -> Unit,
    onResize: (C, Dp) -> Unit,
    onReorder: (Int, Int) -> Unit,
    drag: ColumnDrag<C>,
    menu: (@Composable AppMenuScope.(C) -> Unit)?,
) {
    var menuFor by remember { mutableStateOf<C?>(null) }
    var menuAt by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    // read live inside the gesture rather than keying the modifier on it. keying on `columns` is
    // what broke the first version: a reorder changes the list, which restarted the pointerInput and
    // killed the drag after exactly one swap
    val columnsNow by rememberUpdatedState(columns)
    val widthsNow by rememberUpdatedState(widths)
    val handlePx = with(density) { Dimens.resizeHandle.toPx() }

    Box {
        Row(
            Modifier
                .height(Dimens.headerHeight)
                .background(Colors.headerBackground)
                // ONE gesture for the whole header, on a node that never moves.
                //
                // the first version put it on each header cell, which `Modifier.offset` then moved
                // under the pointer — so `positionChange()` counted the offset as pointer movement
                // and the column ran away from the cursor. Measured against the header row instead,
                // the coordinate space is fixed and the arithmetic is exact.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        var down: PointerInputChange? = null
                        while (down == null) {
                            val event = awaitPointerEvent()
                            if (!event.isRealPress) continue
                            // a resize handle is a child and gets the press first; if it took it,
                            // this gesture isn't ours
                            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                            val at = event.changes.firstOrNull { it.pressed } ?: continue
                            if (event.buttons.isSecondaryPressed) {
                                menuAt = with(density) { DpOffset(at.position.x.toDp(), at.position.y.toDp()) }
                                menuFor = columnsNow.getOrNull(
                                    columnAt(at.position.x, columnsNow, widthsNow, density, handlePx)
                                )
                                at.consume()
                                return@awaitEachGesture
                            }
                            down = at
                        }

                        // the drag keeps its **own** copy of the order.
                        //
                        // `onReorder` updates a StateFlow, and the composable that reads it hasn't
                        // recomposed yet — so `columnsNow` is still the pre-swap list for the rest
                        // of this frame. Doing the arithmetic against a stale list is what made the
                        // header jitter and fight the cursor.
                        val order = columnsNow.toMutableList()

                        fun widthOf(at: Int): Float = with(density) {
                            (order.getOrNull(at)?.let { widthsNow[it] } ?: Dimens.columnDefault).toPx()
                        }

                        fun startOf(at: Int): Float {
                            var x = 0f
                            for (i in 0 until at.coerceIn(0, order.size)) x += widthOf(i) + handlePx
                            return x
                        }

                        fun indexAt(x: Float): Int {
                            var edge = 0f
                            order.indices.forEach { i ->
                                edge += widthOf(i) + handlePx
                                if (x < edge) return i
                            }
                            return order.lastIndex.coerceAtLeast(0)
                        }

                        var index = indexAt(down.position.x)
                        // where in the column it was grabbed, so it stays under the pointer
                        val grab = down.position.x - startOf(index)
                        var dragging = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            val travelled = change.position.x - down.position.x
                            if (!dragging && abs(travelled) > COLUMN_DRAG_SLOP.toPx()) {
                                dragging = true
                                drag.column = order.getOrNull(index)
                            }
                            if (!dragging) continue
                            change.consume()

                            val wantedLeft = change.position.x - grab
                            val centre = wantedLeft + widthOf(index) / 2f

                            // swap when the dragged centre passes the **neighbour's** centre, not
                            // when it enters the neighbour's slot. That distinction is the whole
                            // trick: entering-the-slot oscillates, because after the swap the
                            // pointer is immediately back inside the slot it came from. Comparing
                            // centres leaves a gap of the dragged column's own width behind it,
                            // which is hysteresis for free.
                            //
                            // looped, so one fast flick can walk a column several places rather
                            // than lagging a step behind the cursor
                            while (true) {
                                if (index < order.lastIndex &&
                                    centre > startOf(index + 1) + widthOf(index + 1) / 2f
                                ) {
                                    onReorder(index, index + 1)
                                    order.add(index + 1, order.removeAt(index))
                                    index++
                                    continue
                                }
                                if (index > 0 &&
                                    centre < startOf(index - 1) + widthOf(index - 1) / 2f
                                ) {
                                    onReorder(index, index - 1)
                                    order.add(index - 1, order.removeAt(index))
                                    index--
                                    continue
                                }
                                break
                            }

                            drag.offset.floatValue = wantedLeft - startOf(index)
                        }

                        if (dragging) {
                            drag.column = null
                            drag.offset.floatValue = 0f
                        } else {
                            // a press that never travelled is a click, and a click on a header sorts
                            columnsNow
                                .getOrNull(columnAt(down.position.x, columnsNow, widthsNow, density, handlePx))
                                ?.let(onSort)
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { column ->
                val width = widths[column] ?: Dimens.columnDefault
                val isDragging = column == drag.column
                Row(
                    Modifier
                        .width(width)
                        .fillMaxHeight()
                        // the dragged header travels with the pointer and draws over its neighbours,
                        // the way a JTable's does. read in the placement lambda, not here: this
                        // moves every frame of a drag, and composing on it would recompose the bar
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset {
                            IntOffset(
                                if (column == drag.column) drag.offset.floatValue.roundToInt() else 0,
                                0,
                            )
                        }
                        // pressed rather than lifted: a JTable darkens the header it is carrying and
                        // rules its two edges, and a drop shadow here would fall on the first row
                        .background(if (isDragging) Colors.headerDragged else Color.Transparent)
                        .then(if (isDragging) Modifier.columnEdges(Colors.accent) else Modifier)
                        .padding(horizontal = Dimens.cellPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = header(column),
                        style = tableTextStyle(MaterialTheme.typography.labelMedium, Dimens.headerTextSize),
                        // the Swing header is the same weight as its rows — labelMedium's own
                        // Medium is a Material choice, not this table's
                        fontWeight = FontWeight.Normal,
                        color = Colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (column == sortedBy) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = if (sortAscending) "▲" else "▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = Colors.primaryText,
                        )
                    }
                }
                ColumnResizeHandle(width) { next -> onResize(column, next) }
            }
        }

        if (menu != null) {
            AppPopupMenu(
                expanded = menuFor != null,
                onDismissRequest = menuDismiss { menuFor = null },
                offset = menuAt,
            ) { menuFor?.let { menu(it) } }
        }
    }
}

/**
 * the draggable divider on a column's trailing edge.
 *
 * the drag target is [Dimens.resizeHandle] wide against a hairline rule, because a 1px target is a
 * target you miss.
 */
@Composable
private fun ColumnResizeHandle(width: Dp, onResize: (Dp) -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(Dimens.resizeHandle)
            .fillMaxHeight()
            .resizeCursor()
            .draggable(
                orientation = Orientation.Horizontal,
                // consumed, so the header's own gesture leaves a resize alone
                startDragImmediately = true,
                state = rememberDraggableState { delta ->
                    // px to Dp here rather than in the caller: the drag arrives in px and the widths
                    // are Dp, and converting in two places is how they drift apart
                    onResize((width + with(density) { delta.toDp() }).coerceAtLeast(Dimens.columnMin))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = Colors.headerDivider)
    }
}

/**
 * which column an x offset inside the table falls in, clamped to the ends.
 *
 * the header and the rows both need this — a right-click has to know which column it landed on
 * before it can offer a menu about it — and the two have to agree, or the menu would name a
 * different column than the one under the pointer. [handlePx] is the resize gap that follows every
 * column, which is part of the column's slot as far as a click is concerned.
 */
private fun <C> columnAt(
    x: Float,
    columns: List<C>,
    widths: Map<C, Dp>,
    density: Density,
    handlePx: Float,
): Int {
    var edge = 0f
    columns.indices.forEach { i ->
        edge += with(density) { (widths[columns[i]] ?: Dimens.columnDefault).toPx() } + handlePx
        if (x < edge) return i
    }
    return columns.lastIndex.coerceAtLeast(0)
}

/**
 * the two vertical rules a JTable draws down the sides of the column it is carrying.
 *
 * not `Modifier.border`, which would also rule the top and bottom of every cell and turn the moving
 * column into a stack of boxes rather than one column.
 */
private fun Modifier.columnEdges(color: Color): Modifier = drawWithContent {
    drawContent()
    val line = Dp.Hairline.toPx().coerceAtLeast(1f)
    drawRect(color, Offset.Zero, Size(line, size.height))
    drawRect(color, Offset(size.width - line, 0f), Size(line, size.height))
}

/**
 * a Material style at the table's own size and face.
 *
 * `Text(style = bodySmall, fontSize = 16.sp)` looks right and isn't: the *line height* stays at
 * bodySmall's 16sp, and a 16sp glyph in a line box exactly its own size loses its descenders.
 * Unspecified hands the line height back to the font, which is what a JTable row does.
 */
@Composable
private fun tableTextStyle(base: TextStyle, size: TextUnit): TextStyle =
    remember(base, size) {
        base.copy(fontFamily = Fonts.table, fontSize = size, lineHeight = TextUnit.Unspecified)
    }

@Composable
private fun <C> TableRow(
    columns: List<C>,
    widths: Map<C, Dp>,
    height: Dp,
    width: Dp,
    selected: Boolean,
    windowFocused: Boolean,
    contentColor: Color,
    /** the stripe this row sits in, for a dragged cell to repaint under itself. */
    bandColor: Color,
    drag: ColumnDrag<C>,
    tooltip: ((column: C, clipped: Boolean) -> String?)?,
    onPress: (toggle: Boolean, range: Boolean) -> Unit,
    onActivate: () -> Unit,
    onContextClick: () -> Unit,
    menu: (@Composable AppMenuScope.(column: C?) -> Unit)?,
    cell: @Composable (C) -> Unit,
) {
    val density = LocalDensity.current
    // where in this row the right-click landed, so the menu opens under the pointer like a native
    // one. measured in row-local coordinates, which is exactly what a Popup composed here anchors to
    var menuOpen by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf(DpOffset.Zero) }
    // and which column it landed in, so a menu can offer what only that column can
    var menuColumn by remember { mutableStateOf<C?>(null) }
    // read live inside the gesture rather than keying pointerInput on them — see the note on the
    // header's own handler
    val columnsNow by rememberUpdatedState(columns)
    val widthsNow by rememberUpdatedState(widths)
    val handlePx = with(density) { Dimens.resizeHandle.toPx() }
    // read inside the gesture without keying pointerInput on it — pressing a row flips `selected`,
    // and a key change restarts the handler, cancelling the gesture mid-press
    val selectedNow by rememberUpdatedState(selected)
    // a press on an already-selected row holds the whole selection until release, so a drag can
    // carry all of it; the collapse happens on release only if no drag intervened
    var pendingCollapse by remember { mutableStateOf(false) }

    val active = selected && windowFocused
    val background = when {
        active -> Colors.rowSelected
        selected -> Colors.rowSelectedInactive
        else -> Color.Transparent
    }
    val textColor = if (active) Colors.rowSelectedText else contentColor

    Box {
        Row(
            Modifier
                .width(width)
                .height(height)
                .background(background)
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
                                val at = event.changes.firstOrNull()?.position
                                if (at != null) {
                                    menuAt = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) }
                                    menuColumn = columnsNow.getOrNull(
                                        columnAt(at.x, columnsNow, widthsNow, density, handlePx)
                                    )
                                }
                                onContextClick()
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val modifiers = event.keyboardModifiers
                            val toggle = modifiers.isMetaPressed || modifiers.isCtrlPressed
                            val range = modifiers.isShiftPressed
                            if (selectedNow && !toggle && !range) {
                                pendingCollapse = true
                            } else {
                                onPress(toggle, range)
                            }
                            // mark it handled, so the container knows this wasn't empty space and
                            // doesn't clear what was just selected
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
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides textColor) {
                columns.forEach { column ->
                    val isDragging = column == drag.column
                    val cellModifier = Modifier
                        .width(widths[column] ?: Dimens.columnDefault)
                        .fillMaxHeight()
                        // a dragged column carries its cells with it, over the columns it is
                        // passing: drawn last, and opaque in this row's own colour, or the cell
                        // underneath shows through the one being moved
                        .zIndex(if (isDragging) 1f else 0f)
                        .offset {
                            IntOffset(
                                if (column == drag.column) drag.offset.floatValue.roundToInt() else 0,
                                0,
                            )
                        }
                        .then(
                            if (isDragging) {
                                Modifier
                                    .background(if (selected) background else bandColor)
                                    .columnEdges(Colors.accent)
                            } else {
                                Modifier
                            }
                        )
                    if (tooltip == null) {
                        Box(
                            cellModifier.padding(horizontal = Dimens.cellPadding),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            cell(column)
                        }
                    } else {
                        // the whole cell answers, not just the text in it: a short value in a wide
                        // column would otherwise only reply over its own few characters
                        CellTooltip(
                            text = { clipped -> tooltip(column, clipped) },
                            modifier = cellModifier,
                            contentPadding = PaddingValues(horizontal = Dimens.cellPadding),
                        ) {
                            cell(column)
                        }
                    }
                    Spacer(Modifier.width(Dimens.resizeHandle))
                }
            }
        }
        if (menu != null) {
            AppPopupMenu(
                expanded = menuOpen,
                onDismissRequest = menuDismiss { menuOpen = false },
                offset = menuAt,
            ) { menu(menuColumn) }
        }
    }
}

/**
 * a secondary (right) click, plus long-press for a finger.
 *
 * `detectTapGestures(onLongPress)` alone means holding the *left* button on desktop, which is not
 * what anyone does to open a context menu.
 */
@Composable
fun Modifier.contextClickable(onContextClick: (Offset) -> Unit): Modifier {
    // keyed on Unit, not on the lambda.
    //
    // a lambda that captures anything is a new object on every recomposition, and re-keying
    // `pointerInput` **restarts the handler and cancels any press in flight**. In a list that
    // recomposes ten times a second — a log window appending batches — a click then only lands if
    // it happens to fall between two recompositions. That is what made the filter list feel dead.
    val handler by rememberUpdatedState(onContextClick)
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val at = event.changes.firstOrNull()?.position ?: Offset.Zero
                        event.changes.forEach { it.consume() }
                        handler(at)
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { handler(it) })
        }
}

/**
 * the default cell: one line, clipped rather than wrapped, in whatever the row's colour is.
 *
 * Whether the text fitted is reported twice over: to [onLayout] for a caller that wants to know, and
 * into [LocalCellClipped] for whatever is wrapping the cell — which is what lets a tooltip answer
 * only for the values that don't fit without any column having to wire it up.
 *
 * @param onLayout told whether the text had to be clipped
 */
@Composable
fun TableText(
    text: String?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onLayout: ((clipped: Boolean) -> Unit)? = null,
) = TableText(AnnotatedString(text.orEmpty()), modifier, color, onLayout)

/** the same cell, with parts of the text already styled — a search hit, say. */
@Composable
fun TableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onLayout: ((clipped: Boolean) -> Unit)? = null,
) {
    val clipped = LocalCellClipped.current
    Text(
        text = text,
        modifier = modifier,
        style = tableTextStyle(MaterialTheme.typography.bodySmall, LocalTableTextSize.current),
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            onLayout?.invoke(result.hasVisualOverflow)
            // written during layout, and only when it changed: the one reader is a coroutine
            // waiting out the hover pause, so nothing recomposes on the back of it
            if (clipped != null && clipped.value != result.hasVisualOverflow) {
                clipped.value = result.hasVisualOverflow
            }
        },
    )
}

/**
 * every row whose vertical span the rubber-band crosses.
 *
 * only the y axis matters: rows are full width, so a horizontal component would just make the band
 * harder to use without selecting anything different.
 */
private fun rowsIntersecting(listState: LazyListState, y1: Float, y2: Float): IntRange {
    val top = minOf(y1, y2)
    val bottom = maxOf(y1, y2)
    val hits = listState.layoutInfo.visibleItemsInfo.filter { item ->
        item.offset < bottom && item.offset + item.size > top
    }
    if (hits.isEmpty()) return IntRange.EMPTY
    return hits.first().index..hits.last().index
}

/** the translucent rubber-band overlay. */
@Composable
private fun MarqueeRect(start: Offset, end: Offset) {
    val density = LocalDensity.current
    val left = minOf(start.x, end.x)
    val top = minOf(start.y, end.y)
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(density) { abs(end.x - start.x).toDp() },
                height = with(density) { abs(end.y - start.y).toDp() },
            )
            .background(Colors.accent.copy(alpha = 0.15f))
            .border(Dp.Hairline, Colors.accent.copy(alpha = 0.5f)),
    )
}

/** how many rows fit, for PageUp/PageDown — one less than visible, so a page keeps a line of context. */
private fun visibleRows(listState: LazyListState): Int =
    listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1).let {
        if (it > 1) it - 1 else PAGE_ROWS
    }

/**
 * scroll just far enough to bring [index] into view — never jump when the row is already visible.
 *
 * `requestScrollToItem`, not `scrollToItem`: the suspending one runs a real scroll, which sets
 * `isScrollInProgress` and so reads as a **gesture** to anything watching for one. The log viewer
 * decides Auto Scroll from where a scroll of the user's own landed, and moving the selection with
 * the keyboard is not one of those — ⌘↓ would have re-armed following purely because the row it
 * selected happens to be at the bottom. This lands in the next measure pass instead, which for an
 * un-animated jump looks identical.
 */
private fun LazyListState.requestReveal(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        requestScrollToItem(index)
        return
    }
    val first = visible.first().index
    val last = visible.last().index
    when {
        index <= first -> requestScrollToItem(index)
        index >= last -> requestScrollToItem((index - visible.size + 2).coerceAtLeast(0))
    }
}

/**
 * a click that reports whether a modifier was held, for a list that multi-selects.
 *
 * `clickable` throws the modifier state away, and reading `LocalWindowInfo.keyboardModifiers` at
 * click time is a frame late on some platforms.
 */
@Composable
fun Modifier.modifierClick(onClick: (toggle: Boolean) -> Unit): Modifier {
    // keyed on Unit — see the note on [contextClickable]
    val handler by rememberUpdatedState(onClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue
                if (!event.buttons.isPrimaryPressed) continue
                val modifiers = event.keyboardModifiers
                handler(modifiers.isMetaPressed || modifiers.isCtrlPressed)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * report when the pointer enters and leaves — for a fixed panel that shows what is under it.
 *
 * a hover, not a tooltip: the log window draws its own panel pinned to the table rather than a
 * floating bubble, so it needs the fact of the hover rather than a component to hang off.
 */
@Composable
fun Modifier.onHover(onHover: (Boolean) -> Unit): Modifier {
    val handler by rememberUpdatedState(onHover)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                when (awaitPointerEvent().type) {
                    PointerEventType.Enter -> handler(true)
                    PointerEventType.Exit -> handler(false)
                    else -> Unit
                }
            }
        }
    }
}
