package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * one device's filesystem: the Files page's tab.
 *
 * Ported from the Swing app's `ExploreScreen`, on the same [DataTable] the device list and the log
 * viewer use — which is the whole reason that component is generic. Laid out as docs/REDESIGN.md
 * draws it: the path row joined to the tab, places down the left, a selection bar over the table.
 *
 * Downloads land in the app's own `downloads` folder rather than behind a native save dialog: a file
 * chooser is an `expect`/`actual` per platform, and every download here is one someone is about to
 * look at anyway. The status bar links to the folder.
 */
@Composable
fun ExploreScreen(
    deviceId: String,
    modifier: Modifier = Modifier,
    /** a browser someone else keeps — a Files tab, so its folder survives another tab being opened. */
    owned: FileBrowser? = null,
) {
    val browser = owned ?: remember(deviceId) { FileBrowser(deviceId) }
    val scope = rememberCoroutineScope()
    val path by browser.path.collectAsState()
    val files by browser.files.collectAsState()
    val loading by browser.loading.collectAsState()
    val error by browser.error.collectAsState()
    val sort by browser.sort.collectAsState()
    val query by browser.query.collectAsState()
    val widths by FileTableState.widths.collectAsState()
    val showHidden by SettingsManager.getBoolAsState(AppPref.FILES_SHOW_HIDDEN)
    val copyToClipboard = rememberClipboardCopy()
    val cellTooltip: (DeviceFile, FileColumn, Boolean) -> String? = { file, column, clipped ->
        column.text(file)?.takeIf { clipped }
    }

    var selection by remember { mutableStateOf(TableSelection<Any?>()) }
    var input by remember { mutableStateOf<InputRequest?>(null) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var dropping by remember { mutableStateOf(false) }
    val devices by DeviceManager.devices.collectAsState()
    val deviceName = devices.firstOrNull { it.id == deviceId }?.displayName ?: deviceId

    // dialog titles are resolved here rather than in the click handlers: a string resource can only
    // be read while composing
    val goToTitle = stringResource(Res.string.files_go_to_title)
    val newFolderTitle = stringResource(Res.string.files_new_folder)
    val deleteTitle = stringResource(Res.string.files_delete_title)
    val renameTitle = stringResource(Res.string.files_rename)
    val uploadTitle = stringResource(Res.string.files_upload_title)

    val sorted = remember(files, sort, showHidden, query) { browser.sorted(browser.visible(files, showHidden, query)) }
    val selected = remember(selection, files) { files.filter { it.path in selection.keys } }

    LaunchedEffect(deviceId) { browser.open(scope) }
    // a selection belongs to the folder it was made in
    LaunchedEffect(path) { selection = TableSelection() }

    fun refresh() = browser.open(scope)

    fun openFolder(file: DeviceFile) {
        if (file.isDirectory || file.isSymlink) browser.open(scope, file.path)
    }

    fun upload(local: List<LocalFile>) {
        if (local.isEmpty()) return
        scope.launch {
            if (browser.upload(local) > 0) refresh()
        }
    }

    fun rename(file: DeviceFile) {
        input = InputRequest(renameTitle, file.name) { name ->
            if (name.isNotBlank() && name != file.name) scope.launch { browser.rename(file, name.trim()); refresh() }
        }
    }

    fun delete(targets: List<DeviceFile>) {
        confirm = ConfirmRequest(deleteTitle, targets.joinToString("\n") { it.name }) {
            scope.launch { browser.delete(targets); refresh() }
        }
    }

    Column(modifier.fillMaxSize()) {
        // the path row and the selection bar are one card, hanging off the open tab
        Column(
            Modifier
                .padding(horizontal = Dimens.pageGutter)
                .fillMaxWidth()
                .joinedCard(Colors.toolbarBg, Colors.sidebarBorder, Dimens.cardCorner, Dimens.hairline)
                .padding(start = Dimens.gap, end = Dimens.gap, top = Dimens.gap, bottom = Dimens.gap),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap),
        ) {
            PathRow(
                path = path,
                loading = loading,
                query = query,
                showHidden = showHidden,
                onOpen = { browser.open(scope, it) },
                onUp = { browser.up(scope) },
                onGoTo = { input = InputRequest(goToTitle, path) { target -> browser.open(scope, target) } },
                onCopyPath = { copyToClipboard(path) },
                onQuery = browser::setQuery,
                onHidden = { SettingsManager.setBoolean(AppPref.FILES_SHOW_HIDDEN, !showHidden) },
                onRefresh = ::refresh,
                onNewFolder = {
                    input = InputRequest(newFolderTitle, "") { name ->
                        if (name.isNotBlank()) scope.launch { browser.newFolder(name); refresh() }
                    }
                },
                onUpload = { scope.launch { upload(pickLocalFiles(uploadTitle)) } },
            )

            val one = selected.singleOrNull()
            val plain = selected.filterNot { it.isDirectory }
            val apks = selected.filter { it.isApk }
            SelectionBarFrame(
                count = selected.size,
                hint = stringResource(Res.string.files_hint),
                onClear = { selection = TableSelection() },
                // a folder's size is what `ls` says of the folder itself, not what's in it
                label = stringResource(Res.string.files_selected_size, selected.size, Utils.bytesToDisplayString(plain.sumOf { it.size })),
                outlined = false,
                modifier = Modifier.height(Dimens.controlHeight + Dimens.hairline * 4),
            ) {
                BarAction(Res.drawable.download, stringResource(Res.string.files_download), plain.isNotEmpty()) {
                    scope.launch { plain.forEach { browser.download(it) } }
                }
                if (apks.isNotEmpty()) {
                    // one after another: each is a copy and a `pm install` on the same device
                    BarAction(Res.drawable.file_apk, stringResource(Res.string.files_install), true) {
                        scope.launch { apks.forEach { browser.install(it) } }
                    }
                }
                if (one != null) {
                    BarAction(Res.drawable.edit, stringResource(Res.string.files_rename), true) { rename(one) }
                }
                BarAction(Res.drawable.copy, stringResource(Res.string.files_copy_path), true) {
                    copyToClipboard(selected.joinToString("\n") { it.path })
                }
                BarAction(Res.drawable.delete, stringResource(Res.string.files_delete), true, danger = true) { delete(selected) }
            }
        }

        Row(
            Modifier.weight(1f).padding(start = Dimens.pageGutter, end = Dimens.pageGutter, top = Dimens.rowPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.rowPadding),
        ) {
            Places(deviceId = deviceId, path = path, onOpen = { browser.open(scope, it) })

            val cardShape = RoundedCornerShape(Dimens.cardCorner)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(cardShape)
                    .background(Colors.listBg)
                    .border(Dimens.hairline, Colors.sidebarBorder, cardShape)
                    // anything dropped here goes into the folder on screen — an APK included;
                    // installing one is a button once it's there
                    .fileDropTarget(
                        enabled = error == null,
                        onDragChanged = { dropping = it },
                        onDrop = ::upload,
                    )
            ) {
                when {
                    error != null -> EmptyState(stringResource(Res.string.files_error), error.orEmpty())
                    sorted.isEmpty() && !loading ->
                        EmptyState(stringResource(Res.string.files_empty), stringResource(Res.string.files_empty_msg))
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
                        // the columns always span the pane: a file list wider than its window hides
                        // Size and Modified off to the right, behind a scroll nobody thinks to try
                        fitColumns = true,
                        typeAheadText = { it.name },
                        onSelectionChange = { selection = it },
                        onSort = { browser.sortBy(it) },
                        onResize = { column, width -> FileTableState.resize(column, width) },
                        onActivate = ::openFolder,
                        // a path is the value most likely not to fit here, and the only thing worth
                        // saying about a cell that does fit is nothing
                        tooltip = cellTooltip,
                        rowMenu = { file, _ ->
                            if (file.isDirectory) {
                                Item(stringResource(Res.string.files_open)) { openFolder(file) }
                                Separator()
                            } else {
                                Item(stringResource(Res.string.files_download)) {
                                    scope.launch { browser.download(file) }
                                }
                                if (file.isApk) {
                                    Item(stringResource(Res.string.files_install)) { scope.launch { browser.install(file) } }
                                }
                            }
                            Item(stringResource(Res.string.files_copy_name)) { copyToClipboard(file.name) }
                            Item(stringResource(Res.string.files_copy_path)) { copyToClipboard(file.path) }
                            Item(stringResource(Res.string.files_rename)) { rename(file) }
                            Separator()
                            Item(stringResource(Res.string.files_delete)) { delete(listOf(file)) }
                        },
                    ) { file, column ->
                        FileCell(file, column)
                    }
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (dropping) {
                    DropOverlay(path = path, device = deviceName, modifier = Modifier.matchParentSize())
                }
            }
        }

        ExploreStatusBar(total = sorted.size, selected = selected.size, device = deviceName)
    }

    InputDialog(input) { input = null }
    ConfirmDialog(confirm) { confirm = null }
}

