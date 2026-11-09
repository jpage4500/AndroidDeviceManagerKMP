package com.jpage4500.devicemanager.manager

import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** which column the table is sorted on — a [TableColumn.id] — and which way. */
data class DeviceSort(val columnId: String = DeviceColumn.NAME.id, val ascending: Boolean = true)

/**
 * everything about the device table that isn't a device: which columns there are, which show, in
 * what order, how wide they are and what the rows are sorted on.
 *
 * separate from [DeviceManager] because none of it is about devices — it survives a transport change
 * and it is the half that persists. every setter writes through to [SettingsManager] as it is called,
 * which is cheap because a person changes a column, not a timer — with one exception: a **resize**
 * is a stream of changes rather than one, so its write is debounced. See [Debouncer].
 *
 * order, visibility, widths and sort are all held by [TableColumn.id], so a [CustomColumn] is laid
 * out by exactly the same code as a [DeviceColumn]. The built-ins' ids are their enum names — what
 * these prefs held before there were custom columns, so a saved layout still loads.
 */
object DeviceTableState {
    private val log = LoggerConfig.createLogger("DeviceTableState")

    // a column wide enough to hold the longest stack trace anyone ever pasted into a nickname is
    // not a useful column
    private val COLUMN_MAX = 480.dp

    private val _custom = MutableStateFlow(loadCustom())
    /** the columns someone added, in the order they were added. */
    val customColumns: StateFlow<List<CustomColumn>> = _custom.asStateFlow()

    private val _order = MutableStateFlow(loadOrder())

    private val _hidden = MutableStateFlow(loadHidden())
    /** the ids of the columns not drawn. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _widths = MutableStateFlow(loadWidths())
    /** the widths someone dragged to, by id; a column not in here is at its default. */
    val widths: StateFlow<Map<String, Dp>> = _widths.asStateFlow()

    private val _sort = MutableStateFlow(loadSort())
    val sort: StateFlow<DeviceSort> = _sort.asStateFlow()

    // a resize arrives once per pixel of the drag; the file it lands in doesn't need to
    private val widthWrite = Debouncer()

    // set by [publish] in the same call as the change behind them, never derived on another
    // dispatcher: a header drag reorders once per pointer event, and each step maps its indices
    // through these — read a step behind, the next swap undid the last and the column jumped back
    private val _all = MutableStateFlow(resolve(_order.value, _custom.value))
    private val _visible = MutableStateFlow(_all.value.filterNot { it.id in _hidden.value })

    /** every column, shown or not, in table order. */
    val all: StateFlow<List<TableColumn>> = _all.asStateFlow()

    /** the columns actually drawn, in order — what the table iterates. */
    val visible: StateFlow<List<TableColumn>> = _visible.asStateFlow()

    /** bring [all] and [visible] up to date with the order, the custom columns and the hidden set. */
    private fun publish() {
        _all.value = resolve(_order.value, _custom.value)
        _visible.value = _all.value.filterNot { it.id in _hidden.value }
    }

    fun widthOf(column: TableColumn): Dp = _widths.value[column.id] ?: column.defaultWidth.dp

    /**
     * sort by [column]; clicking the column already sorted reverses it.
     *
     * ascending first for a new column, which is what every table does — and what makes a second
     * click mean "no, the other way" rather than "did that do anything".
     */
    fun sortBy(column: TableColumn) {
        val current = _sort.value
        val next = if (current.columnId == column.id) current.copy(ascending = !current.ascending)
        else DeviceSort(column.id, ascending = true)
        setSort(next)
    }

    private fun setSort(sort: DeviceSort) {
        _sort.value = sort
        SettingsManager.setString(AppPref.DEVICE_SORT, "${sort.columnId},${if (sort.ascending) "asc" else "desc"}")
    }

    fun resize(column: TableColumn, width: Dp) {
        _widths.value = _widths.value + (column.id to width)
        widthWrite.submit { saveWidths() }
    }

    private fun saveWidths() {
        // stored as whole px: a fractional Dp reads as noise in a settings file someone opens
        val asMap = _widths.value.mapValues { (_, dp) -> dp.value.toInt() }
        SettingsManager.setString(AppPref.COLUMN_WIDTHS, JsonUtils.toJson(asMap) ?: "")
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
        val moved = shown.getOrNull(from)?.id ?: return
        val target = shown.getOrNull(to)?.id ?: return
        if (moved == target) return

        val order = all.value.map { it.id }.toMutableList()
        val fromIndex = order.indexOf(moved)
        if (fromIndex < 0) return
        order.removeAt(fromIndex)
        // after the removal the target's index has moved too; inserting *after* it going right and
        // *before* it going left is what puts the column where the pointer is
        val targetIndex = order.indexOf(target)
        if (targetIndex < 0) return
        order.add(if (from < to) targetIndex + 1 else targetIndex, moved)
        setOrder(order)
    }

    private fun setOrder(order: List<String>) {
        _order.value = order
        publish()
        SettingsManager.setString(AppPref.COLUMN_ORDER, order.joinToString(","))
    }

    fun toggleHidden(column: TableColumn) {
        val next = if (column.id in _hidden.value) _hidden.value - column.id else _hidden.value + column.id
        setHidden(next)
    }

    /**
     * hide exactly the columns in [ids] — Settings › Devices › Columns.
     *
     * never all of them: a table with nothing in it has no header to un-hide one from.
     */
    fun setHidden(ids: Set<String>) {
        if (all.value.all { it.id in ids }) {
            log.d("setHidden: refusing to hide every column")
            return
        }
        _hidden.value = ids
        publish()
        saveHidden()
    }

