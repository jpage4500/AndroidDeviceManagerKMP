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
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * one device's logcat.
 *
 * Two rows of controls — search, the level buttons, Auto-scroll, Pause and Save, then the filters
 * that are on as pills joined by AND — over the log table. See docs/REDESIGN.md.
 *
 * Everything that narrows the log is ANDed: the level floor, each saved filter that's on and each
 * quick filter a right-click made. That is the AND the Swing app applies to a multi-selection in
 * its filter list. **Search never narrows it** — it highlights, and steps through the hits — and
 * Save as filter is how a search becomes one of the pills.
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
    val paused by source.paused.collectAsState()
    val scrollsSideways by SettingsManager.getBoolAsState(AppPref.LOGS_SCROLL_SIDEWAYS)
    val savedFilters by LogFilterStore.filters.collectAsState()
    val columns by LogsTableState.visible.collectAsState()
    val widths by LogsTableState.widths.collectAsState()
    val copyToClipboard = rememberClipboardCopy()
    val listState = state.listState
    val focusManager = LocalFocusManager.current

    var search by state::search
    var selectedFilters by state::selectedFilters
    var quick by state::quick
    var floor by state::floor
    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var editing by remember { mutableStateOf<LogFilter?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var askingSave by remember { mutableStateOf(false) }
    var savedLog by remember { mutableStateOf<SavedLog?>(null) }
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
    // handed to the source rather than applied here: it keeps the filtered list incrementally, so a
    // batch only tests its own new lines instead of re-walking 50,000 of them ten times a second
    // an App rule matches the process name too, so new names re-run it — only then: a refreshed
    // process table would otherwise re-filter 50,000 lines for filters that never look at it
    val filterApps = if (active.any { it.usesApp }) apps else emptyMap()
    LaunchedEffect(active, filterApps) {
        source.setFilter(if (active.isEmpty()) null else { entry -> active.all { it.accepts(entry, filterApps) } })
    }

    val query = remember(search) { LogSearch.parse(search) }
    /**
     * the ids of the shown lines the search hits, oldest first — what the count and the arrows walk.
     *
     * counted off the UI thread, and again on every batch: a busy device sends ten a second, and a
     * walk of 50,000 lines is a few milliseconds that a frame shouldn't be paying. null while the
     * first count for a new search is still running, so the box doesn't flash "No matches".
     */
    val hits by produceState<List<Long>?>(null, shown, query, apps) {
        if (query == null) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.Default) { shown.filter { query.accepts(it, apps) }.map { it.id } }
    }
    // where the cursor sits among the hits, from 1
    val hitNumber = currentMatch?.let { id -> hits?.binarySearch(id)?.takeIf { it >= 0 }?.plus(1) }

    /** put the cursor on the line [id] and bring it on screen. */
    fun jumpTo(id: Long) {
        val index = shown.indexOfIdFrom(id)
        if (index < 0 || shown[index].id != id) return
        currentMatch = id
        autoScroll = false
        selection = TableSelection<Any?>().selectOnly(id)
        // `requestScrollToItem` for the same reason the table's own reveal uses it: a jump is not a
        // scroll of the user's own, and one that sets `isScrollInProgress` would have Auto Scroll
        // read it as one
        listState.requestScrollToItem(index)
    }

    // the next hit after the cursor, or after the top of the screen before there is one — ids only
    // ascend, so a cursor whose line has been trimmed away still says where "after" is. Wraps, the
    // way a find does
    fun nextMatch() {
        val ids = hits?.takeIf { it.isNotEmpty() } ?: return
        val from = currentMatch ?: ((listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Long)?.minus(1)) ?: -1L
        jumpTo(ids.firstOrNull { it > from } ?: ids.first())
    }

    fun previousMatch() {
        val ids = hits?.takeIf { it.isNotEmpty() } ?: return
        val from = currentMatch ?: ((listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? Long)?.plus(1)) ?: Long.MAX_VALUE
        jumpTo(ids.lastOrNull { it < from } ?: ids.last())
    }

    fun changeFont(size: Int) {
        fontSize = size.coerceIn(MIN_FONT, MAX_FONT)
        SettingsManager.setInt(AppPref.LOGS_FONT_SIZE, fontSize)
    }

    // the selected lines in the order they arrived, not the order they were clicked
    fun selectedEntries(): List<LogEntry> = if (selection.isEmpty) emptyList() else shown.filter { it.id in selection }

    fun copySelection() {
        val entries = selectedEntries().ifEmpty { return }
        copyToClipboard(entries.joinToString("\n") { it.asLine() })
    }

    // one line opens as its message, the way a double-click does; several keep their date and tag,
    // or the viewer would be a column of messages with nothing to say which came from where
    fun viewSelection() {
        val entries = selectedEntries().ifEmpty { return }
        WindowManager.openMessage(entries.singleOrNull()?.message ?: entries.joinToString("\n") { it.asLine() })
    }

    // ⌘S, from the menu bar
    LaunchedEffect(state.saveRequested) {
        if (!state.saveRequested) return@LaunchedEffect
        state.saveRequested = false
        askingSave = true
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
     * long as Auto Scroll is off, up to the `LogsSource.HOLD_LINES` budget past the buffer size. A budget
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
                    Key.F -> runCatching { searchFocus.requestFocus() }
                    Key.Plus, Key.Equals, Key.NumPadAdd -> changeFont(fontSize + 1)
                    Key.Minus, Key.NumPadSubtract -> changeFont(fontSize - 1)
                    Key.Zero, Key.NumPad0 -> changeFont(DEFAULT_FONT)
                    Key.Enter, Key.NumPadEnter -> {
                        autoScroll = !autoScroll
                        return@onPreviewKeyEvent true
                    }
                    // ⌘↓: the newest line, selected as the table would select it — and following
                    // again, since going to the end of a log is asking to watch it. Claimed here
                    // rather than left to the table, whose selection change turns following off.
                    // ⌘↑ and ⇧⌘↓ stay the table's: the oldest line, and extending to the newest
                    Key.DirectionDown -> {
                        if (event.isShiftPressed) return@onPreviewKeyEvent false
                        shown.lastOrNull()?.let { selection = TableSelection<Any?>().selectOnly(it.id) }
                        // the follow-the-tail effect lands on the last row as soon as this flips
                        autoScroll = true
                    }
                    // ⌘G / ⇧⌘G: the next or previous hit, from wherever the cursor last was
                    Key.G -> if (event.isShiftPressed) previousMatch() else nextMatch()
                    // ⌘C copies the selected lines and ⇧⌘V opens them in the viewer — except while
                    // a text field has the keyboard, where both are the field's own
                    Key.C -> {
                        if (fieldFocused || event.isShiftPressed || selection.isEmpty) return@onPreviewKeyEvent false
                        copySelection()
                    }
                    Key.V -> {
                        if (fieldFocused || !event.isShiftPressed || selection.isEmpty) return@onPreviewKeyEvent false
                        viewSelection()
                    }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        // the two rows of controls are one card, hanging off the open tab — the Files page's shape
        Column(
            Modifier
                .padding(horizontal = Dimens.pageGutter)
                .fillMaxWidth()
                .joinedCard(Colors.toolbarBg, Colors.sidebarBorder, Dimens.cardCorner, Dimens.hairline)
                .padding(top = Dimens.tabGap),
        ) {
            LogsToolbar(
                search = {
                    LogSearchField(
                        value = search,
                        onValueChange = {
                            search = it
                            currentMatch = null
                        },
                        focusRequester = searchFocus,
                        onFieldFocus = { fieldFocused = it },
                        hits = hits?.size,
                        current = hitNumber,
                        onNext = ::nextMatch,
                        onPrevious = ::previousMatch,
                        onSaveAsFilter = { query?.let { editing = LogFilter("", it.asExpression()) } },
                        modifier = Modifier.weight(1f).widthIn(min = Dimens.logSearchMinWidth),
                    )
                },
                floor = floor,
                onFloor = { floor = it },
                // a stream that has ended — the device went away — is started again by Resume
                showing = running && !paused,
                onPause = {
                    if (!running) source.start(streamScope) else source.setPaused(!paused)
                },
                onSave = { askingSave = true },
                menu = {
                    Item(stringResource(Res.string.logs_clear_log)) { source.clear() }
                    Separator()
                    Item(
                        stringResource(Res.string.logs_scroll_sideways),
                        leading = { color -> MenuTick(scrollsSideways, color) },
                    ) { SettingsManager.setBoolean(AppPref.LOGS_SCROLL_SIDEWAYS, !scrollsSideways) }
                    Separator()
                    Item(stringResource(Res.string.logs_text_bigger), shortcut = "⌘+") { changeFont(fontSize + 1) }
                    Item(stringResource(Res.string.logs_text_smaller), shortcut = "⌘−") { changeFont(fontSize - 1) }
                    Item(stringResource(Res.string.logs_text_reset), shortcut = "⌘0") { changeFont(DEFAULT_FONT) }
                },
            )
            val editLabel = stringResource(Res.string.logs_edit_filter)
            val duplicateLabel = stringResource(Res.string.logs_duplicate_filter)
            val removeLabel = stringResource(Res.string.remove)
            val pills = savedFilters.filter { it.name in selectedFilters }.map { filter ->
                val rules = filter.ruleCount
                ActiveFilter(
                    label = filter.name,
                    expression = filter.describe(apps),
                    quick = false,
                    detail = if (rules > 1) stringResource(Res.string.logs_rule_count, rules) else null,
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
                val words = LogFilter("", expression).describe(apps)
                ActiveFilter(
                    label = words,
                    expression = stringResource(Res.string.logs_quick_hint, words),
                    quick = true,
                    onClick = {
                        savingQuick = expression
                        editing = LogFilter("", expression)
                    },
                    onRemove = { quick = quick - expression },
                )
            }
            LogFilterBar(
                active = pills,
                saved = savedFilters,
                selected = selectedFilters,
                names = apps,
                onToggleSaved = { filter ->
                    selectedFilters = if (filter.name in selectedFilters) selectedFilters - filter.name else selectedFilters + filter.name
                },
                onEditSaved = { editing = it },
                onDeleteSaved = { filter ->
                    scope.launch {
                        confirm = ConfirmRequest(
                            title = getString(Res.string.logs_delete_filter_title),
                            message = getString(Res.string.logs_delete_filter_msg, filter.name),
                            onConfirm = {
                                selectedFilters = selectedFilters - filter.name
                                LogFilterStore.delete(filter.name)
                            },
                        )
                    }
                },
                onNewFilter = { editing = LogFilter("", "") },
                // the level is a control of its own, and stays where it was
                onClear = {
                    selectedFilters = emptySet()
                    quick = emptyList()
                },
            )
        }

        // the table in a card of its own, under the controls rather than joined to them
        val tableShape = RoundedCornerShape(Dimens.cardCorner)
        Row(Modifier.weight(1f).padding(start = Dimens.pageGutter, end = Dimens.pageGutter, top = Dimens.rowPadding)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(tableShape)
                    .background(Colors.listBg)
                    .border(Dimens.hairline, Colors.sidebarBorder, tableShape)
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
                rowTint = { logRowTint(it.level) },
                scrollsSideways = scrollsSideways,
                onSelectionChange = {
                    selection = it
                    // picking a line means you are reading it, not watching the tail
                    if (!it.isEmpty) autoScroll = false
                },
                onResize = { column, width -> LogsTableState.resize(column, width) },
                onReorder = { from, to -> LogsTableState.moveColumn(from, to) },
                onActivate = { WindowManager.openMessage(it.message) },
                rowMenu = { entry, column ->
                    // a right-click inside a multi-selection keeps it, and the menu is about all of
                    // it: what to do with those lines, and nothing about the one cell under the pointer
                    val many = selection.size
                    if (many > 1 && entry.id in selection) {
                        Item(stringResource(Res.string.logs_copy_lines, many), shortcut = "⌘C") { copySelection() }
                        Item(stringResource(Res.string.logs_view_lines, many), shortcut = "⇧⌘V") { viewSelection() }
                        return@DataTable
                    }
                    // the App column holds a pid; the menu names the process when it's known
                    // null when the click wasn't on a cell: the line's own items, and nothing else
                    val raw = column?.text(entry).orEmpty()
                    val shown = if (column == LogColumn.APP) apps[raw] ?: raw else raw
                    if (column != null && shown.isNotEmpty()) {
                        Header("${column.label.uppercase()} · ${shown.clip(MENU_VALUE_CHARS)}")
                    }

                    // filters about the cell that was clicked, first, since they're what the menu is
                    // for. Show only and Hide are quick filters: this tab's, unsaved, and a click on
                    // their pill saves them. A date offers none — no two lines share one
                    val filters = cellFilter(column, raw)
                    if (filters != null) {
                        val (only, hide) = filters
                        val message = column == LogColumn.MSG
                        Item(
                            if (message) stringResource(Res.string.logs_show_only_containing)
                            else stringResource(Res.string.logs_show_only, shown.clip(MENU_VALUE_CHARS)),
                        ) { if (only !in quick) quick = quick + only }
                        Item(
                            if (message) stringResource(Res.string.logs_hide_containing)
                            else stringResource(Res.string.logs_hide_value, shown.clip(MENU_VALUE_CHARS)),
                        ) { if (hide !in quick) quick = quick + hide }
                        Item(stringResource(Res.string.logs_new_filter_from)) {
                            // a saved filter outlives the process, so it names the app rather than
                            // the pid, where the name is known
                            val saved = if (column == LogColumn.APP && shown != raw) cellFilter(column, shown)?.first else null
                            editing = LogFilter("", saved ?: only)
                        }
                        Separator()
                    }
                    if (column == LogColumn.LEVEL) {
                        LogLevelFloor.of(entry.level)?.takeIf { it != LogLevelFloor.VERBOSE }?.let { level ->
                            Item(stringResource(Res.string.logs_filter_level, entry.level.orEmpty())) { floor = level }
                            Separator()
                        }
                    }

                    if (column != null && shown.isNotEmpty()) {
                        Item(stringResource(Res.string.logs_copy_value, column.label.lowercase())) { copyToClipboard(shown) }
                    }
                    Item(stringResource(Res.string.logs_copy_line)) { copyToClipboard(entry.asLine()) }
                    Item(stringResource(Res.string.logs_view_message)) {
                        WindowManager.openMessage(entry.message)
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
                            text = highlighted(entry.message, query?.needleFor(LogColumn.MSG), entry.id == currentMatch),
                            onLayout = { clipped = it },
                            overflow = TextOverflow.Clip,
                        )
                    }
                } else {
                    val value = when (column) {
                        // the App column carries a pid; show the process it belongs to when we know it
                        LogColumn.APP -> apps[entry.pid] ?: entry.pid
                        // a process's main thread has the pid for its tid — the UI thread, on an app.
                        // a dash says "main" without a second copy of a number already on the row
                        LogColumn.TID -> if (entry.tid != null && entry.tid == entry.pid) MAIN_THREAD else entry.tid
                        else -> column.text(entry)
                    }
                    TableText(
                        highlighted(value.orEmpty(), query?.needleFor(column), entry.id == currentMatch),
                        // a date's time and a package's last segment are the parts worth keeping
                        // when the column is too narrow
                        modifier = if (column == LogColumn.APP || column == LogColumn.DATE) Modifier.keepEnd() else Modifier,
                        overflow = TextOverflow.Clip,
                    )
                }
            }

            // pinned to the table's own edge at its own width — see MessagePanel
            MessagePanel(
                text = hovered?.message?.takeIf { showTooltip },
                atBottom = panelAtBottom,
            )
            }
        }

        LogsStatusBar(
            shown = shown.size,
            total = total,
            selected = selection.size,
            filtersOn = selectedFilters.size + quick.size,
            running = running,
            paused = paused,
            showTooltip = showTooltip,
            onTooltip = { showTooltip = it },
            autoScroll = autoScroll,
            // the follow-the-tail effect above replays the page as soon as this flips, so there is
            // no scroll to run here
            onAutoScroll = { autoScroll = it },
        )
    }

    ConfirmDialog(confirm, onDismiss = { confirm = null })

    val pickTitle = stringResource(Res.string.logs_save_pick)
    SaveLogDialog(
        open = askingSave,
        selected = selection.size,
        visible = shown.size,
        total = total,
        onDismiss = { askingSave = false },
        onSave = { what ->
            // taken now, before the file dialog: the log keeps moving while it is up
            val entries = when (what) {
                LogSaveScope.SELECTED -> shown.filter { it.id in selection }
                LogSaveScope.VISIBLE -> shown
                LogSaveScope.ALL -> source.all()
            }
            val device = DeviceManager.nameOf(deviceId).replace(Regex("[^A-Za-z0-9_-]"), "_")
            val name = "$device-${DateUtils.formatDate("yyyyMMdd-HHmmss")}.txt"
            scope.launch {
                val path = pickSaveFile(pickTitle, name, SettingsManager.getString(AppPref.LOGS_SAVE_DIR)?.ifBlank { null })
                    ?: return@launch
                // the next save starts where this one went
                SettingsManager.setString(AppPref.LOGS_SAVE_DIR, path.substringBeforeLast('/').substringBeforeLast('\\'))
                savedLog = SavedLog(path, entries.size, source.save(entries, path))
            }
        },
    )
    LogSavedDialog(savedLog, onDismiss = { savedLog = null })

    val floorLabel = stringResource(floor.label())
    FilterDialog(
        filter = editing,
        onDismiss = {
            editing = null
            savingQuick = null
        },
        onSave = { original, saved ->
            LogFilterStore.save(saved, replacing = original.name.ifBlank { null })
            // a new filter is turned on here — that's what it was made for. An edited one keeps
            // whether it was on, under its new name if it has one
            val wasOn = original.name.isBlank() || original.name in selectedFilters
            selectedFilters = (selectedFilters - original.name).let { if (wasOn) it + saved.name else it }
            savingQuick?.let { quick = quick - it }
            savingQuick = null
        },
        names = apps,
        // read once as the editor opens rather than per batch: it walks every held line
        apps = remember(editing, apps) {
            if (editing == null) emptyList() else source.pids().mapNotNull { apps[it] }.distinct().sortedBy { it.lowercase() }
        },
        count = { candidate ->
            // the others on in this tab — not the one being edited, nor the quick filter it replaces
            val others = active.filter { it.name != editing?.name && it.expression != savingQuick }
            FilterCount(
                alone = source.count { candidate.accepts(it, apps) },
                combined = if (others.isEmpty()) null else source.count { e -> candidate.accepts(e, apps) && others.all { it.accepts(e, apps) } },
                total = source.total.value,
                device = DeviceManager.nameOf(deviceId),
                // as their pills name them: the level by what it shows, a quick filter in words
                others = others.map { other ->
                    when {
                        other.name == floor.name && other.expression == floor.expression -> floorLabel
                        other.name == other.expression -> other.describe(apps)
                        else -> other.name
                    }
                },
            )
        },
    )
}