private val DeviceFile.isApk: Boolean get() = !isDirectory && name.endsWith(".apk", ignoreCase = true)

private val DeviceFile.extension: String get() = name.substringAfterLast('.', "").lowercase()

private val IMAGES = setOf("png", "jpg", "jpeg", "webp", "gif", "heic")
private val VIDEOS = setOf("mp4", "mkv", "webm", "3gp", "mov")

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
                file.isApk -> Res.drawable.file_apk
                file.extension in IMAGES -> Res.drawable.screenshot
                file.extension in VIDEOS -> Res.drawable.screen_record
                file.extension == "json" -> Res.drawable.file_json
                file.extension == "xml" -> Res.drawable.file_xml
                else -> Res.drawable.file
            },
            size = Dimens.icon,
            tint = when {
                file.isDirectory -> Colors.accent
                file.isApk -> Colors.tabAccent
                else -> Colors.secondaryText
            },
        )
        Spacer(Modifier.width(Dimens.gap / 2))
        // a folder reads as somewhere to go; a dot-file as one that's usually hidden
        val weight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal
        TableText(
            AnnotatedString(file.name, SpanStyle(fontWeight = weight)),
            color = if (file.name.startsWith('.')) Colors.secondaryText else Color.Unspecified,
        )
    }
}

/**
 * the row joined to the tab: Up, the path as crumbs, the folder filter, and what acts on the folder
 * itself. A click on the path's empty end types a path instead.
 */
