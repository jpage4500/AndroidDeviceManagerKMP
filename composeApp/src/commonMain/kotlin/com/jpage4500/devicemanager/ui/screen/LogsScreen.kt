package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * one device's logcat.
 *
 * A toolbar with the level buttons, a filter bar under it — the filters that are on, as pills
 * joined by AND — then the log table, and the two switches that matter, Tooltip and Auto Scroll, in
 * the bottom-right corner. See docs/REDESIGN.md.
 *
 * Everything that narrows the log is ANDed: the level floor, each saved filter that's on, each
 * quick filter a right-click made, and the expression typed into the filter bar. That is the AND
 * the Swing app applies to a multi-selection in its filter list, and it is what makes "everything
 * at warn and above, mentioning gralloc" one step. "Search…" in the toolbar highlights instead.
 *
 * [view] is what the screen is showing — filters, search, scroll — kept by the Logs page's tab so
 * it survives the tab being left and come back to.
 *
 * Two things carry most of the usefulness:
 *
 * - **The whole row takes the level's colour**, not just the level cell. At 30,000 lines that cell
 *   is four pixels of a screen you are scanning; colouring the message is what makes an error
 *   findable.
 * - **Auto Scroll turns itself off when you scroll up**, and back on when you reach the bottom
 *   again. A log that yanks you back to the tail the moment you look at something is unusable, and
 *   a log you have to keep dragging down is nearly as bad.
 *
 * [owned] is a stream someone else started and will stop — a tab in the Logs page, whose log keeps
 * running while another tab is open. Without one this screen makes its own, and it lives as long as
 * the screen does. Pause and Resume start it again on [ownerScope], so the stream still belongs to
 * whoever owns it rather than to this composable.
 */
