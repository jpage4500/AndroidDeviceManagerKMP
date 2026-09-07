package com.jpage4500.devicemanager.ui.components

import androidx.compose.runtime.*

/**
 * which rows are selected, as an immutable [Set] so `key in selection` is O(1) — the table asks that
 * question for every visible row on every frame, and a list scan would be the first thing to hurt
 * when the log viewer holds 100,000 lines.
 *
 * @param anchor where a shift-range starts, and where it stays while the range is dragged out
 * @param lead the keyboard cursor: the arrow keys move this one, and it is what a single-selection
 *   caller means by "the selected row"
 *
 * ported from FileExplorerKMP's `SelectionState`, generic over the key type so the device table, the
 * file explorer and the log viewer can all use it.
 */
@Immutable
data class TableSelection<K>(
    val keys: Set<K> = emptySet(),
    val anchor: K? = null,
    val lead: K? = null,
) {
    val size: Int get() = keys.size

    val isEmpty: Boolean get() = keys.isEmpty()

    operator fun contains(key: K): Boolean = key in keys

    fun selectOnly(key: K): TableSelection<K> = TableSelection(setOf(key), key, key)

    fun clear(): TableSelection<K> = TableSelection()

    fun selectAll(all: List<K>): TableSelection<K> =
        TableSelection(all.toSet(), all.firstOrNull(), all.lastOrNull())

    /** desktop click semantics: plain replaces, cmd/ctrl toggles, shift extends from the anchor. */
    fun onPress(key: K, rows: List<K>, toggle: Boolean, range: Boolean): TableSelection<K> = when {
        toggle -> {
            val next = if (key in keys) keys - key else keys + key
            // the toggled row becomes the anchor, so a shift-click after a cmd-click ranges from it
            TableSelection(next, key, key)
        }
        range -> rangeTo(key, rows)
        else -> selectOnly(key)
    }

    /** every row between the anchor and [key] inclusive, with the anchor left where it was. */
    fun rangeTo(key: K, rows: List<K>): TableSelection<K> {
        val from = anchor?.let { rows.indexOf(it) }?.takeIf { it >= 0 }
        val to = rows.indexOf(key).takeIf { it >= 0 }
        if (from == null || to == null) return selectOnly(key)
        val span = if (from <= to) from..to else to..from
        // LinkedHashSet: the order rows were added is the order a caller acting on the selection
        // should walk them in
        return TableSelection(span.mapTo(LinkedHashSet()) { rows[it] }, anchor, key)
    }
}
