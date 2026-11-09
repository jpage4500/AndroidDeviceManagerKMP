package com.jpage4500.devicemanager.manager

import androidx.compose.ui.unit.*
import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** which column the file list is sorted on. */
enum class FileColumn(val label: String, val defaultWidth: Int) {
    NAME("Name", 320),
    SIZE("Size", 90),
    DATE("Modified", 150),
    ;

    fun text(file: DeviceFile): String? = when (this) {
        NAME -> file.name
        SIZE -> if (file.isDirectory) "—" else Utils.bytesToDisplayString(file.size)
        DATE -> file.modifiedMs.takeIf { it > 0 }?.let { modified(it) }
    }

    /** `Oct 27, 10:42 AM` this year; `Jan 1, 2009` for anything older, where the time says nothing. */
    private fun modified(ms: Long): String {
        val date = DateUtils.timeToDate(ms)
        val pattern = if (date.year == DateUtils.getNow().year) "MMM d, h:mm a" else "MMM d, yyyy"
        return DateUtils.formatDate(date, pattern)
    }

    fun sortKey(file: DeviceFile): Comparable<*>? = when (this) {
        NAME -> file.name.lowercase()
        SIZE -> file.size
        DATE -> file.modifiedMs
    }
}

/**
 * one device's filesystem, as a browser sees it.
 *
 * Held per window, like [LogsSource]: two explorers on two devices are two of these. Ported from the
 * Swing app's `ExploreScreen`, minus the root toggle — `adb root` only works on a userdebug build
 * and the Swing app's own handling of it is one of the parts worth re-deciding rather than copying.
 *
 * **Directories sort before files**, always, whichever column is sorted — that is what makes a deep
 * tree navigable, and no file manager does it any other way.
 */
class FileBrowser(private val deviceId: String) {
    private val log = LoggerConfig.createLogger("FileBrowser")

    private val _path = MutableStateFlow(START_PATH)
    val path: StateFlow<String> = _path.asStateFlow()

    private val _files = MutableStateFlow<List<DeviceFile>>(emptyList())
    val files: StateFlow<List<DeviceFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _missing = MutableStateFlow(false)

    /** the open folder isn't on the device — a bookmark made on another one, or a mistyped path. */
    val missing: StateFlow<Boolean> = _missing.asStateFlow()

    private val _query = MutableStateFlow("")

    /** "Filter this folder" — kept here, with the folder, so a tab switch doesn't lose it. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(FileColumn.NAME to true)
    val sort: StateFlow<Pair<FileColumn, Boolean>> = _sort.asStateFlow()

    private var job: Job? = null

    // whether the first folder has been picked — a browser kept by a Files tab comes back to where
    // it was, not to Download again
    private var started = false

    /**
     * the first open: Download if the device has one, which is where anything worth pulling off a
     * phone usually is, and [START_PATH] if not. After that, the folder it was left in.
     */
    fun start(scope: CoroutineScope) {
        if (started) return open(scope)
        started = true
        job?.cancel()
        job = scope.launch {
            _loading.value = true
            // a sync LIST of a missing folder answers with nothing rather than an error, so ask
            val probe = DeviceManager.exec(deviceId, "[ -d $DOWNLOAD_PATH ] && echo yes").getOrNull()
            load(if (probe?.contains("yes") == true) DOWNLOAD_PATH else START_PATH)
        }
    }

    fun open(scope: CoroutineScope, to: String = _path.value) {
        job?.cancel()
        job = scope.launch { load(to) }
    }

    private suspend fun load(to: String) {
        _loading.value = true
        _error.value = null
        val target = normalise(to)
        DeviceManager.listFiles(deviceId, target)
            .onSuccess {
                // "." and ".." come back as ordinary entries; nothing wants to see them
                val listed = it.filterNot { file -> file.name == "." || file.name == ".." }
                // a sync LIST of a folder that isn't there answers with nothing, the same as an
                // empty one — so only an empty answer costs the extra round trip to tell them apart
                _missing.value = listed.isEmpty() && !exists(target)
                // a filter typed for one folder means nothing in the next
                if (target != _path.value) _query.value = ""
                // moved to even when missing, so Up and the crumbs lead somewhere real
                _path.value = target
                _files.value = listed
                log.d("load: $target: ${listed.size} files${if (_missing.value) ", missing" else ""}")
            }
            .onFailure {
                _error.value = it.message
                log.w("load: $target: ${it.message}")
            }
        _loading.value = false
    }

    private suspend fun exists(path: String): Boolean =
        DeviceManager.exec(deviceId, "[ -d ${Utils.shellQuote(path)} ] && echo yes").getOrNull()
            // no answer at all isn't proof it's missing; say empty rather than wrong
            ?.contains("yes") ?: true

