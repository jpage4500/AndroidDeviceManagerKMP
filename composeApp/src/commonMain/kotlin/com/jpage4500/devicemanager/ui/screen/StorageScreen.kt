package com.jpage4500.devicemanager.ui.screen

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.manager.*
import com.jpage4500.devicemanager.resources.*
import com.jpage4500.devicemanager.ui.components.*
import com.jpage4500.devicemanager.ui.theme.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * Files › Storage and Files › Apps: what's using a device's storage, and giving some of it back.
 * Laid out as the "Files · Storage Analyzer" canvas's options A and C — see docs/REDESIGN.md.
 */

// --- Storage ---------------------------------------------------------------------------------

private val StorageCategory.label: StringResource
    get() = when (this) {
        StorageCategory.APPS -> Res.string.storage_cat_apps
        StorageCategory.IMAGES -> Res.string.storage_cat_images
        StorageCategory.VIDEO -> Res.string.storage_cat_video
        StorageCategory.AUDIO -> Res.string.storage_cat_audio
        StorageCategory.DOWNLOADS -> Res.string.storage_cat_downloads
        StorageCategory.OTHER -> Res.string.storage_cat_other
        StorageCategory.SYSTEM -> Res.string.storage_cat_system
    }

private val StorageCategory.color: Color
    get() = when (this) {
        StorageCategory.APPS -> Colors.storageApps
        StorageCategory.IMAGES -> Colors.storageImages
        StorageCategory.VIDEO -> Colors.storageVideo
        StorageCategory.AUDIO -> Colors.storageAudio
        StorageCategory.DOWNLOADS -> Colors.storageDownloads
        StorageCategory.OTHER -> Colors.storageOther
        StorageCategory.SYSTEM -> Colors.storageSystem
    }

/** where a category's row leads: a folder most of it lives in, or nowhere — System can't be opened. */
private val StorageCategory.folder: String?
    get() = when (this) {
        StorageCategory.IMAGES -> "/sdcard/DCIM"
        StorageCategory.VIDEO -> "/sdcard/Movies"
        StorageCategory.AUDIO -> "/sdcard/Music"
        StorageCategory.DOWNLOADS -> "/sdcard/Download"
        StorageCategory.OTHER -> "/sdcard"
        StorageCategory.APPS, StorageCategory.SYSTEM -> null
    }

