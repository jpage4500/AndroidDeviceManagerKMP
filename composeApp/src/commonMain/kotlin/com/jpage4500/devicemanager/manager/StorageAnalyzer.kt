package com.jpage4500.devicemanager.manager

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

/** what the device's storage holds, by kind — `dumpsys diskstats`' totals, in the order they're drawn. */
enum class StorageCategory { APPS, IMAGES, VIDEO, AUDIO, DOWNLOADS, OTHER, SYSTEM }

/** one installed package and the space it takes. */
data class AppStorage(
    val packageName: String,
    val appBytes: Long,
    /** the app's data, its cache included — that is how Android reports it. */
    val dataBytes: Long,
    val cacheBytes: Long,
    /** preinstalled: not in `pm list packages -3`. */
    val system: Boolean,
    /** when it was last opened; null when usage stats don't mention it. */
    val lastUsedMs: Long?,
) {
    val totalBytes: Long get() = appBytes + dataBytes
}

/** a file a cleanup scan turned up. */
data class FoundFile(val path: String, val bytes: Long, val modifiedMs: Long) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val folder: String get() = path.trimEnd('/').substringBeforeLast('/')
}

/** a kind of space that can be given back, as the Storage view's Free up space list offers it. */
enum class Cleanup {
    /** files deleted on the phone and kept 30 days by MediaStore as `.trashed-<expiry>-<name>`. */
    TRASH,
    /** every app's cache, which apps rebuild — one `pm trim-caches`. */
    CACHES,
    LARGE_FILES,
    APKS,
    /** what's left in the shell's scratch folder — pushed installers, binaries, test files. */
    ADB_TEMP,
    /** installed apps not opened in [StorageAnalyzer.UNUSED_DAYS]. */
    UNUSED_APPS,
    ;

    /** safe to tick by default: nothing the user made goes with it. */
    val safe: Boolean get() = this == TRASH || this == CACHES
}

/** df and diskstats for one device, read together. */
data class StorageReport(
    val totalBytes: Long?,
    val freeBytes: Long?,
    /** empty when Android hasn't measured the device yet — a new one, or one just wiped. */
    val categories: Map<StorageCategory, Long>,
    /** the apps' cache, all together. */
    val cacheBytes: Long,
    val apps: List<AppStorage>,
    /** whether usage stats answered at all; without them no app can be called unused. */
    val usageKnown: Boolean,
)

/**
 * one device's storage: what's using it, and what can be given back.
 *
 * Held by the Files tab's [FileBrowser], so a tab switch doesn't throw a scan away. Every number is
 * read over the shell, without root:
 *
 * - **totals** from `df /data`, the same read the device list makes for its Free column.
 * - **categories and per-app sizes** from `dumpsys diskstats`. One fast call, but it prints what a
 *   daily job measured, so it can be up to a day old — and empty until that job first runs.
 * - **last used** from `dumpsys usagestats`, filtered on the device to its `lastTimeUsed` lines — the
 *   whole dump runs to megabytes. Best effort: the format isn't a contract.
 * - **cleanup scans** are each one `find`, run side by side, filling in as they finish.
 */
class StorageAnalyzer(private val deviceId: String) {
    private val log = LoggerConfig.createLogger("StorageAnalyzer")

    private val _report = MutableStateFlow<StorageReport?>(null)
    val report: StateFlow<StorageReport?> = _report.asStateFlow()

    private val _found = MutableStateFlow<Map<Cleanup, List<FoundFile>>>(emptyMap())

    /** each file scan's result, once it has finished. */
    val found: StateFlow<Map<Cleanup, List<FoundFile>>> = _found.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    // set once a whole analysis has finished, so coming back to the view doesn't scan again — and a
    // scan cut off by leaving it does run again
    private var analyzed = false

    /** analyze, unless it's been done. */
    fun start(scope: CoroutineScope) {
        if (analyzed || job?.isActive == true) return
        rescan(scope)
    }

    fun rescan(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch { analyze() }
    }

    private suspend fun analyze() = coroutineScope {
        val timer = Timer()
        _loading.value = true
        _error.value = null
        // the file scans don't wait for the report: the slowest is a walk of all of /sdcard
        val scans = FILE_SCANS.map { (kind, command) ->
            launch {
                val files = DeviceManager.exec(deviceId, command).map(::parseFound).getOrElse {
                    log.w("analyze: $kind: ${it.message}")
                    emptyList()
                }
                _found.update { it + (kind to files.sortedByDescending { f -> f.bytes }) }
            }
        }
        readReport()
            .onSuccess { _report.value = it }
            .onFailure {
                _error.value = it.message
                log.w("analyze: ${it.message}")
            }
        scans.joinAll()
        _loading.value = false
        analyzed = _error.value == null
        log.d("analyze: done in ${timer.elapsedTimeMs()}ms, ${_report.value?.apps?.size ?: 0} apps")
    }