    fun up(scope: CoroutineScope) {
        val parent = _path.value.trimEnd('/').substringBeforeLast('/', "")
        open(scope, parent.ifEmpty { "/" })
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    /** what the table shows: dot-files only when asked for, and the folder filter applied. */
    fun visible(files: List<DeviceFile>, showHidden: Boolean, query: String): List<DeviceFile> =
        files.filter { file ->
            (showHidden || !file.name.startsWith('.')) &&
                (query.isBlank() || file.name.containsIgnoreCase(query.trim()))
        }

    fun sortBy(column: FileColumn) {
        val (current, ascending) = _sort.value
        _sort.value = if (current == column) column to !ascending else column to true
    }

    /** the rows in the order the table draws them: folders first, then whatever the sort says. */
    fun sorted(files: List<DeviceFile>): List<DeviceFile> {
        val (column, ascending) = _sort.value
        @Suppress("UNCHECKED_CAST")
        val byColumn = compareBy<DeviceFile, Comparable<Any>?>(nullsLast()) {
            column.sortKey(it) as Comparable<Any>?
        }
        val ordered = if (ascending) byColumn else byColumn.reversed()
        return files.sortedWith(compareByDescending<DeviceFile> { it.isDirectory }.then(ordered))
    }

    /**
     * save a file into the download folder (Settings › Files; app storage on a phone).
     * @return the path it was saved to
     */
    suspend fun download(file: DeviceFile): Result<String> {
        StatusManager.post("Downloading ${file.name}", progress = 0)
        return DeviceManager.pullFile(deviceId, file.path)
            .mapCatching { bytes ->
                val path = SaveFolders.save(SaveFolders.downloads(), PATH_DOWNLOADS, file.name, bytes)
                    ?: throw IllegalStateException("couldn't write ${file.name}")
                StatusManager.post("Downloaded ${path.substringAfterLast('/').substringAfterLast('\\')}", progress = 100)
                path
            }
            .onFailure { StatusManager.post("${file.name}: ${it.message}", isError = true) }
    }

    suspend fun delete(files: List<DeviceFile>): Result<Unit> {
        val quoted = files.joinToString(" ") { Utils.shellQuote(it.path) }
        return DeviceManager.runCommand(deviceId, "rm -rf $quoted", "Deleting on").map { }
    }

    suspend fun newFolder(name: String): Result<Unit> {
        val target = Utils.shellQuote("${_path.value.trimEnd('/')}/$name")
        return DeviceManager.runCommand(deviceId, "mkdir -p $target", "Creating folder on").map { }
    }

    suspend fun rename(file: DeviceFile, name: String): Result<Unit> {
        val parent = file.path.substringBeforeLast('/', "").ifEmpty { "" }
        val target = Utils.shellQuote("$parent/$name")
        return DeviceManager.runCommand(deviceId, "mv ${Utils.shellQuote(file.path)} $target", "Renaming on").map { }
    }

    /**
     * install an APK that's already on the device.
     *
     * copied to the scratch folder first, as [InstallManager] pushes there: `pm` runs as the system
     * and on recent Android can't read shared storage, where a downloaded APK usually sits.
     */
    suspend fun install(file: DeviceFile): Result<Unit> {
        val scratch = Utils.shellQuote("$SCRATCH/${file.name}")
        val output = DeviceManager.runCommand(
            deviceId = deviceId,
            command = "cp ${Utils.shellQuote(file.path)} $scratch && pm install -r -d $scratch; rm -f $scratch",
            label = "Installing on",
        ).getOrElse { return Result.failure(it) }
        // pm prints "Success" or "Failure [REASON]"; exec doesn't carry the exit code
        return if (output.contains("Success", ignoreCase = true)) {
            StatusManager.post("Installed ${file.name}", progress = 100)
            Result.success(Unit)
        } else {
            val reason = output.trim().lineSequence().lastOrNull().orEmpty()
            log.w("install: ${file.path}: $reason")
            StatusManager.post("${file.name}: $reason", isError = true)
            Result.failure(IllegalStateException(reason))
        }
    }

    /** copy files from this machine into the open folder. @return how many arrived */
    suspend fun upload(files: List<LocalFile>): Int {
        val folder = _path.value.trimEnd('/')
        var sent = 0
        files.forEachIndexed { index, file ->
            StatusManager.post("Uploading ${file.name}", progress = index * 100 / files.size)
            val bytes = readLocalFile(file.path)
            if (bytes == null) {
                StatusManager.post("Couldn't read ${file.name}", isError = true)
                return@forEachIndexed
            }
            DeviceManager.pushFile(deviceId, bytes, "$folder/${file.name}")
                .onSuccess { sent++ }
                .onFailure { StatusManager.post("${file.name}: ${it.message}", isError = true) }
        }
        if (sent > 0) StatusManager.post("Uploaded $sent of ${files.size} to $folder", progress = 100)
        return sent
    }

    /**
     * save a folder and everything under it into the download folder, as a folder of the same name.
     *
     * `find` lists the files rather than walking the tree a sync LIST at a time: one round trip for
     * the whole tree instead of one per folder. An empty folder has no file to carry it, so one
     * doesn't come across.
     *
     * @return the local folder it was saved as
     */
    suspend fun downloadFolder(dir: DeviceFile): Result<String> {
        val root = dir.path.trimEnd('/')
        StatusManager.post("Downloading ${dir.name}", progress = 0)
        val listing = DeviceManager.exec(deviceId, "find ${Utils.shellQuote(root)} -type f")
            .getOrElse {
                StatusManager.post("${dir.name}: ${it.message}", isError = true)
                return Result.failure(it)
            }
        val remote = listing.lineSequence().map { it.trim() }.filter { it.startsWith("$root/") }.toList()
        if (remote.isEmpty()) {
            StatusManager.post("${dir.name}: nothing to download", isError = true)
            return Result.failure(IllegalStateException("no files in ${dir.name}"))
        }
        // never into a folder already there: a second download of DCIM lands as "DCIM (2)"
        val base = SaveFolders.downloads()?.trimEnd('/', '\\')
        val name = if (base == null) dir.name else generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) dir.name else "${dir.name} ($n)" }
            .first { localFileInfo("$base/$it") == null }
        var saved = 0
        remote.forEachIndexed { index, path ->
            StatusManager.post("Downloading ${dir.name}: ${index + 1} of ${remote.size}", progress = index * 100 / remote.size)
            val relative = path.removePrefix("$root/")
            val bytes = DeviceManager.pullFile(deviceId, path).getOrElse {
                log.w("downloadFolder: $path: ${it.message}")
                return@forEachIndexed
            }
            val ok = if (base == null) {
                val sub = relative.substringBeforeLast('/', "")
                val folder = listOf(PATH_DOWNLOADS, name, sub).filter { it.isNotEmpty() }.joinToString("/")
                StorageManager.writeBytes(folder, relative.substringAfterLast('/'), bytes)
            } else {
                writeLocalFile("$base/$name/$relative", bytes)
            }
            if (ok) saved++ else log.w("downloadFolder: couldn't write $relative")
        }
        val local = if (base == null) StorageManager.getAbsolutePath(PATH_DOWNLOADS, name) ?: name else "$base/$name"
        if (saved == remote.size) {
            StatusManager.post("Downloaded ${dir.name}: $saved files", progress = 100)
        } else {
            StatusManager.post("Downloaded $saved of ${remote.size} files from ${dir.name}", isError = true)
        }
        return Result.success(local)
    }

    /** what a path is once the slashes are tidy — `/sdcard/` and `//sdcard` are the same folder. */
    private fun normalise(path: String): String {
        val trimmed = path.trim().ifEmpty { "/" }
        val collapsed = trimmed.replace(Regex("/+"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }

    companion object {
        /** where a browser opens when the device has no [DOWNLOAD_PATH]. */
        const val START_PATH = "/sdcard"

        /** where a browser opens: what's been downloaded on the phone is what gets pulled off it. */
        const val DOWNLOAD_PATH = "/sdcard/Download"
        const val PATH_DOWNLOADS = "downloads"

        /** somewhere every Android version lets the shell write and `pm` read. */
        private const val SCRATCH = "/data/local/tmp"
    }
}

/** folders kept to one click away in Places, across every device — a path, not a device's folder. */
object FileBookmarks {
    private val _paths = MutableStateFlow(load())
    val paths: StateFlow<List<String>> = _paths.asStateFlow()

    fun contains(path: String): Boolean = path in _paths.value

    fun toggle(path: String) {
        _paths.value = if (contains(path)) _paths.value - path else _paths.value + path
        SettingsManager.setString(AppPref.FILES_BOOKMARKS, _paths.value.joinToString("\n"))
    }

    private fun load(): List<String> =
        SettingsManager.getString(AppPref.FILES_BOOKMARKS).orEmpty().lines().filter { it.isNotBlank() }
}

/** which columns the file table shows, and how wide — persisted, like the other two tables. */
object FileTableState {
    private val _widths = MutableStateFlow(load())
    val widths: StateFlow<Map<FileColumn, Dp>> = _widths.asStateFlow()

    // a resize arrives once per pixel of the drag; the file it lands in doesn't need to
    private val widthWrite = Debouncer()

    fun resize(column: FileColumn, width: Dp) {
        _widths.value = _widths.value + (column to width)
        widthWrite.submit {
            val asMap = _widths.value.entries.associate { (key, dp) -> key.name to dp.value.toInt() }
            SettingsManager.setString(AppPref.FILES_COLUMN_WIDTHS, JsonUtils.toJson(asMap) ?: "")
        }
    }

    private fun load(): Map<FileColumn, Dp> =
        JsonUtils.stringToMap<Int>(SettingsManager.getString(AppPref.FILES_COLUMN_WIDTHS))
            .mapNotNull { (name, px) -> Utils.enumFromName<FileColumn>(name)?.let { it to px.dp } }
            .toMap()
}
