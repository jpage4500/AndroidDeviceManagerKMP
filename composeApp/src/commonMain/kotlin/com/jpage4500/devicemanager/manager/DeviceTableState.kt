package com.jpage4500.devicemanager.manager

import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*

/** which column the table is sorted on, and which way. */
data class DeviceSort(val column: DeviceColumn = DeviceColumn.NAME, val ascending: Boolean = true)

/**
 * everything about the device table that isn't a device: which columns show, in what order, how wide
 * they are and what the rows are sorted on.
 *
 * separate from [DeviceManager] because none of it is about devices — it survives a transport change
 * and it is the half that persists. every setter writes through to [SettingsManager] as it is called,
 * which is cheap because a person changes a column, not a timer — with one exception: a **resize**
 * is a stream of changes rather than one, so its write is debounced. See [Debouncer].
 */
object DeviceTableState {
    private val log = LoggerConfig.createLogger("DeviceTableState")

    // a column wide enough to hold the longest stack trace anyone ever pasted into a nickname is
    // not a useful column
    private val COLUMN_MAX = 480.dp

    private val _order = MutableStateFlow(loadOrder())
    val order: StateFlow<List<DeviceColumn>> = _order.asStateFlow()

    private val _hidden = MutableStateFlow(loadHidden())
    val hidden: StateFlow<Set<DeviceColumn>> = _hidden.asStateFlow()

    private val _widths = MutableStateFlow(loadWidths())
    val widths: StateFlow<Map<DeviceColumn, Dp>> = _widths.asStateFlow()

    private val _sort = MutableStateFlow(loadSort())
    val sort: StateFlow<DeviceSort> = _sort.asStateFlow()

    // a resize arrives once per pixel of the drag; the file it lands in doesn't need to
    private val widthWrite = Debouncer()

    /** the columns actually drawn, in order — what the table iterates. */
    val visible: StateFlow<List<DeviceColumn>> = _order.combine(_hidden) { order, hidden ->
        order.filterNot { it in hidden }
    }.stateIn(
        scope = kotlinx.coroutines.CoroutineScope(ioDispatcher),
        started = SharingStarted.Eagerly,
        initialValue = _order.value.filterNot { it in _hidden.value },
    )

    fun widthOf(column: DeviceColumn): Dp = _widths.value[column] ?: column.defaultWidth.dp

    /**
     * sort by [column]; clicking the column already sorted reverses it.
     *
     * ascending first for a new column, which is what every table does — and what makes a second
     * click mean "no, the other way" rather than "did that do anything".
     */
    fun sortBy(column: DeviceColumn) {
        val current = _sort.value
        val next = if (current.column == column) current.copy(ascending = !current.ascending)
        else DeviceSort(column, ascending = true)
        _sort.value = next
        SettingsManager.setString(AppPref.DEVICE_SORT, "${next.column.name},${if (next.ascending) "asc" else "desc"}")
    }

    fun resize(column: DeviceColumn, width: Dp) {
        _widths.value = _widths.value + (column to width)
        widthWrite.submit {
            // stored as whole px: a fractional Dp reads as noise in a settings file someone opens
            val asMap = _widths.value.mapValues { (_, dp) -> dp.value.toInt() }
                .mapKeys { (each, _) -> each.name }
            SettingsManager.setString(AppPref.COLUMN_WIDTHS, JsonUtils.toJson(asMap) ?: "")
        }
    }

    /**
     * move the column at visible index [from] to visible index [to].
     *
     * the indices are into the *visible* list, which is what the header row draws — so they have to
     * be mapped back onto the full order before anything is written, or a hidden column sitting
     * between the two would be stepped over.
     */
    fun moveColumn(from: Int, to: Int) {
        val shown = visible.value
        val moved = shown.getOrNull(from) ?: return
        val target = shown.getOrNull(to) ?: return
        if (moved == target) return

        val order = _order.value.toMutableList()
        val fromIndex = order.indexOf(moved)
        if (fromIndex < 0) return
        order.removeAt(fromIndex)
        // after the removal the target's index has moved too; inserting *after* it going right and
        // *before* it going left is what puts the column where the pointer is
        val targetIndex = order.indexOf(target)
        if (targetIndex < 0) return
        order.add(if (from < to) targetIndex + 1 else targetIndex, moved)

        _order.value = order
        SettingsManager.setEnumOrder(AppPref.COLUMN_ORDER, order)
    }

