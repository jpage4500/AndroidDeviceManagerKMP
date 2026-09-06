package com.jpage4500.devicemanager.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.ui.theme.*

/**
 * a real data table: click a header to sort, drag its edge to resize, right-click it for the column
 * menu.
 *
 * Compose has none of this — `LazyColumn` gives scrolling and nothing else — so everything a Swing
 * `JTable` hands over for free is built here, once. Written generic over the row type [T] and the
 * column type [C] on purpose: the device list, the file explorer and the log viewer are all tables,
 * and this is the difference between writing three of them and writing one.
 *
 * Widths are in **px as Dp**, held by the caller so they can be persisted. The last column is not
 * stretched to fill: a fleet of 45 rows is read by scanning down one column, and a column that
 * changes width when a device is plugged in is a column you have to find again.
 */
@Composable
fun <T, C> DataTable(
    rows: List<T>,
    columns: List<C>,
    widths: Map<C, Dp>,
    sortedBy: C?,
    sortAscending: Boolean,
    rowKey: (T) -> Any,
    header: (C) -> String,
    modifier: Modifier = Modifier,
    selectedKey: Any? = null,
    rowHeight: Dp = Dimens.rowHeight,
    onSort: (C) -> Unit = {},
    onResize: (C, Dp) -> Unit = { _, _ -> },
    onRowClick: (T) -> Unit = {},
    onRowDoubleClick: (T) -> Unit = {},
    onHeaderContextClick: (C) -> Unit = {},
    onRowContextClick: (T) -> Unit = {},
    cell: @Composable (row: T, column: C) -> Unit,
) {
    // one shared scroll offset for the header and the body, so a column can't drift out of line
    val horizontalScroll = rememberScrollState()
    val totalWidth = columns.sumOf { (widths[it] ?: Dimens.columnDefault).value.toDouble() }.dp

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
                onContextClick = onHeaderContextClick,
            )
        }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f).horizontalScroll(horizontalScroll)) {
            itemsIndexed(rows, key = { _, row -> rowKey(row) }) { index, row ->
                TableRow(
                    columns = columns,
                    widths = widths,
                    index = index,
                    height = rowHeight,
                    width = totalWidth,
                    selected = selectedKey != null && rowKey(row) == selectedKey,
                    onClick = { onRowClick(row) },
                    onDoubleClick = { onRowDoubleClick(row) },
                    onContextClick = { onRowContextClick(row) },
                ) { column ->
                    cell(row, column)
                }
            }
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
    onContextClick: (C) -> Unit,
) {
    Row(Modifier.height(Dimens.headerHeight), verticalAlignment = Alignment.CenterVertically) {
        columns.forEach { column ->
            val width = widths[column] ?: Dimens.columnDefault
            Row(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .clickable { onSort(column) }
                    .pointerInput(column) {
                        detectTapGestures(onLongPress = { onContextClick(column) })
                    }
                    .padding(horizontal = Dimens.cellPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = header(column),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (column == sortedBy) {
                    Text(
                        text = if (sortAscending) " ▲" else " ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            ColumnResizeHandle(
                width = width,
                onResize = { delta -> onResize(column, delta) },
            )
        }
    }
}

/**
 * the draggable divider between two headers.
 *
 * wider than it looks ([Dimens.resizeHandle] against a 1px line) because a 1px drag target is a
 * target you miss; the visible divider stays hairline.
 */
@Composable
private fun ColumnResizeHandle(width: Dp, onResize: (Dp) -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .width(Dimens.resizeHandle)
            .fillMaxHeight()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // px to Dp here rather than in the caller: the drag arrives in px and the widths
                    // are Dp, and converting in two places is how they drift apart
                    val next = width + with(density) { delta.toDp() }
                    onResize(next.coerceAtLeast(Dimens.columnMin))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider()
    }
}

@Composable
private fun <C> TableRow(
    columns: List<C>,
    widths: Map<C, Dp>,
    index: Int,
    height: Dp,
    width: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onContextClick: () -> Unit,
    cell: @Composable (C) -> Unit,
) {
    // alternating bands, so an eye tracking one row across ten columns doesn't slip a line
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        index % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .width(width)
            .height(height)
            .background(background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = { onDoubleClick() },
                    onLongPress = { onContextClick() },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            Box(
                Modifier
                    .width(widths[column] ?: Dimens.columnDefault)
                    .fillMaxHeight()
                    .padding(horizontal = Dimens.cellPadding),
                contentAlignment = Alignment.CenterStart,
            ) {
                cell(column)
            }
            Spacer(Modifier.width(Dimens.resizeHandle))
        }
    }
}

/** the default cell: one line of text, clipped rather than wrapped. */
@Composable
fun TableText(text: String?, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text = text.orEmpty(),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