/**
 * the first row of a log tab's controls: search, the level buttons, Pause and Save…, and ⋯ for
 * what's used less — clearing the log, sideways scrolling and the text size.
 *
 * [showing] is whether lines are arriving on screen; Pause stops that and nothing else.
 */
@Composable
private fun LogsToolbar(
    search: @Composable RowScope.() -> Unit,
    floor: LogLevelFloor,
    onFloor: (LogLevelFloor) -> Unit,
    showing: Boolean,
    onPause: () -> Unit,
    onSave: () -> Unit,
    menu: @Composable AppMenuScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.toolbarHeight)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        search()
        Spacer(Modifier.width(Dimens.gapLarge))
        LevelFloorButtons(floor, onFloor)
        ToolbarRule()
        OutlinedAction(
            label = stringResource(if (showing) Res.string.logs_pause else Res.string.logs_resume),
            icon = if (showing) Res.drawable.pause else Res.drawable.play,
            onClick = onPause,
        )
        Spacer(Modifier.width(Dimens.gap))
        OutlinedAction(stringResource(Res.string.logs_save), icon = Res.drawable.file_save, onClick = onSave)
        Spacer(Modifier.width(Dimens.tabGap))
        MoreButton(menu)
    }
}

/** the hairline between groups of controls in the toolbar. */
@Composable
private fun ToolbarRule() {
    Box(
        Modifier
            .padding(horizontal = Dimens.gap)
            .size(Dimens.hairline, Dimens.chipHeight - Dimens.tabGap)
            .background(Colors.divider)
    )
}