    private suspend fun readReport(): Result<StorageReport> = coroutineScope {
        val disk = async { DeviceManager.exec(deviceId, DeviceDetails.CMD_DISK) }
        val stats = async { DeviceManager.exec(deviceId, CMD_DISKSTATS) }
        val user = async { DeviceManager.exec(deviceId, CMD_USER_PACKAGES) }
        val usage = async { DeviceManager.exec(deviceId, CMD_USAGE) }
        val space = DeviceDetails.parseDiskSpace(disk.await().getOrElse { return@coroutineScope Result.failure(it) })
        val diskstats = parseDiskStats(stats.await().getOrNull().orEmpty())
        val userPackages = user.await().getOrNull().orEmpty().lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toSet()
        val lastUsed = parseUsage(usage.await().getOrNull().orEmpty())
        val sized = diskstats.apps.associateBy { it.packageName }
        // an app diskstats hasn't measured yet — installed since its last run — still gets a row
        val apps = (sized.keys + userPackages).map { pkg ->
            val known = sized[pkg]
            AppStorage(
                packageName = pkg,
                appBytes = known?.appBytes ?: 0,
                dataBytes = known?.dataBytes ?: 0,
                cacheBytes = known?.cacheBytes ?: 0,
                system = pkg !in userPackages,
                lastUsedMs = lastUsed[pkg],
            )
        }
        Result.success(
            StorageReport(
                totalBytes = space.totalBytes,
                freeBytes = space.freeBytes,
                categories = diskstats.categories,
                cacheBytes = diskstats.cacheBytes,
                apps = apps,
                usageKnown = lastUsed.isNotEmpty(),
            )
        )
    }

    /** installed apps not opened in [UNUSED_DAYS] — none when usage stats said nothing. */
    fun unusedApps(report: StorageReport): List<AppStorage> {
        if (!report.usageKnown) return emptyList()
        val cutoff = DateUtils.nowMs() - UNUSED_DAYS * DAY_MS
        // missing from usage stats means not opened in as long as they remember, which is longer
        return report.apps.filter { !it.system && (it.lastUsedMs ?: 0) < cutoff }.sortedByDescending { it.totalBytes }
    }

    // --- acting on it ---

    suspend fun emptyTrash(files: List<FoundFile>): Result<Unit> = deleteFiles(files, "Emptying trash on")

    /** every app's cache: asks for more free space than any device has, so it trims all of them. */
    suspend fun trimCaches(): Result<Unit> =
        DeviceManager.runCommand(deviceId, "pm trim-caches 999999999999999", "Clearing app caches on").map { }

    suspend fun deleteFiles(files: List<FoundFile>, label: String = "Deleting on"): Result<Unit> {
        // a few hundred paths to an `rm` at a time — one command line has a length limit
        files.chunked(DELETE_CHUNK).forEach { chunk ->
            val quoted = chunk.joinToString(" ") { Utils.shellQuote(it.path) }
            DeviceManager.runCommand(deviceId, "rm -rf $quoted", label).onFailure { return Result.failure(it) }
        }
        val gone = files.map { it.path }.toSet()
        _found.update { found -> found.mapValues { (_, list) -> list.filterNot { it.path in gone } } }
        return Result.success(Unit)
    }

    /**
     * one app's cache. `pm clear --cache-only` is Android 14's; older devices only let the shell
     * clear the cache it can reach, the one on shared storage.
     */
    suspend fun clearCache(app: AppStorage): Result<Unit> {
        val pkg = Utils.shellQuote(app.packageName)
        val output = DeviceManager.runCommand(deviceId, "pm clear --cache-only $pkg", "Clearing cache on")
            .getOrElse { return Result.failure(it) }
        if (output.contains("Success")) return Result.success(Unit)
        log.d("clearCache: ${app.packageName}: ${output.trim()}, clearing the external cache instead")
        return DeviceManager.runCommand(deviceId, "rm -rf /sdcard/Android/data/${app.packageName}/cache", "Clearing cache on").map { }
    }

    suspend fun clearData(app: AppStorage): Result<Unit> =
        pm("clear ${Utils.shellQuote(app.packageName)}", "Clearing data on")