@Composable
fun LogsScreen(
    deviceId: String,
    modifier: Modifier = Modifier,
    owned: LogsSource? = null,
    ownerScope: CoroutineScope? = null,
    view: LogsView? = null,
) {
    val state = view ?: remember(deviceId) { LogsView() }
    val source = owned ?: remember(deviceId) { LogsSource(deviceId) }
    val scope = rememberCoroutineScope()
    val streamScope = ownerScope ?: scope
    val page by source.entries.collectAsState()
    val shown = page.entries
    val total by source.total.collectAsState()
    val apps by source.apps.collectAsState()
    val running by source.running.collectAsState()
    val holding by source.hold.collectAsState()
    val savedFilters by LogFilterStore.filters.collectAsState()
    val columns by LogsTableState.visible.collectAsState()
    val widths by LogsTableState.widths.collectAsState()
    val copyToClipboard = rememberClipboardCopy()
    val listState = state.listState
    val focusManager = LocalFocusManager.current

    var search by state::search
    var filterText by state::filterText
    var selectedFilters by state::selectedFilters
    var quick by state::quick
    var floor by state::floor
    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var editing by remember { mutableStateOf<LogFilter?>(null) }
    // the quick filter being saved through the editor, so saving it can take its dashed pill away
    var savingQuick by remember { mutableStateOf<String?>(null) }
    var autoScroll by state::autoScroll
    // read here, not per row: the row colour lambda runs once per visible row per frame
    val defaultTextColor = Colors.primaryText
    var showTooltip by remember { mutableStateOf(true) }
    var hovered by remember { mutableStateOf<LogEntry?>(null) }
    var panelAtBottom by remember { mutableStateOf(false) }
    // the pointer's y inside the table. written on every mouse move and **never read while
    // composing** — reading it there would recompose the whole screen on every move. Which edge the
    // panel sits on is decided once, when a hover starts
    val pointerY = remember { mutableStateOf(0f) }
    val panelHeightPx = with(LocalDensity.current) { MESSAGE_PANEL_HEIGHT.toPx() }
    var currentMatch by remember { mutableStateOf<Long?>(null) }
    val searchFocus = remember { FocusRequester() }
    // whether a text field already has the keyboard; typing-to-search must not steal from one
    var fieldFocused by remember { mutableStateOf(false) }
    var fontSize by remember {
        mutableStateOf(SettingsManager.getInt(AppPref.LOGS_FONT_SIZE).coerceIn(MIN_FONT, MAX_FONT))
    }

    // the level floor, the saved filters switched on here, and the right-click ones — every one of
    // them must pass. The built-in "Log Level …+" filters are the level buttons now
    val active = remember(savedFilters, selectedFilters, quick, floor) {
        listOfNotNull(floor.expression?.let { LogFilter(floor.name, it) }) +
            savedFilters.filter { it.name in selectedFilters && it.expression.isNotBlank() } +
            quick.map { LogFilter(it, it) }
    }
    // only the sidebar box filters; "Search…" highlights instead — a hit is only useful with the
    // lines around it, and filtering to the hits throws those away
    val typed = remember(filterText) {
        listOfNotNull(filterText.takeIf { it.isNotBlank() }?.let { LogFilter("typed", it) })
    }

    // handed to the source rather than applied here: it keeps the filtered list incrementally, so a
    // batch only tests its own new lines instead of re-walking 50,000 of them ten times a second
    LaunchedEffect(active, typed) {
        val terms = active + typed
        source.setFilter(if (terms.isEmpty()) null else { entry -> terms.all { it.accepts(entry) } })
    }

    if (owned == null) {
        LaunchedEffect(deviceId) { source.start(scope) }
        DisposableEffect(deviceId) { onDispose { source.stop() } }
    }

    /**
     * hold the oldest lines while they are being read.
     *
     * the trim is what moves the ground under a scrolled-up window, and the first half of not doing
     * that to someone is not doing it at all — [LogsSource.setHold] keeps the oldest lines for as
     * long as Auto Scroll is off, up to the `LOGS_HOLD_LINES` budget past the buffer size. A budget
     * rather than a promise, because a device printing thousands of lines a second would otherwise
     * take the heap with it; when it runs out the lines go again and the status bar says so.
     *
     * releasing it gives back up to a whole budget in one drop, which is why it is released by
     * following the tail again and nothing else: the jump lands where the user is already looking.
     */
    LaunchedEffect(autoScroll) { source.setHold(!autoScroll) }

    /**
     * follow the tail while Auto Scroll is on.
     *
     * **`requestScrollToItem`, not `scrollToItem`.** The suspending one runs a real scroll: it takes
     * the list's scroll mutex and sets `isScrollInProgress`, ten times a second on a busy device.
     * That is a scroll the user has to win the mutex against to get anywhere with the wheel, and a
     * signal indistinguishable from one of their own — which is what left the log pinned to the
     * bottom with no way to scroll off it. This one is applied in the next measure pass, in the same
     * pass as the rows it is following, so it neither runs a scroll nor lags the tail by a frame.
     *
     * collected rather than keyed on the last row's id: a `StateFlow` replays, so ticking the
     * checkbox lands on the tail without the click handler scrolling for itself. And keyed on
     * `shown.size` it stopped working outright once the buffer filled — a line arrives, the oldest
     * goes, and the size never changes again.
     */
    LaunchedEffect(source, listState, autoScroll) {
        if (!autoScroll) return@LaunchedEffect
        source.entries.collect { current ->
            // a scroll of the user's own outranks this one — it is about to turn Auto Scroll off,
            // and cancelling their fling to chase the tail is what this is here to stop
            if (listState.isScrollInProgress || current.entries.isEmpty()) return@collect
            listState.requestScrollToItem(current.entries.lastIndex)
        }
    }

    /**
     * hold the reading position while the ring buffer trims lines out from under it.
     *
     * `LazyColumn` re-finds its first visible row by key when rows come and go above it, but only
     * within about 130 rows either side of it (`NearestItemsExtraItemCount`) — and a busy device
     * sheds hundreds of lines per 100ms batch. Past that window the key lookup misses, the index is
     * kept as it was, and every batch jerks the log forward by however many lines it dropped. Which
     * is what reading the oldest lines on a busy device felt like.
     *
     * so the row that was at the top is put back at the top. The row is read from **`layoutInfo`**,
     * which only changes when the table lays out, so it is the row that was on screen however many
     * batches have queued up behind a slow frame — `firstVisibleItemIndex` also moves when a scroll
     * is *requested*, and would count this effect's own request as the user's position.
     *
     * `requestScrollToItem` rather than `scrollToItem` because it lands in the next measure pass
     * without a coroutine — and it tells the list to forget its remembered key, so this and
     * `LazyColumn`'s own anchoring can't both move the same row. Skipped while a drag or a fling is
     * running: a scroll of the user's own outranks this one.
     */
    LaunchedEffect(source) {
        source.entries.collect { current ->
            if (autoScroll || listState.isScrollInProgress) return@collect
            val top = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@collect
            val id = top.key as? Long ?: return@collect
            val index = current.entries.indexOfIdFrom(id)
            if (index < 0 || index == top.index) return@collect
            val offset = (listState.layoutInfo.viewportStartOffset - top.offset).coerceAtLeast(0)
            listState.requestScrollToItem(index, offset)
        }
    }

    /**
     * Auto Scroll follows the tail until a scroll of the user's own leaves the last row off screen,
     * and picks it up again when one brings the last row back — the Swing app's own wheel listener,
     * which unticks the box on a wheel-up and re-ticks it once the last row is visible again.
     *
     * Asked of the **layout** rather than of the direction of a wheel delta. The first version read
     * the delta's sign out of a `NestedScrollConnection`, which has two ways to be wrong and both
     * are invisible from here: a platform is free to sign its wheel either way, and a delta that a
     * scrollable dispatches raw never reaches a parent connection at all. Where the list *ended up*
     * has neither problem.
     *
     * **Gated on `isScrollInProgress`, which is the whole thing.** Ungated, this is the version that
     * turned itself off a moment after being clicked: a batch grows the list and for one frame the
     * last row is out of view. Only a scroll of the user's own sets that flag — following the tail is
     * a `requestScrollToItem`, which is applied at measure time and is not a scroll.
     */
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.isAtEnd() }
            .collect { (scrolling, atEnd) -> if (scrolling) autoScroll = atEnd }
    }

    Column(
        modifier
            .fillMaxSize()
            // ⌘+ / ⌘- / ⌘0, on the whole window: the table owns the keyboard once it has focus, so
            // a preview handler is what gets these before the arrow-key navigation sees them
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // Escape clears the search wherever focus is — including out of the field
                if (event.key == Key.Escape) {
                    search = ""
                    currentMatch = null
                    runCatching { focusManager.clearFocus() }
                    return@onPreviewKeyEvent true
                }

                if (!event.isMetaPressed && !event.isCtrlPressed) {
                    // typing anywhere searches: the first character lands here, then focus moves
                    // into the box so the rest is ordinary typing. skipped while a field already
                    // has the keyboard, or this would swallow what is being typed into it
                    if (fieldFocused) return@onPreviewKeyEvent false
                    val codePoint = event.utf16CodePoint
                    // 0xFFFF is what a key with no character reports — arrows, F-keys, modifiers
                    if (codePoint == 0xFFFF || codePoint < 0x20 || codePoint in 0x7F..0x9F) {
                        return@onPreviewKeyEvent false
                    }
                    search += codePoint.toChar()
                    currentMatch = null
                    runCatching { searchFocus.requestFocus() }
                    return@onPreviewKeyEvent true
                }
                // "⌘+" is ⌘⇧= on most layouts, so Equals counts as an increase too
                when (event.key) {
                    Key.Plus, Key.Equals, Key.NumPadAdd -> fontSize = (fontSize + 1).coerceAtMost(MAX_FONT)
                    Key.Minus, Key.NumPadSubtract -> fontSize = (fontSize - 1).coerceAtLeast(MIN_FONT)
                    Key.Zero, Key.NumPad0 -> fontSize = DEFAULT_FONT
                    Key.Enter, Key.NumPadEnter -> {
                        autoScroll = !autoScroll
                        return@onPreviewKeyEvent true
                    }
                    // ⌘↑ / ⌘↓ are deliberately **not** claimed here: they belong to the table, which
                    // selects the first or last row with them the same way it does in the device
                    // list and the file explorer. Selecting a line turns Auto Scroll off, so ⌘↓
                    // lands on the newest line and stays on it rather than resuming the chase —
                    // ⌘Enter is what re-arms following, and so does scrolling back to the bottom.
                    //
                    // ⌘G: the next line containing the search text, from wherever the last one was.
                    // an index walk, not `drop().indexOfFirst {}` — that copies the buffer, and the
                    // buffer is up to 50,000 lines
                    Key.G -> {
                        if (search.isBlank()) return@onPreviewKeyEvent true
                        val from = shown.indexOfFirst { it.id == currentMatch } + 1
                        var found = -1
                        for (i in from until shown.size) {
                            if (matches(shown[i].message, search)) {
                                found = i
                                break
                            }
                        }
                        // nothing after the cursor: wrap to the top, the way a find does
                        if (found < 0 && from > 0) {
                            for (i in 0 until from) {
                                if (matches(shown[i].message, search)) {
                                    found = i
                                    break
                                }
                            }
                        }
                        if (found >= 0) {
                            currentMatch = shown[found].id
                            autoScroll = false
                            selection = TableSelection<Any?>().selectOnly(shown[found].id)
                            // `requestScrollToItem` for the same reason the table's own reveal uses
                            // it: a jump is not a scroll of the user's own, and one that sets
                            // `isScrollInProgress` would have Auto Scroll read it as one
                            listState.requestScrollToItem(found)
                        }
                        return@onPreviewKeyEvent true
                    }
                    else -> return@onPreviewKeyEvent false
                }
                SettingsManager.setInt(AppPref.LOGS_FONT_SIZE, fontSize)
                true
            }
    ) {
        LogsToolbar(
            running = running,
            search = search,
            onSearch = {
                search = it
                currentMatch = null
            },
            searchFocus = searchFocus,
            onFieldFocus = { fieldFocused = it },
            onToggle = { if (running) source.stop() else source.start(streamScope) },
            onHide = { LogsTableState.toggleCompact() },
            onSave = {
                scope.launch {
                    source.save(shown, DeviceManager.nameOf(deviceId).replace(Regex("[^A-Za-z0-9_-]"), "_"))
                        ?.let { saved ->
                            StorageManager.getAbsolutePath(PATH_LOGS, saved)?.let(PlatformUtils::openUrl)
                        }
                }
            },
            onClear = { source.clear() },
            floor = floor,
            onFloor = { floor = it },
        )

        val editLabel = stringResource(Res.string.logs_edit_filter)
        val duplicateLabel = stringResource(Res.string.logs_duplicate_filter)
        val removeLabel = stringResource(Res.string.remove)
        val pills = savedFilters.filter { it.name in selectedFilters }.map { filter ->
            ActiveFilter(
                label = filter.name,
                expression = filter.expression,
                quick = false,
                onClick = { editing = filter },
                onRemove = { selectedFilters = selectedFilters - filter.name },
                menu = {
                    Item(editLabel) { editing = filter }
                    Item(duplicateLabel) { LogFilterStore.save(filter.copy(name = LogFilterStore.uniqueName(filter.name))) }
                    Separator()
                    Item(removeLabel) {
                        selectedFilters = selectedFilters - filter.name
                        LogFilterStore.delete(filter.name)
                    }
                },
            )
        } + quick.map { expression ->
            ActiveFilter(
                label = expression,
                expression = stringResource(Res.string.logs_quick_hint, expression),
                quick = true,
                onClick = {
                    savingQuick = expression
                    editing = LogFilter("", expression)
                },
                onRemove = { quick = quick - expression },
            )
        }
        Box(Modifier.fillMaxWidth().background(Colors.toolbarBg)) {
            LogFilterBar(
                filterText = filterText,
                onFilterText = { filterText = it },
                onFieldFocus = { fieldFocused = it },
                active = pills,
                saved = savedFilters,
                selected = selectedFilters,
                onToggleSaved = { filter ->
                    selectedFilters = if (filter.name in selectedFilters) selectedFilters - filter.name else selectedFilters + filter.name
                },
                onNewFilter = { editing = LogFilter("", "") },
                onClear = {
                    selectedFilters = emptySet()
                    quick = emptyList()
                    floor = LogLevelFloor.VERBOSE
                },
            )
        }
        HorizontalDivider(color = Colors.divider)

        Row(Modifier.weight(1f)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { pointerY.value = it.position.y }
                                if (event.type == PointerEventType.Exit) hovered = null
                            }
                        }
                    }
            ) {
            DataTable(
                rows = shown,
                columns = columns,
                widths = columns.associateWith { widths[it] ?: it.defaultWidth.dp },
                // a log is always in the order it arrived; sorting it by tag loses the one thing it
                // is for. the headers still resize and reorder
                sortedBy = null,
                sortAscending = true,
                selection = selection,
                rowKey = { it.id },
                header = { it.label },
                // the row grows with the text, or bigger text would clip against a fixed row
                rowHeight = (fontSize * 1.45f).dp,
                textSize = fontSize.sp,
                listState = listState,
                // how many lines have already dropped off the front, so the banding stays with the
                // row: the offset rises by exactly what every index fell by
                stripeOffset = page.dropped,
                rowContentColor = { logLevelColor(it.level, defaultTextColor) },
                onSelectionChange = {
                    selection = it
                    // picking a line means you are reading it, not watching the tail
                    if (!it.isEmpty) autoScroll = false
                },
                onResize = { column, width -> LogsTableState.resize(column, width) },
                onReorder = { from, to -> LogsTableState.moveColumn(from, to) },
                onActivate = { WindowManager.openMessage(it.message) },
                rowMenu = { entry, column ->
                    Item(stringResource(Res.string.copy_line)) { copyToClipboard(entry.asLine()) }
                    Item(stringResource(Res.string.logs_copy_message)) { copyToClipboard(entry.message) }
                    Item(stringResource(Res.string.logs_view_message)) {
                        WindowManager.openMessage(entry.message)
                    }
                    // filters about the cell that was clicked, and only in a column whose value is
                    // worth filtering on — the tag and the app, as the Swing menu offers, and the
                    // level as a floor. Show only and Hide are quick filters: this tab's, unsaved,
                    // and a click on their pill saves them
                    val field = when (column) {
                        LogColumn.TAG -> entry.tag?.let { LogTerm.Field.TAG to it }
                        LogColumn.APP -> entry.pid?.let { LogTerm.Field.APP to it }
                        else -> null
                    }
                    if (field != null) {
                        val (on, value) = field
                        // the App column holds a pid; the menu names the process when it's known
                        val named = if (on == LogTerm.Field.APP) apps[value] ?: value else value
                        val only = LogTermSpec(on, LogOperator.EQUALS, value).asExpression()
                        val hide = LogTermSpec(on, LogOperator.NOT_EQUALS, value).asExpression()
                        Separator()
                        Item(stringResource(Res.string.logs_show_only, named)) {
                            if (only !in quick) quick = quick + only
                        }
                        Item(stringResource(Res.string.logs_hide_value, named)) {
                            if (hide !in quick) quick = quick + hide
                        }
                        Item(stringResource(Res.string.logs_new_filter_from)) {
                            editing = LogFilter("", only)
                        }
                    }
                    if (column == LogColumn.LEVEL) {
                        LogLevelFloor.of(entry.level)?.takeIf { it != LogLevelFloor.VERBOSE }?.let { level ->
                            Separator()
                            Item(stringResource(Res.string.logs_filter_level, entry.level.orEmpty())) { floor = level }
                        }
                    }
                },
                headerMenu = { column ->
                    Item(stringResource(Res.string.hide_column, column.label)) {
                        LogsTableState.toggleHidden(column)
                    }
                    LogColumn.entries.filter { LogsTableState.isHidden(it) }.forEach { hidden ->
                        Item(stringResource(Res.string.show_column, hidden.label)) {
                            LogsTableState.toggleHidden(hidden)
                        }
                    }
                },
            ) { entry, column ->
                if (showTooltip && column == LogColumn.MSG) {
                    // only a message that doesn't fit gets a panel — for one you can already read
                    // in full, a panel is just something covering the rows above it
                    var clipped by remember { mutableStateOf(false) }
                    // the whole cell reports the hover, not just the text: a short message would
                    // otherwise only answer over its own few characters
                    Box(
                        Modifier.fillMaxSize().onHover { on ->
                            if (on) {
                                hovered = entry.takeIf { clipped }
                                // near the top of the table the panel would sit over the very row
                                // being pointed at, so it moves to the bottom edge instead
                                panelAtBottom = pointerY.value < panelHeightPx
                            } else if (hovered?.id == entry.id) {
                                hovered = null
                            }
                        },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TableText(
                            text = highlighted(entry.message, search, entry.id == currentMatch),
                            onLayout = { clipped = it },
                        )
                    }
                } else {
                    // the App column carries a pid; show the process it belongs to when we know it
                    val value = if (column == LogColumn.APP) apps[entry.pid] ?: entry.pid
                    else column.text(entry)
                    TableText(highlighted(value.orEmpty(), search, entry.id == currentMatch))
                }
            }

            // pinned to the table's own edge at its own width — see MessagePanel
            MessagePanel(
                text = hovered?.message?.takeIf { showTooltip },
                atBottom = panelAtBottom,
            )
            }
        }

        HorizontalDivider(color = Colors.divider)
        LogsStatusBar(
            shown = shown.size,
            total = total,
            hold = holding,
            selected = selection.size,
            expression = describe(active, typed),
            showTooltip = showTooltip,
            onTooltip = { showTooltip = it },
            autoScroll = autoScroll,
            // the follow-the-tail effect above replays the page as soon as this flips, so there is
            // no scroll to run here
            onAutoScroll = { autoScroll = it },
        )
    }

    FilterDialog(
        filter = editing,
        onDismiss = {
            editing = null
            savingQuick = null
        },
        onSave = { original, saved ->
            LogFilterStore.save(saved, replacing = original.name.ifBlank { null })
            // a renamed filter keeps its place among the ones that are on
            selectedFilters = selectedFilters - original.name + saved.name
            savingQuick?.let { quick = quick - it }
            savingQuick = null
        },
    )
}