/** ⋯ at the end of the toolbar, opening [menu]. */
@Composable
private fun MoreButton(menu: @Composable AppMenuScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.logs_more)
    Box {
        CellTooltip(text = { label }) {
            Box(
                Modifier
                    .size(Dimens.controlHeight)
                    .clip(RoundedCornerShape(Dimens.navCorner))
                    .clickable { open = true },
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Res.drawable.more, size = Dimens.icon, tint = Colors.toolbarIcon, contentDescription = label)
            }
        }
        AppPopupMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, Dimens.controlHeight)) {
            menu()
        }
    }
}

/** a menu item's tick, in the space a tick takes whether or not it's on. */
@Composable
private fun MenuTick(on: Boolean, color: Color) {
    Box(Modifier.size(Dimens.icon), contentAlignment = Alignment.Center) {
        if (on) AppIcon(Res.drawable.success, size = Dimens.pillClose, tint = color)
    }
}

/**
 * what's typed into search: text anywhere on the line, or `tag:` / `app:` to look in one column.
 *
 * `app:` looks for the process name, not the pid the column holds underneath — nobody searches for
 * 2063.
 */
private class LogSearch(val column: LogColumn?, val needle: String) {
    fun accepts(entry: LogEntry, apps: Map<String, String>): Boolean = when (column) {
        LogColumn.TAG -> matches(entry.tag, needle)
        LogColumn.APP -> matches(apps[entry.pid], needle) || matches(entry.pid, needle)
        else -> matches(entry.message, needle) || matches(entry.tag, needle) || matches(apps[entry.pid], needle)
    }

