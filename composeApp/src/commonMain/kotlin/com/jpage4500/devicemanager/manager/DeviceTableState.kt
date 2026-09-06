package com.jpage4500.devicemanager.manager

import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*

/** which column the table is sorted on, and which way. */
data class DeviceSort(val column: DeviceColumn = DeviceColumn.NAME, val ascending: Boolean = true)

/**
 * everything about the device table that isn't a device: which columns show, in what order, how wide
 * they are and what the rows are sorted on.
 *
 * separate from [DeviceManager] because none of it is about devices — it survives a transport change
 * and it is the half that persists. every setter writes through to [SettingsManager] immediately,
 * which is cheap: a person changes a column, not a timer.
 */
object DeviceTableState {
    private val log = LoggerConfig.createLogger("DeviceTableState")

    private val _order = MutableStateFlow(loadOrder())
    val order: StateFlow<List<DeviceColumn>> = _order.asStateFlow()

    private val _hidden = MutableStateFlow(loadHidden())
    val hidden: StateFlow<Set<DeviceColumn>> = _hidden.asStateFlow()

    private val _widths = MutableStateFlow(loadWidths())
    val widths: StateFlow<Map<DeviceColumn, Dp>> = _widths.asStateFlow()

    private val _sort = MutableStateFlow(loadSort())
    val sort: StateFlow<DeviceSort> = _sort.asStateFlow()

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
        // stored as whole px: a fractional Dp reads as noise in a settings file someone opens
        val asMap = _widths.value.mapValues { (_, dp) -> dp.value.toInt() }
            .mapKeys { (column, _) -> column.name }
        SettingsManager.setString(AppPref.COLUMN_WIDTHS, JsonUtils.toJson(asMap) ?: "")
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

    fun resetColumns() {
        log.d("resetColumns: back to defaults")
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