@Composable
private fun PathRow(
    path: String,
    loading: Boolean,
    query: String,
    showHidden: Boolean,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onGoTo: () -> Unit,
    onCopyPath: () -> Unit,
    onQuery: (String) -> Unit,
    onHidden: () -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.navCorner)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
    ) {
        IconBox(Res.drawable.folder_up, stringResource(Res.string.files_up), onClick = onUp)

        Row(
            Modifier
                .weight(1f)
                .height(Dimens.controlHeight)
                .clip(shape)
                .background(Colors.listBg)
                .border(Dimens.hairline, Colors.divider, shape)
                .padding(horizontal = Dimens.liveDot),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState(), reverseScrolling = true),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs(path).forEachIndexed { index, (label, target) ->
                    if (index > 1) Text("›", color = Colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                    val last = target == path
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (last) Colors.primaryText else Colors.secondaryText,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Dimens.checkboxCorner))
                            .clickable { onOpen(target) }
                            .padding(horizontal = Dimens.liveDot, vertical = Dimens.hairline * 2),
                    )
                }
            }
            // the rest of the bar types a path — the Go To… the toolbar used to have
            Box(Modifier.weight(1f).fillMaxHeight().plainClickable(onClick = onGoTo))
            val copy = stringResource(Res.string.files_copy_path)
            CellTooltip(text = { copy }) {
                AppIcon(
                    Res.drawable.copy,
                    size = Dimens.icon,
                    tint = Colors.secondaryText,
                    contentDescription = copy,
                    modifier = Modifier.clip(RoundedCornerShape(Dimens.checkboxCorner)).clickable(onClick = onCopyPath).padding(Dimens.liveDot),
                )
            }
        }

        Row(
            Modifier
                .width(Dimens.folderFilterWidth)
                .height(Dimens.controlHeight)
                .clip(shape)
                .background(Colors.listBg)
                .border(Dimens.hairline, Colors.divider, shape)
                .padding(horizontal = Dimens.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterField(value = query, onValueChange = onQuery, hint = stringResource(Res.string.files_filter), modifier = Modifier.weight(1f))
        }

        AppSwitch(checked = showHidden, label = stringResource(Res.string.files_hidden)) { onHidden() }
        IconBox(Res.drawable.refresh, stringResource(Res.string.action_refresh)) { if (!loading) onRefresh() }
        OutlinedAction(stringResource(Res.string.files_new_folder), onClick = onNewFolder)
        OutlinedAction(stringResource(Res.string.files_upload), onClick = onUpload)
    }
}

/** `/sdcard/DCIM` as `/`, `sdcard`, `DCIM` — each with the path it opens. */
private fun crumbs(path: String): List<Pair<String, String>> {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return listOf("/" to "/") + parts.mapIndexed { index, part -> part to "/" + parts.take(index + 1).joinToString("/") }
}

/** a folder worth one click, as Places lists it. */
private data class Place(val label: StringResource, val icon: DrawableResource, val path: String)

private val PLACES = listOf(
    Place(Res.string.files_place_internal, Res.drawable.home, "/sdcard"),
    Place(Res.string.files_place_download, Res.drawable.download, "/sdcard/Download"),
    Place(Res.string.files_place_camera, Res.drawable.screenshot, "/sdcard/DCIM/Camera"),
    Place(Res.string.files_place_screenshots, Res.drawable.screenshot, "/sdcard/Pictures/Screenshots"),
    Place(Res.string.files_place_pictures, Res.drawable.folder, "/sdcard/Pictures"),
    Place(Res.string.files_place_app_data, Res.drawable.android, "/sdcard/Android/data"),
    Place(Res.string.files_place_temp, Res.drawable.terminal, "/data/local/tmp"),
    Place(Res.string.files_place_root, Res.drawable.root, "/"),
)

