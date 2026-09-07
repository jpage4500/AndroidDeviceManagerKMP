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
    DATE("Date", 150),
    ;

    fun text(file: DeviceFile): String? = when (this) {
        NAME -> file.name
        SIZE -> if (file.isDirectory) null else Utils.bytesToDisplayString(file.size)
        DATE -> file.modifiedMs.takeIf { it > 0 }
            ?.let { DateUtils.formatDate(DateUtils.timeToDate(it * MILLIS), "MM/dd/yy h:mm a") }
    }

    fun sortKey(file: DeviceFile): Comparable<*>? = when (this) {
        NAME -> file.name.lowercase()
        SIZE -> file.size
        DATE -> file.modifiedMs
    }

    companion object {
        // adb reports mtime in seconds
        private const val MILLIS = 1000L
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
class FileBrowser(private val serial: String) {
    private val log = LoggerConfig.createLogger("FileBrowser")

    private val _path = MutableStateFlow(START_PATH)
    val path: StateFlow<String> = _path.asStateFlow()

    private val _files = MutableStateFlow<List<DeviceFile>>(emptyList())
    val files: StateFlow<List<DeviceFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sort = MutableStateFlow(FileColumn.NAME to true)
    val sort: StateFlow<Pair<FileColumn, Boolean>> = _sort.asStateFlow()

    private var job: Job? = null

    fun open(scope: CoroutineScope, to: String = _path.value) {
        job?.cancel()
        job = scope.launch {
            _loading.value = true
            _error.value = null
            val target = normalise(to)
            DeviceManager.listFiles(serial, target)
                .onSuccess {
                    _path.value = target
                    // "." and ".." come back as ordinary entries; nothing wants to see them
                    _files.value = it.filterNot { file -> file.name == "." || file.name == ".." }
                    log.d("open: $target: ${_files.value.size} files")
                }
                .onFailure {
                    _error.value = it.message
                    log.w("open: $target: ${it.message}")
                }
            _loading.value = false
        }
    }

    fun up(scope: CoroutineScope) {
        val parent = _path.value.trimEnd('/').substringBeforeLast('/', "")
        open(scope, parent.ifEmpty { "/" })
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

    /** save a file into the app's own download folder. @return the name it was saved as */
    suspend fun download(file: DeviceFile): Result<String> {
        StatusManager.post("Downloading ${file.name}", progress = 0)
        return DeviceManager.pullFile(serial, file.path)
            .mapCatching { bytes ->
                val ok = StorageManager.writeBytes(PATH_DOWNLOADS, file.name, bytes)
                if (!ok) throw IllegalStateException("couldn't write ${file.name}")
                StatusManager.post("Downloaded ${file.name}", progress = 100)
                file.name
            }
            .onFailure { StatusManager.post("${file.name}: ${it.message}", isError = true) }
    }

    suspend fun delete(files: List<DeviceFile>): Result<Unit> {
        val quoted = files.joinToString(" ") { Utils.shellQuote(it.path) }
        return DeviceManager.runCommand(serial, "rm -rf $quoted", "Deleting on").map { }
    }

    suspend fun newFolder(name: String): Result<Unit> {
        val target = Utils.shellQuote("${_path.value.trimEnd('/')}/$name")
        return DeviceManager.runCommand(serial, "mkdir -p $target", "Creating folder on").map { }
    }

    /** what a path is once the slashes are tidy — `/sdcard/` and `//sdcard` are the same folder. */
    private fun normalise(path: String): String {
        val trimmed = path.trim().ifEmpty { "/" }
        val collapsed = trimmed.replace(Regex("/+"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }

    companion object {
        /** where a browser opens: the one folder anyone actually wants. */
        const val START_PATH = "/sdcard"
        const val PATH_DOWNLOADS = "downloads"
    }
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