    fun toggleHidden(column: DeviceColumn) {
        val next = if (column in _hidden.value) _hidden.value - column else _hidden.value + column
        // hiding the last column leaves a table with nothing in it and no header to un-hide from
        if (next.size >= DeviceColumn.entries.size) {
            log.d("toggleHidden: refusing to hide every column")
            return
        }
        _hidden.value = next
        SettingsManager.setEnumSet(AppPref.HIDDEN_COLUMNS, next)
    }

    /** hide exactly [columns] — Settings › Devices › Columns. never all of them, as [toggleHidden]. */
    fun setHidden(columns: Set<DeviceColumn>) {
        if (columns.size >= DeviceColumn.entries.size) {
            log.d("setHidden: refusing to hide every column")
            return
        }
        _hidden.value = columns
        SettingsManager.setEnumSet(AppPref.HIDDEN_COLUMNS, columns)
    }

    /**
     * widen (or narrow) [column] to whatever its widest value needs.
     *
     * the measuring is the caller's — only a composable has a `TextMeasurer` — so this just takes
     * the answer and clamps it to something a table can live with.
     */
    fun sizeToFit(column: DeviceColumn, contentWidth: Dp) {
        resize(column, (contentWidth + Dimens.cellPadding * 2).coerceIn(Dimens.columnMin, COLUMN_MAX))
    }

    fun resetColumns() {
        log.d("resetColumns: back to defaults")
        // or a resize from a moment ago would land on top of the defaults
        widthWrite.cancel()
        _order.value = DeviceColumn.defaultOrder
        _hidden.value = DeviceColumn.defaultHidden
        _widths.value = emptyMap()
        SettingsManager.setString(AppPref.COLUMN_ORDER, null)
        SettingsManager.setString(AppPref.COLUMN_WIDTHS, null)
        SettingsManager.setEnumSet(AppPref.HIDDEN_COLUMNS, DeviceColumn.defaultHidden)
    }

    /** the rows in the order the table draws them — nulls last, whichever way it's sorted. */
    fun sorted(devices: List<Device>): List<Device> {
        val (column, ascending) = _sort.value
        @Suppress("UNCHECKED_CAST")
        val comparator = compareBy<Device, Comparable<Any>?>(nullsLast()) {
            column.sortKey(it) as Comparable<Any>?
        }
        return devices.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    private fun loadOrder(): List<DeviceColumn> =
        SettingsManager.getEnumOrder<DeviceColumn>(AppPref.COLUMN_ORDER)

    private fun loadHidden(): Set<DeviceColumn> =
        if (SettingsManager.getString(AppPref.HIDDEN_COLUMNS) == null) DeviceColumn.defaultHidden
        else SettingsManager.getEnumSet(AppPref.HIDDEN_COLUMNS)

    private fun loadWidths(): Map<DeviceColumn, Dp> {
        val stored = JsonUtils.stringToMap<Int>(SettingsManager.getString(AppPref.COLUMN_WIDTHS))
        return stored.mapNotNull { (name, px) ->
            Utils.enumFromName<DeviceColumn>(name)?.let { it to px.dp }
        }.toMap()
    }

    private fun loadSort(): DeviceSort {
        val stored = SettingsManager.getString(AppPref.DEVICE_SORT) ?: return DeviceSort()
        val column = Utils.enumFromName<DeviceColumn>(stored.substringBefore(',')) ?: return DeviceSort()
        return DeviceSort(column, ascending = stored.substringAfter(',', "asc") != "desc")
    }
}