    /** what to highlight in [cell]: the needle, if this search looks in that column. */
    fun needleFor(cell: LogColumn): String? = needle.takeIf { column == null || column == cell }

    /**
     * the search as a filter expression, for Save as filter.
     *
     * a filter on App tests the process name as well as the pid, so `app:` carries over as it was
     * typed.
     */
    fun asExpression(): String = when (column) {
        LogColumn.TAG -> LogTermSpec(LogTerm.Field.TAG, LogOperator.CONTAINS, needle).asExpression()
        LogColumn.APP -> LogTermSpec(LogTerm.Field.APP, LogOperator.CONTAINS, needle).asExpression()
        else -> LogTermSpec(LogTerm.Field.ANY, LogOperator.CONTAINS, needle).asExpression()
    }

    companion object {
        fun parse(text: String): LogSearch? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val colon = trimmed.indexOf(':')
            val column = when (trimmed.substring(0, colon.coerceAtLeast(0)).lowercase()) {
                "tag" -> LogColumn.TAG
                "app" -> LogColumn.APP
                else -> null
            }
            if (column == null) return LogSearch(null, trimmed)
            return trimmed.substring(colon + 1).trim().takeIf { it.isNotEmpty() }?.let { LogSearch(column, it) }
        }
    }
}

/**
 * the plain line under the table: how many of the tab's lines are showing and how many filters are
 * doing that — then, on the right, whether lines are being kept while paused or not arriving at all,
 * and the two switches that are flipped while reading: the message panel and Auto-scroll. The device
 * is the open tab's name already, so it isn't said again here.
 */