    /**
     * a preinstalled app can't be removed from the system image, only for the user — which frees
     * its data and its updates. `cmd package install-existing` brings one back.
     */
    suspend fun uninstall(app: AppStorage): Result<Unit> {
        val user = if (app.system) "--user 0 " else ""
        return pm("uninstall $user${Utils.shellQuote(app.packageName)}", "Uninstalling on")
    }

    /** Android 15: the APK goes, the data and the icon stay, and a tap re-downloads it. */
    suspend fun archive(app: AppStorage): Result<Unit> =
        pm("archive ${Utils.shellQuote(app.packageName)}", "Archiving on")

    suspend fun forceStop(app: AppStorage): Result<Unit> =
        DeviceManager.runCommand(deviceId, "am force-stop ${Utils.shellQuote(app.packageName)}", "Stopping on").map { }

    /** the app's base APK into the download folder. A split app's other pieces aren't fetched. */
    suspend fun downloadApk(app: AppStorage): Result<String> {
        val apk = DeviceManager.exec(deviceId, "pm path ${Utils.shellQuote(app.packageName)}")
            .getOrElse { return Result.failure(it) }
            .lineSequence()
            .map { it.trim().removePrefix("package:") }
            .firstOrNull { it.endsWith("base.apk") || it.endsWith(".apk") }
            ?: return Result.failure(IllegalStateException("no APK for ${app.packageName}"))
        StatusManager.post("Downloading ${app.packageName}", progress = 0)
        return DeviceManager.pullFile(deviceId, apk).mapCatching { bytes ->
            val name = "${app.packageName}.apk"
            val path = SaveFolders.save(SaveFolders.downloads(), FileBrowser.PATH_DOWNLOADS, name, bytes)
                ?: throw IllegalStateException("couldn't write $name")
            StatusManager.post("Downloaded $name", progress = 100)
            path
        }.onFailure { StatusManager.post("${app.packageName}: ${it.message}", isError = true) }
    }

    /** an app gone from the device, gone from the report — so the list doesn't wait for a rescan. */
    fun forget(packages: Collection<String>) {
        val gone = packages.toSet()
        _report.update { report -> report?.copy(apps = report.apps.filterNot { it.packageName in gone }) }
    }

    /**
     * what an action did, written into the report by hand: diskstats won't measure again until its
     * daily run, so a rescan would put the cleared cache straight back.
     */
    fun adjust(packages: Collection<String>, change: (AppStorage) -> AppStorage) {
        val touched = packages.toSet()
        _report.update { report ->
            report?.copy(apps = report.apps.map { if (it.packageName in touched) change(it) else it })
        }
    }

    /** every app's cache gone, after [trimCaches]. */
    fun cachesTrimmed() {
        _report.update { report ->
            report?.copy(
                cacheBytes = 0,
                apps = report.apps.map { it.copy(dataBytes = (it.dataBytes - it.cacheBytes).coerceAtLeast(0), cacheBytes = 0) },
            )
        }
    }

    /** free space read again — the one number that does change the moment something is deleted. */
    suspend fun refreshSpace() {
        val space = DeviceDetails.parseDiskSpace(DeviceManager.exec(deviceId, DeviceDetails.CMD_DISK).getOrNull())
        _report.update { report -> report?.copy(freeBytes = space.freeBytes ?: report.freeBytes, totalBytes = space.totalBytes ?: report.totalBytes) }
    }

    /** `pm` prints "Success" or "Failure [REASON]", and exec doesn't carry the exit code. */
    private suspend fun pm(args: String, label: String): Result<Unit> {
        val output = DeviceManager.runCommand(deviceId, "pm $args", label).getOrElse { return Result.failure(it) }
        if (output.contains("Success")) return Result.success(Unit)
        val reason = output.trim().lineSequence().lastOrNull().orEmpty().ifEmpty { "pm $args failed" }
        log.w("pm: $args: $reason")
        StatusManager.post(reason, isError = true)
        return Result.failure(IllegalStateException(reason))
    }

    private data class DiskStats(val categories: Map<StorageCategory, Long>, val cacheBytes: Long, val apps: List<AppStorage>)