@Composable
private fun LogsToolbar(
    running: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    searchFocus: FocusRequester,
    onFieldFocus: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onHide: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    floor: LogLevelFloor,
    onFloor: (LogLevelFloor) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.toolbarHeight)
            .background(Colors.toolbarBg)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(
            res = if (running) Res.drawable.pause else Res.drawable.play,
            label = stringResource(if (running) Res.string.logs_stop else Res.string.logs_start),
            onClick = onToggle,
            tint = Colors.toolbarIcon,
        )
        ToolbarButton(Res.drawable.eye_closed, stringResource(Res.string.logs_hide), onClick = onHide, tint = Colors.toolbarIcon)
        ToolbarButton(Res.drawable.file_save, stringResource(Res.string.logs_save), onClick = onSave, tint = Colors.toolbarIcon)
        Spacer(Modifier.width(Dimens.gapLarge))
        LevelFloorButtons(floor, onFloor)

        Spacer(Modifier.weight(1f))

        FilterField(
            value = search,
            onValueChange = onSearch,
            hint = stringResource(Res.string.logs_search),
            focusRequester = searchFocus,
            modifier = Modifier
                .onFocusChanged { onFieldFocus(it.isFocused) }
                .widthIn(min = 180.dp, max = 340.dp)
                .clip(RoundedCornerShape(Dimens.corner))
                .background(Colors.listBg)
                .border(Dp.Hairline, Colors.divider, RoundedCornerShape(Dimens.corner))
                .padding(horizontal = Dimens.gap, vertical = 5.dp),
        )
        Spacer(Modifier.width(Dimens.gap))
        ToolbarButton(Res.drawable.trash, stringResource(Res.string.logs_clear), onClick = onClear, tint = Colors.toolbarIcon)
    }
}

