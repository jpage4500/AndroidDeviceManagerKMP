package com.jpage4500.devicemanager.manager

import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.adb.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.transport.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.getString

/**
 * one device's `logcat`, buffered and filtered.
 *
 * Held per log window rather than globally: two windows may be watching two devices, and closing one
 * has to stop only that device's stream.
 *
 * **The buffer is a fixed-size ring, not a growing list.** A busy device prints thousands of lines a
 * second; without a cap the app would run itself out of heap in minutes, and re-allocating a list of
 * 100,000 entries per batch would cost more than the parsing.
 */
class LogsSource(private val deviceId: String) {
    private val log = LoggerConfig.createLogger("LogsSource")

    private val maxLines = SettingsManager.getInt(AppPref.LOGS_MAX_LINES).coerceAtLeast(1_000)

    /**
     * how far past [maxLines] the buffer may grow while the window is reading history.
     *
     * the trim is what moves the ground under someone scrolled up — so while they are reading, it
     * doesn't happen. **Bounded**, and 0 turns it off: a device printing thousands of lines a second
     * would otherwise take the heap with it, which is the whole reason the buffer is a ring.
     */
    private val holdLines = SettingsManager.getInt(AppPref.LOGS_HOLD_LINES).coerceAtLeast(0)
    private val buffer = ArrayDeque<LogEntry>(minOf(maxLines, 10_000))

    /**
     * the filtered view, kept **incrementally**.
     *
     * re-filtering the whole buffer on every batch is what a naive version does, and at 50,000 lines
     * ten times a second it is 500,000 predicate evaluations and a 50,000-element allocation per
     * second. Instead a batch only tests its own new lines, and the whole buffer is walked again
     * only when the filter itself changes.
     */
    private val matched = ArrayDeque<LogEntry>()
    private var filter: ((LogEntry) -> Boolean)? = null
    private var nextId = 0L

    /**
     * how many lines have left the **front** of [matched] since it was last rebuilt.
     *
     * two things need it, and both break without it: the table's row banding, which is an index plus
     * this or it inverts on every trim, and the log window's reading position, which is a row that
     * has to stay where it is while the ring drops lines above it.
     */
    private var dropped = 0L

    /** whether the window is reading history rather than following the tail — see [setHold]. */
    private var holding = false

    private val _entries = MutableStateFlow(LogPage())

    /** what the table shows: the buffer with the filter applied. */
    val entries: StateFlow<LogPage> = _entries.asStateFlow()

    private val _total = MutableStateFlow(0)

    /**
     * how many lines are held, filtered or not — the "/ N" in the status bar.
     *
     * this can read above the configured buffer size: [setHold] keeps the oldest lines while the
     * window is reading them, and [hold] is what says so.
     */
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _paused = MutableStateFlow(false)

    /**
     * reading on, showing off — Pause.
     *
     * the stream and the buffer carry on, so nothing logged while paused is lost and Resume shows it
     * all; only [entries] stands still. Stopping the stream instead is what Pause used to do, and it
     * left a gap in the log exactly where someone had stopped to look at it.
     */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(on: Boolean) {
        if (_paused.value == on) return
        _paused.value = on
        // whatever arrived meanwhile goes out in one snapshot
        if (!on) publish()
    }

    private val _hold = MutableStateFlow(LogHold())

    /** how many of the oldest lines the hold is keeping, and whether it has run out. */
    val hold: StateFlow<LogHold> = _hold.asStateFlow()

    private val _apps = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * pid -> process name, so the App column reads `com.android.systemui` rather than `2063`.
     *
     * logcat only carries the pid; the Swing app resolves it the same way. Resolved at *display*
     * time rather than folded into each entry, so a name that arrives late shows up on lines already
     * on screen instead of only on new ones.
     */
    val apps: StateFlow<Map<String, String>> = _apps.asStateFlow()

    private var job: Job? = null
    private var appsJob: Job? = null