/**
 * the folders people go to, down the left, and the device's free space under them.
 *
 * the one marked is the deepest place the open folder is inside — `/sdcard/Pictures/Screenshots`
 * marks Screenshots, not Pictures or Internal storage.
 */
@Composable
private fun Places(deviceId: String, path: String, onOpen: (String) -> Unit) {
    val devices by DeviceManager.devices.collectAsState()
    val device = devices.firstOrNull { it.id == deviceId }
    val here = PLACES
        .filter { path == it.path || path.startsWith(it.path.trimEnd('/') + "/") }
        .maxByOrNull { it.path.length }
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(
        Modifier
            .width(Dimens.placesWidth)
            .fillMaxHeight()
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.sidebarBorder, shape)
            .padding(Dimens.gap),
    ) {
        Text(
            stringResource(Res.string.files_places),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Colors.secondaryText,
            modifier = Modifier.padding(start = Dimens.liveDot, top = Dimens.liveDot, bottom = Dimens.liveDot),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PLACES.forEach { place ->
                val on = place == here
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Dimens.placeRowHeight)
                        .clip(RoundedCornerShape(Dimens.navCorner))
                        .background(if (on) Colors.navSelected else Color.Transparent)
                        .clickable { onOpen(place.path) }
                        .padding(horizontal = Dimens.liveDot),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(place.icon, size = Dimens.icon, tint = if (on) Colors.tabAccent else Colors.toolbarIcon)
                    Spacer(Modifier.width(Dimens.gap))
                    Text(
                        stringResource(place.label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = Colors.primaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (device != null) {
            Box(Modifier.padding(Dimens.liveDot)) { StorageBar(device.freeSpace, device.totalSpace) }
        }
    }
}

/**
 * what the table is showing, then which device and as whom — a plain line under the cards.
 *
 * downloads still say where they went here until the Transfers panel has a row to say it in.
 */
@Composable
private fun ExploreStatusBar(total: Int, selected: Int, device: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.statusBarHeight + Dimens.gap)
            .padding(horizontal = Dimens.pageGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge),
    ) {
        // a toast takes the count's place while it's up
        StatusToastSlot(contentAlignment = Alignment.CenterStart) {
            Text(
                text = if (selected > 0) stringResource(Res.string.files_count_selected, total, selected)
                else stringResource(Res.string.files_count, total),
                style = MaterialTheme.typography.labelSmall,
                color = Colors.secondaryText,
            )
        }
        Spacer(Modifier.weight(1f))
        // the folder Settings › Files names, or app storage's on a phone
        SettingsManager.observePref(AppPref.DOWNLOAD_FOLDER)
        val folder = SaveFolders.downloads()
        Text(
            text = stringResource(Res.string.files_saved_to, folder ?: FileBrowser.PATH_DOWNLOADS),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).plainClickable {
                (folder ?: StorageManager.getAbsolutePath(FileBrowser.PATH_DOWNLOADS, ""))
                    ?.let { PlatformUtils.openUrl(it) }
            },
        )
        Text(
            text = stringResource(Res.string.files_browsing_shell, device),
            style = MaterialTheme.typography.labelSmall,
            color = Colors.secondaryText,
        )
    }
}

/** what a drop will do, over the whole table while files are dragged across it. */
@Composable
private fun DropOverlay(path: String, device: String, modifier: Modifier = Modifier) {
    val accent = Colors.tabAccent
    Column(
        modifier
            .padding(Dimens.gap)
            .clip(RoundedCornerShape(Dimens.cardCorner))
            .background(Colors.rowSelectedBg)
            .drawBehind {
                val width = (Dimens.hairline * 2).toPx()
                val corner = Dimens.cardCorner.toPx()
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(width / 2, width / 2),
                    size = Size(size.width - width, size.height - width),
                    cornerRadius = CornerRadius(corner),
                    style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 4, width * 3))),
                )
            },
        verticalArrangement = Arrangement.spacedBy(Dimens.gap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(Res.drawable.download, size = Dimens.navIcon * 2, tint = accent, modifier = Modifier.rotate(180f))
        Text(
            stringResource(Res.string.files_drop_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Colors.primaryText,
        )
        Text(
            stringResource(Res.string.files_drop_here, path, device),
            style = MaterialTheme.typography.bodyMedium,
            color = Colors.secondaryText,
        )
    }
}