    private fun saveHidden() {
        // an empty set clears the pref rather than storing "", which reads back as one bad name
        SettingsManager.setString(AppPref.HIDDEN_COLUMNS, _hidden.value.takeIf { it.isNotEmpty() }?.joinToString(","))
    }

    /**
     * add [column] to the table, just after [after] when it's given — the header that was
     * right-clicked — and at the end otherwise. It shows straight away.
     *
     * its values are read by [DeviceManager], which watches [customColumns].
     */
    fun addCustom(column: CustomColumn, after: TableColumn? = null) {
        log.d("addCustom: ${column.label}: ${column.type} ${column.key}")
        val order = all.value.map { it.id }.toMutableList()
        val at = after?.let { order.indexOf(it.id) }?.takeIf { it >= 0 }?.plus(1) ?: order.size
        order.add(at, column.id)
        _custom.value = _custom.value + column
        publish()
        saveCustom()
        setOrder(order)
    }

    /** replace the custom column with [column]'s id — a new header, or a new thing to read. */
    fun updateCustom(column: CustomColumn) {
        log.d("updateCustom: ${column.label}: ${column.type} ${column.key}")
        _custom.value = _custom.value.map { if (it.id == column.id) column else it }
        publish()
        saveCustom()
    }

    /** take a custom column out of the table, and out of everything that remembered it. */
    fun removeCustom(column: CustomColumn) {
        log.d("removeCustom: ${column.label}")
        _custom.value = _custom.value.filterNot { it.id == column.id }
        publish()
        saveCustom()
        setOrder(_order.value - column.id)
        if (column.id in _hidden.value) {
            _hidden.value = _hidden.value - column.id
            publish()
            saveHidden()
        }
        if (column.id in _widths.value) {
            _widths.value = _widths.value - column.id
            saveWidths()
        }
        if (_sort.value.columnId == column.id) setSort(DeviceSort())
    }

    private fun saveCustom() {
        SettingsManager.setString(AppPref.CUSTOM_COLUMNS, _custom.value.takeIf { it.isNotEmpty() }?.let { JsonUtils.toJson(it) })
    }

    /**
     * widen (or narrow) [column] to whatever its widest value needs.
     *
     * the measuring is the caller's — only a composable has a `TextMeasurer` — so this just takes
     * the answer and clamps it to something a table can live with.
     */
    fun sizeToFit(column: TableColumn, contentWidth: Dp) {
        resize(column, (contentWidth + Dimens.cellPadding * 2).coerceIn(Dimens.columnMin, COLUMN_MAX))
    }

    /**
     * order, widths and visibility back to their defaults. Custom columns stay — they're something
     * someone made, not a layout — and go after the built-ins, shown.
     */
    fun resetColumns() {
        log.d("resetColumns: back to defaults")
        // or a resize from a moment ago would land on top of the defaults
        widthWrite.cancel()
        _order.value = emptyList()
        _hidden.value = DeviceColumn.defaultHidden.mapTo(HashSet()) { it.id }
        _widths.value = emptyMap()
        publish()
        SettingsManager.setString(AppPref.COLUMN_ORDER, null)
        SettingsManager.setString(AppPref.COLUMN_WIDTHS, null)
        saveHidden()
    }

    /** everything read back from the prefs again — after Settings › Reset has cleared them. */
    fun reload() {
        widthWrite.cancel()
        _custom.value = loadCustom()
        _order.value = loadOrder()
        _hidden.value = loadHidden()
        _widths.value = loadWidths()
        _sort.value = loadSort()
        publish()
    }

    /** the rows in the order the table draws them — nulls last, whichever way it's sorted. */
    fun sorted(devices: List<Device>): List<Device> {
        val (columnId, ascending) = _sort.value
        val column = all.value.firstOrNull { it.id == columnId } ?: DeviceColumn.NAME
        @Suppress("UNCHECKED_CAST")
        val comparator = compareBy<Device, Comparable<Any>?>(nullsLast()) {
            column.sortKey(it) as Comparable<Any>?
        }
        return devices.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    /**
     * [order]'s ids as columns: the ones that no longer exist dropped, and any column it doesn't
     * mention appended — so a layout saved by an older build still lists every column a newer one
     * adds, and a custom column the order never heard of still shows up.
     */
    private fun resolve(order: List<String>, custom: List<CustomColumn>): List<TableColumn> {
        val byId: Map<String, TableColumn> = (DeviceColumn.defaultOrder + custom).associateBy { it.id }
        val listed = order.distinct().mapNotNull { byId[it] }
        return listed + byId.values.filter { it !in listed }
    }

    private fun loadCustom(): List<CustomColumn> =
        JsonUtils.stringToList<CustomColumn>(SettingsManager.getString(AppPref.CUSTOM_COLUMNS))

    private fun loadOrder(): List<String> =
        SettingsManager.getString(AppPref.COLUMN_ORDER)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun loadHidden(): Set<String> {
        val stored = SettingsManager.getString(AppPref.HIDDEN_COLUMNS)
            ?: return DeviceColumn.defaultHidden.mapTo(HashSet()) { it.id }
        return stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun loadWidths(): Map<String, Dp> =
        JsonUtils.stringToMap<Int>(SettingsManager.getString(AppPref.COLUMN_WIDTHS)).mapValues { (_, px) -> px.dp }

    private fun loadSort(): DeviceSort {
        val stored = SettingsManager.getString(AppPref.DEVICE_SORT) ?: return DeviceSort()
        val id = stored.substringBefore(',').takeIf { it.isNotEmpty() } ?: return DeviceSort()
        return DeviceSort(id, ascending = stored.substringAfter(',', "asc") != "desc")
    }
}