/** the card row joined to the tab, in the Storage view: what this is, and Rescan and Free up space. */
@Composable
fun StorageRow(analyzer: StorageAnalyzer, onRescan: () -> Unit, onFreeUp: () -> Unit) {
    val loading by analyzer.loading.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(Dimens.controlHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        AppIcon(Res.drawable.chart_pie, size = Dimens.icon, tint = Colors.tabAccent)
        Text(stringResource(Res.string.storage_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(Res.string.storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = Colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconBox(Res.drawable.refresh, stringResource(Res.string.storage_rescan), enabled = !loading, onClick = onRescan)
        PrimaryButton(null, stringResource(Res.string.storage_free_up), onClick = onFreeUp)
    }
}

/**
 * the Storage view: a bar of used and free by kind, then where it is and what can be freed side by
 * side. The report fills in first; each Free up space row as its scan finishes.
 */
@Composable
fun StoragePane(
    analyzer: StorageAnalyzer,
    onOpenFolder: (String) -> Unit,
    onApps: (AppFilter) -> Unit,
    onReview: (Set<Cleanup>) -> Unit,
    onEmptyTrash: () -> Unit,
    onTrimCaches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report by analyzer.report.collectAsState()
    val found by analyzer.found.collectAsState()
    val loading by analyzer.loading.collectAsState()
    val error by analyzer.error.collectAsState()
    val current = report
    Box(modifier.fillMaxSize()) {
        when {
            current == null && error != null -> EmptyState(stringResource(Res.string.storage_error), error.orEmpty())
            current == null -> Box(Modifier.fillMaxSize())
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.rowPadding),
            ) {
                UsageCard(current)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.rowPadding)) {
                    WhereCard(current, onOpenFolder, onApps, Modifier.weight(1f).widthIn(min = Dimens.storageColumnMin))
                    CleanupCard(
                        analyzer, current, found, onApps, onReview, onEmptyTrash, onTrimCaches,
                        Modifier.weight(1f).widthIn(min = Dimens.storageColumnMin),
                    )
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun StorageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Dimens.cardCorner)
    Column(
        modifier
            .clip(shape)
            .background(Colors.listBg)
            .border(Dimens.hairline, Colors.sidebarBorder, shape),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageCard(report: StorageReport) {
    val free = report.freeBytes ?: 0
    val total = report.totalBytes
    val used = total?.let { it - free }
    StorageCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Dimens.gapLarge), verticalArrangement = Arrangement.spacedBy(Dimens.gap)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                Text(
                    stringResource(Res.string.storage_used, Utils.bytesToDisplayString(used)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Colors.primaryText,
                )
                if (total != null) {
                    Text(
                        stringResource(Res.string.storage_of, Utils.bytesToDisplayString(total)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Colors.secondaryText,
                        modifier = Modifier.padding(bottom = Dimens.tabGap),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(Res.string.storage_free, Utils.bytesToDisplayString(free)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Colors.tabAccent,
                    modifier = Modifier.padding(bottom = Dimens.tabGap),
                )
            }
            // diskstats' kinds when Android has measured them; one block of "used" when it hasn't
            val parts = report.categories.entries.toList()
            Row(
                Modifier.fillMaxWidth().height(Dimens.storageBarHeight).clip(RoundedCornerShape(Dimens.navCorner)),
                horizontalArrangement = Arrangement.spacedBy(Dimens.hairline * 2),
            ) {
                if (parts.isEmpty() && used != null && used > 0) {
                    Box(Modifier.weight(used.toFloat()).fillMaxHeight().background(Colors.storageSystem))
                }
                parts.forEach { (category, bytes) ->
                    Box(Modifier.weight(bytes.toFloat()).fillMaxHeight().background(category.color))
                }
                if (free > 0) Box(Modifier.weight(free.toFloat()).fillMaxHeight().background(Colors.tableRule))
            }
            if (parts.isEmpty()) {
                Text(stringResource(Res.string.storage_unmeasured), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge), verticalArrangement = Arrangement.spacedBy(Dimens.tabGap)) {
                    parts.forEach { (category, _) -> Legend(category.color, stringResource(category.label)) }
                    Legend(Colors.tableRule, stringResource(Res.string.storage_cat_free))
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot)) {
        Box(Modifier.size(Dimens.storageSwatch).clip(RoundedCornerShape(Dimens.checkboxCorner)).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Colors.primaryText)
    }
}

@Composable
private fun CardTitle(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.rowPadding, end = Dimens.rowPadding, top = Dimens.rowPadding, bottom = Dimens.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Colors.primaryText)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun WhereCard(report: StorageReport, onOpenFolder: (String) -> Unit, onApps: (AppFilter) -> Unit, modifier: Modifier) {
    val parts = report.categories.entries.sortedByDescending { it.value }
    if (parts.isEmpty()) return
    val largest = parts.first().value.coerceAtLeast(1)
    StorageCard(modifier) {
        CardTitle(stringResource(Res.string.storage_where)) {
            Text(stringResource(Res.string.storage_where_hint), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
        }
        parts.forEach { (category, bytes) ->
            val target: (() -> Unit)? = when {
                category == StorageCategory.APPS -> ({ onApps(AppFilter.ALL) })
                category.folder != null -> ({ onOpenFolder(category.folder!!) })
                else -> null
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.cleanupRowMinHeight)
                    .then(if (target != null) Modifier.clickable(onClick = target) else Modifier)
                    .padding(horizontal = Dimens.rowPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
            ) {
                Box(Modifier.size(Dimens.storageSwatch).clip(RoundedCornerShape(Dimens.checkboxCorner)).background(category.color))
                Text(stringResource(category.label), style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText, modifier = Modifier.weight(1f))
                Box(Modifier.width(Dimens.storageTrackWidth).height(Dimens.storageTrackHeight).clip(CircleShape).background(Colors.tableRule)) {
                    Box(Modifier.fillMaxWidth(bytes.toFloat() / largest).fillMaxHeight().background(category.color))
                }
                Text(
                    Utils.bytesToDisplayString(bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(Dimens.storageSizeWidth),
                )
                AppIcon(Res.drawable.arrow_right, size = Dimens.icon, tint = if (target != null) Colors.secondaryText else Color.Transparent)
            }
        }
        Spacer(Modifier.height(Dimens.gap))
    }
}

/** one Free up space row, before it's drawn: what it is, what it's worth, and its button. */
private data class Offer(
    val kind: Cleanup,
    val icon: DrawableResource,
    val title: String,
    /** null while the scan behind it is still running. */
    val detail: String?,
    val bytes: Long,
    val action: String,
    val onAction: () -> Unit,
)

@Composable
private fun CleanupCard(
    analyzer: StorageAnalyzer,
    report: StorageReport,
    found: Map<Cleanup, List<FoundFile>>,
    onApps: (AppFilter) -> Unit,
    onReview: (Set<Cleanup>) -> Unit,
    onEmptyTrash: () -> Unit,
    onTrimCaches: () -> Unit,
    modifier: Modifier,
) {
    val unused = remember(report) { analyzer.unusedApps(report) }
    val review = stringResource(Res.string.cleanup_review)
    fun files(kind: Cleanup) = found[kind]
    val offers = buildList {
        files(Cleanup.TRASH).let { list ->
            add(Offer(Cleanup.TRASH, Res.drawable.trash, stringResource(Res.string.cleanup_trash),
                list?.let { stringResource(Res.string.cleanup_trash_sub, it.size) }, list.orEmpty().sumOf { it.bytes },
                stringResource(Res.string.cleanup_empty), onEmptyTrash))
        }
        add(Offer(Cleanup.CACHES, Res.drawable.memory, stringResource(Res.string.cleanup_caches),
            stringResource(Res.string.cleanup_caches_sub), report.cacheBytes, stringResource(Res.string.cleanup_clear), onTrimCaches))
        files(Cleanup.LARGE_FILES).let { list ->
            add(Offer(Cleanup.LARGE_FILES, Res.drawable.file, stringResource(Res.string.cleanup_large),
                list?.let { stringResource(Res.string.cleanup_large_sub, it.size) }, list.orEmpty().sumOf { it.bytes },
                review) { onReview(setOf(Cleanup.LARGE_FILES)) })
        }
        if (report.usageKnown) {
            add(Offer(Cleanup.UNUSED_APPS, Res.drawable.android, stringResource(Res.string.cleanup_unused),
                stringResource(Res.string.cleanup_unused_sub, unused.size), unused.sumOf { it.totalBytes },
                review) { onApps(AppFilter.UNUSED) })
        }
        files(Cleanup.APKS).let { list ->
            add(Offer(Cleanup.APKS, Res.drawable.file_apk, stringResource(Res.string.cleanup_apks),
                list?.let { stringResource(Res.string.cleanup_apks_sub, it.size) }, list.orEmpty().sumOf { it.bytes },
                review) { onReview(setOf(Cleanup.APKS)) })
        }
        files(Cleanup.ADB_TEMP).let { list ->
            add(Offer(Cleanup.ADB_TEMP, Res.drawable.temp, stringResource(Res.string.cleanup_adb),
                list?.let { stringResource(Res.string.cleanup_adb_sub, it.size) }, list.orEmpty().sumOf { it.bytes },
                review) { onReview(setOf(Cleanup.ADB_TEMP)) })
        }
    }
    // a finished scan that found nothing is left out rather than listed at zero
    val shown = offers.filter { it.detail == null || it.bytes > 0 }
    StorageCard(modifier) {
        CardTitle(stringResource(Res.string.storage_suggestions)) {
            val total = shown.sumOf { it.bytes }
            if (total > 0) {
                Text(
                    stringResource(Res.string.storage_up_to, Utils.bytesToDisplayString(total)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Colors.tabAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Colors.rowSelectedBg)
                        .padding(horizontal = Dimens.gap, vertical = Dimens.hairline * 2),
                )
            }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(Res.string.storage_nothing),
                style = MaterialTheme.typography.bodyMedium,
                color = Colors.secondaryText,
                modifier = Modifier.padding(Dimens.rowPadding),
            )
        }
        shown.forEach { OfferRow(it) }
        Spacer(Modifier.height(Dimens.gap))
    }
}

@Composable
private fun OfferRow(offer: Offer) {
    val scanning = offer.detail == null
    Row(
        Modifier.fillMaxWidth().height(Dimens.cleanupRowHeight).padding(horizontal = Dimens.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
    ) {
        Box(
            Modifier.size(Dimens.cleanupIcon).clip(RoundedCornerShape(Dimens.navCorner)).background(Colors.rowSelectedBg),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(offer.icon, size = Dimens.icon, tint = Colors.tabAccent)
        }
        Column(Modifier.weight(1f)) {
            Text(offer.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Colors.primaryText)
            Text(
                offer.detail ?: stringResource(Res.string.storage_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = Colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (scanning) "—" else Utils.bytesToDisplayString(offer.bytes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            color = if (scanning) Colors.secondaryText else Colors.primaryText,
            modifier = Modifier.width(Dimens.storageSizeWidth),
        )
        OutlinedAction(
            offer.action,
            modifier = Modifier.width(Dimens.cleanupButtonWidth).alpha(if (scanning || offer.bytes <= 0) 0.4f else 1f),
            onClick = { if (!scanning && offer.bytes > 0) offer.onAction() },
        )
    }
}

// --- the review dialog -----------------------------------------------------------------------

/** one tick box in the review: a kind of cleanup as a whole, a file, or an app. */
private data class CleanupItem(
    val key: String,
    val name: String,
    val detail: String,
    val bytes: Long,
    val safe: Boolean,
    val file: FoundFile? = null,
    val app: AppStorage? = null,
)

private const val KEY_TRASH = "trash"
private const val KEY_CACHES = "caches"

/**
 * what Free up space… and a row's Review open: every file and app [kinds] found, grouped by what
 * removing it costs, with the safe ones ticked. Nothing is removed until Free.
 */
@Composable
fun CleanupDialog(
    analyzer: StorageAnalyzer,
    kinds: Set<Cleanup>,
    deviceName: String,
    /** the screen's, not the dialog's: a cleanup that's started finishes even if the dialog goes. */
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val report by analyzer.report.collectAsState()
    val found by analyzer.found.collectAsState()
    val trashLabel = stringResource(Res.string.cleanup_trash)
    val trashFiles = found[Cleanup.TRASH].orEmpty()
    val trashDetail = stringResource(Res.string.cleanup_trash_sub, trashFiles.size)
    val cachesLabel = stringResource(Res.string.cleanup_caches)
    val cachesDetail = stringResource(Res.string.cleanup_caches_sub)
    val unusedDetail = stringResource(Res.string.cleanup_unused)
    val items = remember(report, found, kinds) {
        val current = report
        val safe = buildList {
            if (Cleanup.TRASH in kinds && trashFiles.isNotEmpty()) {
                add(CleanupItem(KEY_TRASH, trashLabel, trashDetail, trashFiles.sumOf { it.bytes }, safe = true))
            }
            if (Cleanup.CACHES in kinds && current != null && current.cacheBytes > 0) {
                add(CleanupItem(KEY_CACHES, cachesLabel, cachesDetail, current.cacheBytes, safe = true))
            }
        }
        // an APK over 100 MB is in two scans; it's one file to tick
        val files = listOf(Cleanup.LARGE_FILES, Cleanup.APKS, Cleanup.ADB_TEMP)
            .filter { it in kinds }
            .flatMap { found[it].orEmpty() }
            .distinctBy { it.path }
            .map { CleanupItem(it.path, it.name, it.folder, it.bytes, safe = false, file = it) }
        val apps = if (Cleanup.UNUSED_APPS in kinds && current != null) {
            analyzer.unusedApps(current).map { CleanupItem(it.packageName, it.packageName, unusedDetail, it.totalBytes, safe = false, app = it) }
        } else emptyList()
        Triple(safe, files, apps)
    }
    val (safe, files, apps) = items
    var ticked by remember(items) { mutableStateOf(safe.map { it.key }.toSet()) }
    val all = safe + files + apps
    val chosen = all.filter { it.key in ticked }
    val total = chosen.sumOf { it.bytes }
    var running by remember { mutableStateOf(false) }

    AppDialog(
        title = stringResource(Res.string.cleanup_title, deviceName),
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.cleanup_confirm, Utils.bytesToDisplayString(total)),
        confirmEnabled = chosen.isNotEmpty() && !running,
        onConfirm = {
            running = true
            onDismiss()
            scope.launch { runCleanup(analyzer, chosen) }
        },
        extraButton = {
            val free = report?.freeBytes
            if (free != null && total > 0) {
                Text(
                    stringResource(Res.string.cleanup_after, Utils.bytesToDisplayString(free + total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Colors.secondaryText,
                )
            }
        },
    ) {
        Text(stringResource(Res.string.cleanup_intro), style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText)
        Spacer(Modifier.height(Dimens.gap))
        Column(Modifier.heightIn(max = Dimens.cleanupListMaxHeight).verticalScroll(rememberScrollState())) {
            listOf(
                Res.string.cleanup_group_safe to safe,
                Res.string.cleanup_group_files to files,
                Res.string.cleanup_group_apps to apps,
            ).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Colors.secondaryText,
                    modifier = Modifier.padding(top = Dimens.gap, bottom = Dimens.tabGap),
                )
                group.forEach { item ->
                    val on = item.key in ticked
                    val toggle = { ticked = if (on) ticked - item.key else ticked + item.key }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Dimens.cleanupRowMinHeight).plainClickable(onClick = toggle),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
                    ) {
                        RowCheckbox(checked = on, onToggle = toggle, label = item.name)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium, color = Colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = Colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            Utils.bytesToDisplayString(item.bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            color = if (on) Colors.primaryText else Colors.secondaryText,
                            modifier = Modifier.width(Dimens.storageSizeWidth),
                        )
                    }
                }
            }
        }
    }
}

/** the ticked items, cheapest to undo first — one device, one command after another. */
private suspend fun runCleanup(analyzer: StorageAnalyzer, chosen: List<CleanupItem>) {
    if (chosen.any { it.key == KEY_TRASH }) analyzer.emptyTrash(analyzer.found.value[Cleanup.TRASH].orEmpty())
    if (chosen.any { it.key == KEY_CACHES }) analyzer.trimCaches().onSuccess { analyzer.cachesTrimmed() }
    val files = chosen.mapNotNull { it.file }
    if (files.isNotEmpty()) analyzer.deleteFiles(files)
    val removed = chosen.mapNotNull { it.app }.filter { analyzer.uninstall(it).isSuccess }
    analyzer.forget(removed.map { it.packageName })
    analyzer.refreshSpace()
}

// --- Apps ------------------------------------------------------------------------------------

/** the Apps view's chips. */
enum class AppFilter { ALL, INSTALLED, SYSTEM, UNUSED, LARGE }

enum class AppColumn(val defaultWidth: Dp) {
    NAME(Dimens.appColumnName),
    APP(Dimens.appColumnSize),
    DATA(Dimens.appColumnSize),
    CACHE(Dimens.appColumnSize),
    TOTAL(Dimens.appColumnSize),
    USED(Dimens.appColumnUsed),
    ;

    fun sortKey(app: AppStorage): Comparable<*>? = when (this) {
        NAME -> app.packageName.lowercase()
        APP -> app.appBytes
        DATA -> app.dataBytes
        CACHE -> app.cacheBytes
        TOTAL -> app.totalBytes
        USED -> app.lastUsedMs ?: 0L
    }
}

/** the Apps view's choices, kept by the tab's screen so the card row and the table share them. */
@Stable
class AppsViewState {
    var filter by mutableStateOf(AppFilter.INSTALLED)
    var query by mutableStateOf("")
    var selection by mutableStateOf(TableSelection<Any?>())
    var sortBy by mutableStateOf(AppColumn.TOTAL)
    var ascending by mutableStateOf(false)

    fun visible(analyzer: StorageAnalyzer, report: StorageReport?): List<AppStorage> {
        val apps = report?.apps ?: return emptyList()
        val unused = if (filter == AppFilter.UNUSED) analyzer.unusedApps(report).map { it.packageName }.toSet() else emptySet()
        val filtered = apps.filter { app ->
            val inFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.INSTALLED -> !app.system
                AppFilter.SYSTEM -> app.system
                AppFilter.UNUSED -> app.packageName in unused
                AppFilter.LARGE -> app.totalBytes >= LARGE_APP_BYTES
            }
            inFilter && (query.isBlank() || app.packageName.containsIgnoreCase(query.trim()))
        }
        @Suppress("UNCHECKED_CAST")
        val byColumn = compareBy<AppStorage, Comparable<Any>?>(nullsLast()) { sortBy.sortKey(it) as Comparable<Any>? }
        return filtered.sortedWith(if (ascending) byColumn else byColumn.reversed())
    }

    fun count(analyzer: StorageAnalyzer, report: StorageReport?, which: AppFilter): Int {
        val apps = report?.apps ?: return 0
        return when (which) {
            AppFilter.ALL -> apps.size
            AppFilter.INSTALLED -> apps.count { !it.system }
            AppFilter.SYSTEM -> apps.count { it.system }
            AppFilter.UNUSED -> analyzer.unusedApps(report).size
            AppFilter.LARGE -> apps.count { it.totalBytes >= LARGE_APP_BYTES }
        }
    }

    fun sort(column: AppColumn) {
        // a size reads biggest first; a name A to Z
        if (sortBy == column) ascending = !ascending else {
            sortBy = column
            ascending = column == AppColumn.NAME
        }
    }

    companion object {
        private const val LARGE_APP_BYTES = 1024L * 1024 * 1024
    }
}

/** the card row joined to the tab, in the Apps view: the filter chips, a filter box, and Clear all app caches. */
@Composable
fun AppsRow(analyzer: StorageAnalyzer, state: AppsViewState, onTrimCaches: () -> Unit) {
    val report by analyzer.report.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(Dimens.controlHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Dimens.liveDot),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppFilter.entries.forEach { filter ->
                // Unused only when usage stats answered; it would always say 0 otherwise
                if (filter == AppFilter.UNUSED && report?.usageKnown != true) return@forEach
                val label = when (filter) {
                    AppFilter.ALL -> Res.string.apps_filter_all
                    AppFilter.INSTALLED -> Res.string.apps_filter_installed
                    AppFilter.SYSTEM -> Res.string.apps_filter_system
                    AppFilter.UNUSED -> Res.string.apps_filter_unused
                    AppFilter.LARGE -> Res.string.apps_filter_large
                }
                FilterChip(
                    label = stringResource(label, state.count(analyzer, report, filter)),
                    leading = if (filter == AppFilter.UNUSED) ({
                        Box(Modifier.size(Dimens.liveDot).clip(CircleShape).background(Colors.chipWarn))
                    }) else null,
                    on = state.filter == filter,
                ) {
                    state.filter = filter
                    state.selection = TableSelection()
                }
            }
        }
        val shape = RoundedCornerShape(Dimens.navCorner)
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
            FilterField(
                value = state.query,
                onValueChange = { state.query = it },
                hint = stringResource(Res.string.apps_filter),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedAction(stringResource(Res.string.apps_clear_all_caches), onClick = onTrimCaches)
    }
}

/** what can be done to every selected app, as one actor — the bar and a row's menu both go through it. */
class AppActions(
    private val analyzer: StorageAnalyzer,
    private val scope: CoroutineScope,
    private val onDone: () -> Unit,
) {
    fun clearCache(apps: List<AppStorage>) = scope.launch {
        val done = apps.filter { analyzer.clearCache(it).isSuccess }
        analyzer.adjust(done.map { it.packageName }) { it.copy(dataBytes = (it.dataBytes - it.cacheBytes).coerceAtLeast(0), cacheBytes = 0) }
        finish()
    }

    fun archive(apps: List<AppStorage>) = scope.launch {
        val done = apps.filter { analyzer.archive(it).isSuccess }
        analyzer.adjust(done.map { it.packageName }) { it.copy(appBytes = 0) }
        finish()
    }

    fun uninstall(apps: List<AppStorage>) = scope.launch {
        val done = apps.filter { analyzer.uninstall(it).isSuccess }
        analyzer.forget(done.map { it.packageName })
        finish()
    }

    fun clearData(app: AppStorage) = scope.launch {
        if (analyzer.clearData(app).isSuccess) analyzer.adjust(listOf(app.packageName)) { it.copy(dataBytes = 0, cacheBytes = 0) }
        finish()
    }

    fun forceStop(app: AppStorage) = scope.launch { analyzer.forceStop(app) }

    fun downloadApk(apps: List<AppStorage>) = scope.launch { apps.forEach { analyzer.downloadApk(it) } }

    private suspend fun finish() {
        analyzer.refreshSpace()
        onDone()
    }
}

/** the Apps view's selection bar: Clear cache, Archive on Android 15, Uninstall, Download APK. */
@Composable
fun AppsSelectionBar(selected: List<AppStorage>, canArchive: Boolean, actions: AppActions, onUninstall: (List<AppStorage>) -> Unit, onClear: () -> Unit) {
    SelectionBarFrame(
        count = selected.size,
        hint = stringResource(Res.string.apps_hint),
        onClear = onClear,
        label = stringResource(Res.string.apps_selected, selected.size, Utils.bytesToDisplayString(selected.sumOf { it.totalBytes })),
        outlined = false,
        modifier = Modifier.height(Dimens.controlHeight + Dimens.hairline * 4),
    ) {
        BarAction(Res.drawable.memory, stringResource(Res.string.apps_clear_cache), true) { actions.clearCache(selected) }
        if (canArchive) {
            BarAction(Res.drawable.save, stringResource(Res.string.apps_archive), true) { actions.archive(selected) }
        }
        BarAction(Res.drawable.download, stringResource(Res.string.apps_download_apk), true) { actions.downloadApk(selected) }
        BarAction(Res.drawable.delete, stringResource(Res.string.apps_uninstall), true, danger = true) { onUninstall(selected) }
    }
}

/** the Apps view's table: every package diskstats measured, biggest first. */
@Composable
fun AppsPane(
    analyzer: StorageAnalyzer,
    state: AppsViewState,
    canArchive: Boolean,
    actions: AppActions,
    onUninstall: (List<AppStorage>) -> Unit,
    onClearData: (AppStorage) -> Unit,
    onShowFiles: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report by analyzer.report.collectAsState()
    val loading by analyzer.loading.collectAsState()
    val error by analyzer.error.collectAsState()
    val copyToClipboard = rememberClipboardCopy()
    val rows = remember(report, state.filter, state.query, state.sortBy, state.ascending) { state.visible(analyzer, report) }
    val headers = mapOf(
        AppColumn.NAME to stringResource(Res.string.apps_col_name),
        AppColumn.APP to stringResource(Res.string.apps_col_app),
        AppColumn.DATA to stringResource(Res.string.apps_col_data),
        AppColumn.CACHE to stringResource(Res.string.apps_col_cache),
        AppColumn.TOTAL to stringResource(Res.string.apps_col_total),
        AppColumn.USED to stringResource(Res.string.apps_col_used),
    )
    val system = stringResource(Res.string.apps_system)
    val today = stringResource(Res.string.apps_used_today)
    val yesterday = stringResource(Res.string.apps_used_yesterday)
    val daysAgo = stringResource(Res.string.apps_used_days, DAYS_PLACEHOLDER)
    fun used(app: AppStorage): String {
        val at = app.lastUsedMs ?: return "—"
        val days = ((DateUtils.nowMs() - at) / DAY_MS).toInt()
        return when {
            days <= 0 -> today
            days == 1 -> yesterday
            else -> daysAgo.replace(DAYS_PLACEHOLDER.toString(), days.toString())
        }
    }
    Box(modifier.fillMaxSize()) {
        when {
            report == null && error != null -> EmptyState(stringResource(Res.string.storage_error), error.orEmpty())
            rows.isEmpty() && !loading -> EmptyState(stringResource(Res.string.apps_empty), stringResource(Res.string.apps_empty_msg))
            else -> DataTable(
                rows = rows,
                columns = AppColumn.entries,
                widths = AppColumn.entries.associateWith { it.defaultWidth },
                sortedBy = state.sortBy,
                sortAscending = state.ascending,
                selection = state.selection,
                rowKey = { it.packageName },
                header = { headers[it].orEmpty() },
                fitColumns = true,
                typeAheadText = { it.packageName },
                onSelectionChange = { state.selection = it },
                onSort = { state.sort(it) },
                rowMenu = { app, _ ->
                    Item(stringResource(Res.string.apps_clear_cache)) { actions.clearCache(listOf(app)) }
                    if (canArchive) Item(stringResource(Res.string.apps_archive)) { actions.archive(listOf(app)) }
                    Item(stringResource(Res.string.apps_uninstall)) { onUninstall(listOf(app)) }
                    Separator()
                    Item(stringResource(Res.string.apps_clear_data)) { onClearData(app) }
                    Item(stringResource(Res.string.apps_force_stop)) { actions.forceStop(app) }
                    Separator()
                    Item(stringResource(Res.string.apps_show_files)) { onShowFiles("/sdcard/Android/data/${app.packageName}") }
                    Item(stringResource(Res.string.apps_download_apk)) { actions.downloadApk(listOf(app)) }
                    Item(stringResource(Res.string.apps_copy_package)) { copyToClipboard(app.packageName) }
                },
            ) { app, column ->
                when (column) {
                    AppColumn.NAME -> TableText(
                        buildAnnotatedString {
                            append(app.packageName)
                            if (app.system) withStyle(SpanStyle(color = Colors.secondaryText)) { append("  $system") }
                        }
                    )
                    AppColumn.APP -> TableText(Utils.bytesToDisplayString(app.appBytes))
                    AppColumn.DATA -> TableText(Utils.bytesToDisplayString(app.dataBytes))
                    AppColumn.CACHE -> TableText(Utils.bytesToDisplayString(app.cacheBytes))
                    AppColumn.TOTAL -> TableText(AnnotatedString(Utils.bytesToDisplayString(app.totalBytes), SpanStyle(fontWeight = FontWeight.SemiBold)))
                    AppColumn.USED -> TableText(used(app))
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

// a number that can't be a real day count, so the formatted string's slot can be found and filled
// outside composition
private const val DAYS_PLACEHOLDER = 987654321
private const val DAY_MS = 24L * 60 * 60 * 1000
