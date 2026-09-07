package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

/**
 * one device's filesystem.
 *
 * Ported from the Swing app's `ExploreScreen`, on the same [DataTable] the device list and the log
 * viewer use — which is the whole reason that component is generic.
 *
 * Downloads land in the app's own `downloads` folder rather than behind a native save dialog: a file
 * chooser is an `expect`/`actual` per platform, and every download here is one someone is about to
 * look at anyway. The status bar links to the folder.
 */
@Composable
fun ExploreScreen(serial: String, modifier: Modifier = Modifier) {
    val browser = remember(serial) { FileBrowser(serial) }
    val scope = rememberCoroutineScope()
    val path by browser.path.collectAsState()
    val files by browser.files.collectAsState()
    val loading by browser.loading.collectAsState()
    val error by browser.error.collectAsState()
    val sort by browser.sort.collectAsState()
    val widths by FileTableState.widths.collectAsState()
    val copyToClipboard = rememberClipboardCopy()
    val showTooltips by SettingsManager.getBoolAsState(AppPref.SHOW_TOOLTIPS)
    val cellTooltip: (DeviceFile, FileColumn, Boolean) -> String? = { file, column, clipped ->
        column.text(file)?.takeIf { clipped }
    }

    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var input by remember { mutableStateOf<InputRequest?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    // dialog titles are resolved here rather than in the click handlers: a string resource can only
    // be read while composing
    val goToTitle = stringResource(Res.string.files_go_to_title)
    val newFolderTitle = stringResource(Res.string.files_new_folder)
    val deleteTitle = stringResource(Res.string.files_delete_title)

    val sorted = remember(files, sort) { browser.sorted(files) }
    val selected = remember(selection, files) { files.filter { it.path in selection.keys } }

    LaunchedEffect(serial) { browser.open(scope) }

    fun refresh() = browser.open(scope)

    fun openFolder(file: DeviceFile) {
        if (file.isDirectory || file.isSymlink) browser.open(scope, file.path)
    }

    Column(modifier.fillMaxSize()) {
        ExploreToolbar(
            path = path,
            loading = loading,
            hasSelection = selected.isNotEmpty(),
            onUp = { browser.up(scope) },
            onRefresh = ::refresh,
            onGoTo = {
                input = InputRequest(goToTitle, path) { target -> browser.open(scope, target) }
            },
            onDownload = {
                scope.launch {
                    selected.filterNot { it.isDirectory }.forEach { browser.download(it) }
                }
            },
            onNewFolder = {
                input = InputRequest(newFolderTitle, "") { name ->
                    if (name.isNotBlank()) scope.launch { browser.newFolder(name); refresh() }
                }
            },
            onDelete = {
                confirm = ConfirmRequest(
                    title = deleteTitle,
                    message = selected.joinToString("\n") { it.name },
                ) {
                    scope.launch { browser.delete(selected); refresh() }
                }
            },
        )
        HorizontalDivider(color = Colors.divider)

        Box(Modifier.weight(1f)) {
            when {
                error != null -> EmptyState(stringResource(Res.string.files_error), error.orEmpty())
                sorted.isEmpty() && !loading ->
                    EmptyState(stringResource(Res.string.files_empty), path)
                else -> DataTable(
                    rows = sorted,
                    columns = FileColumn.entries,
                    widths = FileColumn.entries.associateWith { widths[it] ?: it.defaultWidth.dp },
                    sortedBy = sort.first,
                    sortAscending = sort.second,
                    selection = selection,
                    rowKey = { it.path },
                    header = { it.label },
                    rowHeight = Dimens.tableRowHeight,
                    typeAheadText = { it.name },
                    onSelectionChange = { selection = it },
                    onSort = { browser.sortBy(it) },
                    onResize = { column, width -> FileTableState.resize(column, width) },
                    onActivate = ::openFolder,
                    // a path is the value most likely not to fit here, and the only thing worth
                    // saying about a cell that does fit is nothing
                    tooltip = cellTooltip.takeIf { showTooltips },
                    rowMenu = { file, _ ->
                        if (file.isDirectory) {
                            Item(stringResource(Res.string.files_open)) { openFolder(file) }
                            Separator()
                        } else {
                            Item(stringResource(Res.string.files_download)) {
                                scope.launch { browser.download(file) }
                            }
                        }
                        Item(stringResource(Res.string.files_copy_name)) { copyToClipboard(file.name) }
                        Item(stringResource(Res.string.files_copy_path)) { copyToClipboard(file.path) }
                        Separator()
                        Item(stringResource(Res.string.files_delete)) {
                            confirm = ConfirmRequest(deleteTitle, file.path) {
                                scope.launch { browser.delete(listOf(file)); refresh() }
                            }
                        }
                    },
                ) { file, column ->
                    FileCell(file, column)
                }
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        HorizontalDivider(color = Colors.divider)
        ExploreStatusBar(total = sorted.size, selected = selected.size)
    }

    InputDialog(input) { input = null }
    ConfirmDialog(confirm) { confirm = null }
}

/** the name cell carries the icon; everything else is text. */
@Composable
private fun FileCell(file: DeviceFile, column: FileColumn) {
    if (column != FileColumn.NAME) {
        TableText(column.text(file))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(
            res = when {
                file.isSymlink && file.isDirectory -> Res.drawable.folder_link
                file.isDirectory -> Res.drawable.folder
                file.isSymlink -> Res.drawable.file_link
                file.name.endsWith(".apk", ignoreCase = true) -> Res.drawable.file_apk
                file.name.endsWith(".json", ignoreCase = true) -> Res.drawable.file_json
                file.name.endsWith(".xml", ignoreCase = true) -> Res.drawable.file_xml
                else -> Res.drawable.file
            },
            size = Dimens.icon,
            tint = if (file.isDirectory) Colors.accent else Colors.secondaryText,
        )
        Spacer(Modifier.width(Dimens.gap / 2))
        TableText(file.name)
    }
}

@Composable
private fun ExploreToolbar(
    path: String,
    loading: Boolean,
    hasSelection: Boolean,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onGoTo: () -> Unit,
    onDownload: () -> Unit,
    onNewFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Colors.toolbarBg)) {
        Row(
            Modifier.height(Dimens.toolbarHeight).padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(Res.drawable.folder_up, stringResource(Res.string.files_up), onClick = onUp, tint = Colors.toolbarIcon)
            ToolbarButton(Res.drawable.open_folder, stringResource(Res.string.files_go_to), onClick = onGoTo, tint = Colors.toolbarIcon)
            ToolbarSeparator()
            ToolbarButton(
                res = Res.drawable.download,
                label = stringResource(Res.string.files_download),
                onClick = onDownload,
                enabled = hasSelection,
                tint = Colors.toolbarIcon,
            )
            ToolbarButton(Res.drawable.folder_new, stringResource(Res.string.files_new_folder), onClick = onNewFolder, tint = Colors.toolbarIcon)
            ToolbarButton(
                res = Res.drawable.delete,
                label = stringResource(Res.string.files_delete),
                onClick = onDelete,
                enabled = hasSelection,
                tint = Colors.toolbarIcon,
            )
            Spacer(Modifier.weight(1f))
            ToolbarButton(
                res = Res.drawable.refresh,
                label = stringResource(Res.string.action_refresh),
                onClick = onRefresh,
                enabled = !loading,
                tint = Colors.toolbarIcon,
            )
        }
        // the path is its own row, not a toolbar item: it is the longest thing on screen and the one
        // most worth reading in full
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.gapLarge, vertical = 4.dp),
        )
    }
}

@Composable
private fun ExploreStatusBar(total: Int, selected: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight)
            .background(Colors.statusBarBg)
            .padding(horizontal = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.files_saved_to, FileBrowser.PATH_DOWNLOADS),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
            modifier = Modifier.plainClickable {
                StorageManager.getAbsolutePath(FileBrowser.PATH_DOWNLOADS, "")
                    ?.let { PlatformUtils.openUrl(it) }
            },
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (selected > 0) stringResource(Res.string.device_selected_of, selected, total)
            else stringResource(Res.string.files_count, total),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
        )
    }
}