    /** narrow what [entries] shows. null shows everything. */
    fun setFilter(predicate: ((LogEntry) -> Boolean)?) {
        filter = predicate
        matched.clear()
        if (predicate == null) matched.addAll(buffer) else buffer.filterTo(matched, predicate)
        // a different filter is a different list — nothing kept its position in it, so the count of
        // what has fallen off the front starts again
        dropped = 0L
        _entries.value = LogPage(matched.toList(), dropped)
    }

    /**
     * how many held lines [predicate] passes, filtered or not — the filter editor's live count.
     *
     * walks the whole buffer, so it is for a person typing, not for every batch. On the thread that
     * fills the buffer, like [setFilter].
     */
    fun count(predicate: (LogEntry) -> Boolean): Int = buffer.count(predicate)

    /**
     * keep the oldest lines rather than trimming them, because the window is reading them.
     *
     * on while the log window isn't following the tail. Turning it **off** gives back everything the
     * hold was keeping in one go, which is a drop of up to [holdLines] at once — the window is
     * following the tail again by then, so the jump lands where the user is already looking.
     */
    fun setHold(on: Boolean) {
        if (holding == on) return
        holding = on
        val changed = trim()
        // the count moves even when nothing the table can see does — the lines given back may all
        // be ones a filter was hiding
        _total.value = buffer.size
        if (changed && !_paused.value) publish()
        _hold.value = heldNow()
    }

    /**
     * bring the buffer back to the limit in force, oldest first.
     *
     * once per batch rather than per line: the buffer carries a batch of slack for the length of the
     * loop that fills it and the arithmetic comes out the same.
     *
     * @return whether anything left the **filtered** list, which is the only thing the table sees
     */
    private fun trim(): Boolean {
        val limit = if (holding) maxLines + holdLines else maxLines
        var changed = false
        while (buffer.size > limit) {
            val evicted = buffer.removeFirst()
            // the filtered list is a subsequence, so an evicted line can only ever be its first
            // element
            if (matched.firstOrNull()?.id == evicted.id) {
                matched.removeFirst()
                dropped++
                changed = true
            }
        }
        return changed
    }

    /** the filtered list as the table sees it; the caller keeps [total] in step. */
    private fun publish() {
        _entries.value = LogPage(matched.toList(), dropped)
    }

    // the hold is only worth reporting while it is holding something: what it keeps is why the line
    // count can read above the buffer size, and running out is why the oldest lines start moving
    // again under someone still reading them
    private fun heldNow(): LogHold {
        if (!holding || holdLines == 0) return LogHold()
        val over = (buffer.size - maxLines).coerceAtLeast(0)
        return LogHold(lines = over, full = over >= holdLines)
    }