@Composable
private fun LogsStatusBar(
    shown: Int,
    total: Int,
    selected: Int,
    filtersOn: Int,
    running: Boolean,
    paused: Boolean,
    showTooltip: Boolean,
    onTooltip: (Boolean) -> Unit,
    autoScroll: Boolean,
    onAutoScroll: (Boolean) -> Unit,
) {
    val parts = buildList {
        add(
            when {
                selected > 1 -> stringResource(Res.string.logs_status_selected, selected, shown)
                shown < total -> stringResource(Res.string.logs_status_shown_of, shown, total)
                else -> stringResource(Res.string.logs_status_shown, shown)
            }
        )
        if (filtersOn == 1) add(stringResource(Res.string.logs_status_filter))
        if (filtersOn > 1) add(stringResource(Res.string.logs_status_filters, filtersOn))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight + Dimens.gap)
            .padding(horizontal = Dimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
    ) {
        // a toast takes the count's place while it's up
        StatusToastSlot(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(
                text = parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val state = when {
            !running -> stringResource(Res.string.tab_stopped)
            paused -> stringResource(Res.string.logs_status_paused)
            else -> null
        }
        if (state != null) {
            Text(state, style = MaterialTheme.typography.labelSmall, color = Colors.secondaryText, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSwitch(showTooltip, stringResource(Res.string.logs_tooltip), onTooltip)
            AppSwitch(autoScroll, stringResource(Res.string.logs_auto_scroll), onAutoScroll)
        }
    }
}

/**
 * a right-click's Show only and Hide for the cell [value] is in, as expressions, or null where the
 * column isn't worth filtering on.
 *
 * the message is matched by **contains**, the rest by equality. A message is cut at the first `&&`,
 * `||` or quote: the expression syntax would read those as its own, and a filter on the text before
 * them still finds the line.
 */
private fun cellFilter(column: LogColumn?, value: String): Pair<String, String>? {
    val field = when (column) {
        LogColumn.TAG -> LogTerm.Field.TAG
        LogColumn.APP -> LogTerm.Field.APP
        LogColumn.TID -> LogTerm.Field.TID
        LogColumn.MSG -> LogTerm.Field.MSG
        else -> return null
    }
    val message = column == LogColumn.MSG
    val usable = if (!message) value else {
        val cut = listOf(LogFilter.AND, LogFilter.OR, "\"").map { value.indexOf(it) }.filter { it >= 0 }.minOrNull()
        (if (cut == null) value else value.substring(0, cut)).trim()
    }
    if (usable.isEmpty()) return null
    val (show, hide) = if (message) LogOperator.CONTAINS to LogOperator.NOT_CONTAINS else LogOperator.EQUALS to LogOperator.NOT_EQUALS
    return LogTermSpec(field, show, usable).asExpression() to LogTermSpec(field, hide, usable).asExpression()
}

/** [this] cut to [max] characters with an ellipsis, for a menu that has to stay a menu's width. */
private fun String.clip(max: Int): String = if (length <= max) this else take(max).trimEnd() + "…"

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

// what the Thread column shows for a process's main thread
private const val MAIN_THREAD = "-"

// the design's own cut-off for a value quoted in the right-click menu
private const val MENU_VALUE_CHARS = 26

private const val MIN_FONT = 8
private const val MAX_FONT = 28
private const val DEFAULT_FONT = 13