/**
 * what the filter is, in words — the Swing status bar's centre label.
 *
 * every selected filter's expression joined the way the syntax joins them, with the search box
 * quoted on the end, so what is on screen can be read back and retyped.
 */
private fun describe(active: List<LogFilter>, typed: List<LogFilter>): String =
    (active.map { it.expression } + typed.map { "\"${it.expression}\"" })
        .joinToString(" ${LogFilter.AND} ")

@Composable
private fun LogsStatusBar(
    shown: Int,
    total: Int,
    /** what the ring buffer's hold is doing, which is why [total] can read above the buffer size. */
    hold: LogHold,
    selected: Int,
    expression: String,
    showTooltip: Boolean,
    onTooltip: (Boolean) -> Unit,
    autoScroll: Boolean,
    onAutoScroll: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight)
            .background(Colors.statusBarBg)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "selected N of M" while a selection is live, else "viewing N", with "/ total" when a
        // filter is hiding some — the Swing left label exactly
        Text(
            text = when {
                selected > 1 -> stringResource(Res.string.logs_selected, selected, shown)
                shown < total -> stringResource(Res.string.logs_viewing_of, shown, total)
                else -> stringResource(Res.string.logs_viewing, shown)
            },
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
        )

        // both halves of the hold, said out loud: what it keeps is why the count can read above the
        // configured buffer size, and running out is why the oldest lines start moving again under
        // someone who is still reading them
        if (hold.lines > 0) {
            Spacer(Modifier.width(Dimens.gap))
            Text(
                text = when {
                    hold.full -> stringResource(Res.string.logs_hold_full)
                    else -> stringResource(Res.string.logs_hold, hold.lines)
                },
                style = MaterialTheme.typography.labelSmall,
                // the same split the expression label uses: primary for the thing worth reading,
                // secondary for the ambient counts either side of it
                color = if (hold.full) Colors.primaryText else Colors.secondaryText,
            )
        }

        // two even spacers put the expression in the middle and the switches hard right; a weighted
        // Text between them would reserve its share and leave a gap before the switches
        Spacer(Modifier.weight(1f))
        if (expression.isNotEmpty()) {
            Text(
                text = expression,
                style = MaterialTheme.typography.labelSmall,
                color = Colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        Spacer(Modifier.weight(1f))

        AppCheckbox(showTooltip, stringResource(Res.string.logs_tooltip), onTooltip)
        Spacer(Modifier.width(Dimens.gapLarge))
        AppCheckbox(autoScroll, stringResource(Res.string.logs_auto_scroll), onAutoScroll)
    }
}

/**
 * where [id] sits in [this], or the oldest line still held if it has already been trimmed away.
 *
 * a binary search rather than `indexOfFirst`: this runs once per batch, ten times a second, over as
 * many as 50,000 lines — and ids only ever ascend, whichever lines a filter let through.
 */
private fun List<LogEntry>.indexOfIdFrom(id: Long): Int {
    var low = 0
    var high = lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].id >= id) {
            found = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return found
}

/**
 * whether the list is parked at its end — the last row on screen, with nothing after it.
 *
 * an empty list counts as at the end: there is nothing to have scrolled away from, and a log window
 * that has just been cleared should carry on following.
 */
private fun LazyListState.isAtEnd(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1
}

/** the message panel's height, for deciding which edge it sits on. */
private val MESSAGE_PANEL_HEIGHT = 110.dp

private const val MIN_FONT = 8
private const val MAX_FONT = 28
private const val DEFAULT_FONT = 13