    /** start reading, on [scope] so the window's lifetime owns the stream. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        // an adb server runs `logcat` and a Device Manager server pushes it over /ws/logs; a
        // connection with neither — the test devices — has no log to show, and an empty window with
        // no explanation is the worst way to say so
        if (!DeviceManager.canStreamLogs(deviceId)) {
            log.w("start: $deviceId can't stream logs")
            scope.launch {
                ToastManager.showToast(getString(Res.string.logs_no_stream), success = false)
            }
            return
        }
        _running.value = true
        _paused.value = false
        startAppNames(scope)
        job = scope.launch {
            DeviceManager.logcat(deviceId).collect { batch ->
                // merged rather than replaced: a map that arrives mid-batch is what the far end knew
                // a moment ago, and a name already resolved is better than no name
                if (batch.processes.isNotEmpty()) _apps.value = _apps.value + batch.processes
                if (batch.lines.isEmpty()) return@collect
                var changed = false
                batch.lines.forEach { line ->
                    val entry = LogEntry.parse(line, nextId++)
                    buffer.addLast(entry)
                    if (filter?.invoke(entry) != false) {
                        matched.addLast(entry)
                        changed = true
                    }
                }
                if (trim()) changed = true
                _hold.value = heldNow()
                _total.value = buffer.size
                // one snapshot per batch, not per line — see the note on adbLogcat
                if (changed && !_paused.value) publish()
            }
            _running.value = false
        }
    }

    /**
     * keep the pid table fresh, for a connection that doesn't send it.
     *
     * processes start and die while a log runs, so one read at the beginning goes stale — but `ps`
     * on a busy device isn't free either, so it is re-read on a slow timer rather than per batch.
     *
     * A Device Manager server puts the map on the log socket itself, so this doesn't run there:
     * `ps` through `/api/execute` would be a shell command on someone else's machine, twice a
     * minute, for an answer already arriving with the lines.
     */
    private fun startAppNames(scope: CoroutineScope) {
        if (appsJob != null) return
        if (DeviceManager.hasProcessNames(deviceId)) {
            log.d("startAppNames: $deviceId sends its own")
            return
        }
        appsJob = scope.launch {
            while (isActive) {
                val output = DeviceManager.exec(deviceId, CMD_PS).getOrNull()
                if (output != null) _apps.value = parseProcessList(output)
                delay(APPS_REFRESH_MS)
            }
        }
    }

    fun stop() {
        log.d("stop: $deviceId")
        job?.cancel()
        job = null
        appsJob?.cancel()
        appsJob = null
        _running.value = false
    }

    fun clear() {
        buffer.clear()
        matched.clear()
        dropped = 0L
        _hold.value = LogHold()
        _total.value = 0
        _entries.value = LogPage()
    }

    /**
     * write what is on screen to a file in the app's `logs` folder.
     *
     * what is *on screen*, not what is in the buffer: someone saving a filtered log wants the filter
     * applied, which is the whole reason they narrowed it.
     *
     * @return the file name, or null if it couldn't be written
     */
    suspend fun save(entries: List<LogEntry>, name: String): String? {
        val fileName = "$name-${DateUtils.formatDate("yyyyMMdd-HHmmss")}.txt"
        StatusManager.post("Saving $fileName", progress = 0)
        val body = entries.joinToString("\n") { it.asLine() }
        val ok = StorageManager.writeBytes(PATH_LOGS, fileName, body.encodeToByteArray())
        StatusManager.post(
            if (ok) "Saved $fileName" else "Couldn't save $fileName",
            progress = 100,
            isError = !ok,
        )
        return fileName.takeIf { ok }
    }

    companion object {
        // -o PID,NAME needs Android 8+; the bare `ps` fallback puts the name last on each line,
        // which the parser handles either way
        private const val CMD_PS = "ps -A -o PID,NAME 2>/dev/null || ps"
        private const val APPS_REFRESH_MS = 30_000L

        /**
         * `  2063 com.android.systemui` per line, header skipped.
         *
         * the bare `ps` output has the name as the **last** column and a pid as the first number, so
         * taking the first number and the last field reads both shapes.
         */
        fun parseProcessList(output: String): Map<String, String> = output.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val pid = parts.first().takeIf { it.toIntOrNull() != null } ?: return@mapNotNull null
                val name = parts.last().takeIf { it.isNotBlank() && !it.startsWith('[') }
                    ?: return@mapNotNull null
                pid to name
            }
            .toMap()
    }
}

/** which columns the log table shows, how wide, and what it is sorted on — persisted. */
object LogsTableState {
    private val _order = MutableStateFlow(
        SettingsManager.getEnumOrder<LogColumn>(AppPref.LOGS_COLUMN_ORDER)
    )
    val order: StateFlow<List<LogColumn>> = _order.asStateFlow()

    private val _hidden = MutableStateFlow(
        if (SettingsManager.getString(AppPref.LOGS_HIDDEN_COLUMNS) == null) LogColumn.defaultHidden
        else SettingsManager.getEnumSet(AppPref.LOGS_HIDDEN_COLUMNS)
    )
    val hidden: StateFlow<Set<LogColumn>> = _hidden.asStateFlow()