    /**
     * ```
     * App Size: 13404569600
     * App Data Size: 6578266112
     * App Cache Size: 1189318656
     * Photos Size: 3829657600
     * ...
     * Package Names: ["com.google.android.youtube","com.android.chrome",...]
     * App Sizes: [123,456,...]
     * App Data Sizes: [...]
     * Cache Sizes: [...]
     * ```
     * the four lists are in step, one entry per package.
     */
    private fun parseDiskStats(text: String): DiskStats {
        val values = text.lineSequence()
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() } }
            .toMap()
        fun size(key: String): Long? = values[key]?.toLongOrNull()
        fun numbers(key: String): List<Long> = NUMBER.findAll(values[key].orEmpty()).map { it.value.toLong() }.toList()

        val appTotal = (size("App Size") ?: 0) + (size("App Data Size") ?: 0)
        val categories = buildMap {
            if (values.containsKey("App Size")) put(StorageCategory.APPS, appTotal)
            size("Photos Size")?.let { put(StorageCategory.IMAGES, it) }
            size("Videos Size")?.let { put(StorageCategory.VIDEO, it) }
            size("Audio Size")?.let { put(StorageCategory.AUDIO, it) }
            size("Downloads Size")?.let { put(StorageCategory.DOWNLOADS, it) }
            size("Other Size")?.let { put(StorageCategory.OTHER, it) }
            size("System Size")?.let { put(StorageCategory.SYSTEM, it) }
        }.filterValues { it > 0 }

        val names = QUOTED.findAll(values["Package Names"].orEmpty()).map { it.groupValues[1] }.toList()
        val apps = numbers("App Sizes")
        val data = numbers("App Data Sizes")
        val caches = numbers("Cache Sizes")
        val sized = names.mapIndexed { i, pkg ->
            AppStorage(pkg, apps.getOrElse(i) { 0 }, data.getOrElse(i) { 0 }, caches.getOrElse(i) { 0 }, system = false, lastUsedMs = null)
        }
        return DiskStats(categories, size("App Cache Size") ?: 0, sized)
    }

    /**
     * `package=com.android.chrome totalTimeUsed="1:23" lastTimeUsed="2025-11-16 09:12:33" ...`, once
     * per package per stats interval — the newest wins. A package never used says 1970.
     */
    private fun parseUsage(text: String): Map<String, Long> {
        val zone = TimeZone.currentSystemDefault()
        val latest = HashMap<String, Long>()
        USAGE.findAll(text).forEach { match ->
            val pkg = match.groupValues[1]
            val at = DateUtils.parseDate(match.groupValues[2], "yyyy-MM-dd HH:mm:ss") ?: return@forEach
            if (at.year < 2000) return@forEach
            val ms = at.toInstant(zone).toEpochMilliseconds()
            if (ms > (latest[pkg] ?: 0)) latest[pkg] = ms
        }
        return latest
    }

    /** [FILE_SCANS]' `stat -c '%s %Y %n'` lines: size, modified seconds, then the path, spaces and all. */
    private fun parseFound(text: String): List<FoundFile> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val bytes = parts[0].toLongOrNull() ?: return@mapNotNull null
        val seconds = parts[1].toLongOrNull() ?: 0
        FoundFile(parts[2], bytes, seconds * 1000)
    }.toList()

    companion object {
        const val UNUSED_DAYS = 90L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        // a file this big is worth a look before it's worth a delete
        private const val LARGE_FILE_BYTES = 100L * 1024 * 1024
        private const val DELETE_CHUNK = 200

        private const val CMD_DISKSTATS = "dumpsys diskstats"
        private const val CMD_USER_PACKAGES = "pm list packages -3"
        private const val CMD_USAGE = "dumpsys usagestats 2>/dev/null | grep lastTimeUsed="

        // `/sdcard/` with the slash, so `find` follows the symlink into shared storage
        private const val STAT = "-exec stat -c '%s %Y %n' {} + 2>/dev/null"
        private const val NOT_TRASH = "! -name '.trashed-*'"

        private val FILE_SCANS = listOf(
            Cleanup.TRASH to "find /sdcard/ -type f -name '.trashed-*' $STAT",
            Cleanup.LARGE_FILES to "find /sdcard/ -type f -size +${LARGE_FILE_BYTES}c $NOT_TRASH $STAT",
            Cleanup.APKS to "find /sdcard/ -type f -iname '*.apk' $NOT_TRASH $STAT",
            Cleanup.ADB_TEMP to "find /data/local/tmp/ -mindepth 1 -maxdepth 1 -type f $STAT",
        )

        private val NUMBER = Regex("-?\\d+")
        private val QUOTED = Regex("\"([^\"]+)\"")
        private val USAGE = Regex("package=(\\S+).*?lastTimeUsed=\"([^\"]+)\"")
    }
}