    private val _widths = MutableStateFlow(loadWidths())
    val widths: StateFlow<Map<LogColumn, Dp>> = _widths.asStateFlow()

    // a resize arrives once per pixel of the drag; the file it lands in doesn't need to
    private val widthWrite = Debouncer()

    val visible: StateFlow<List<LogColumn>> = _order.combine(_hidden) { order, hidden ->
        order.filterNot { it in hidden }
    }.stateIn(
        scope = CoroutineScope(ioDispatcher),
        started = SharingStarted.Eagerly,
        initialValue = _order.value.filterNot { it in _hidden.value },
    )

    fun resize(column: LogColumn, width: Dp) {
        _widths.value = _widths.value + (column to width)
        widthWrite.submit {
            val asMap = _widths.value.entries.associate { (key, dp) -> key.name to dp.value.toInt() }
            SettingsManager.setString(AppPref.LOGS_COLUMN_WIDTHS, JsonUtils.toJson(asMap) ?: "")
        }
    }

    fun toggleHidden(column: LogColumn) {
        val next = if (column in _hidden.value) _hidden.value - column else _hidden.value + column
        if (next.size >= LogColumn.entries.size) return
        _hidden.value = next
        SettingsManager.setEnumSet(AppPref.LOGS_HIDDEN_COLUMNS, next)
    }

    fun isHidden(column: LogColumn): Boolean = column in _hidden.value

    fun moveColumn(from: Int, to: Int) {
        val shown = visible.value
        val moved = shown.getOrNull(from) ?: return
        val target = shown.getOrNull(to) ?: return
        if (moved == target) return
        val order = _order.value.toMutableList()
        val fromIndex = order.indexOf(moved)
        if (fromIndex < 0) return
        order.removeAt(fromIndex)
        val targetIndex = order.indexOf(target)
        if (targetIndex < 0) return
        order.add(if (from < to) targetIndex + 1 else targetIndex, moved)
        _order.value = order
        SettingsManager.setEnumOrder(AppPref.LOGS_COLUMN_ORDER, order)
    }

    private fun loadWidths(): Map<LogColumn, Dp> =
        JsonUtils.stringToMap<Int>(SettingsManager.getString(AppPref.LOGS_COLUMN_WIDTHS))
            .mapNotNull { (name, px) -> Utils.enumFromName<LogColumn>(name)?.let { it to px.dp } }
            .toMap()
}

/** the saved filters down the side of the log window. */
object LogFilterStore {
    private val log = LoggerConfig.createLogger("LogFilterStore")

    private val _filters = MutableStateFlow(load())
    val filters: StateFlow<List<LogFilter>> = _filters.asStateFlow()

    fun save(filter: LogFilter, replacing: String? = null) {
        log.d("save: ${filter.name}")
        val without = _filters.value.filterNot { it.name == (replacing ?: filter.name) }
        persist(without + filter)
    }

    fun delete(name: String) = persist(_filters.value.filterNot { it.name == name })

    fun setEnabled(name: String, enabled: Boolean) =
        persist(_filters.value.map { if (it.name == name) it.copy(enabled = enabled) else it })

    /** a name that isn't taken, for Duplicate. */
    fun uniqueName(base: String): String {
        if (_filters.value.none { it.name == base }) return base
        var index = 2
        while (_filters.value.any { it.name == "$base $index" }) index++
        return "$base $index"
    }

    private fun persist(list: List<LogFilter>) {
        _filters.value = list
        SettingsManager.setString(AppPref.LOGS_FILTERS, JsonUtils.toJson(list))
    }

    private fun load(): List<LogFilter> =
        JsonUtils.stringToList(SettingsManager.getString(AppPref.LOGS_FILTERS))
}
