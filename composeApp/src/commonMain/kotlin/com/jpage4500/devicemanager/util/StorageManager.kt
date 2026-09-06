package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*

/** what a directory listing knows about a file, on every platform. */
data class FileMetadata(val name: String, val lastModified: Long, val size: Long)

/**
 * every file the app writes for itself: logs, crash reports, saved screenshots.
 *
 * a thin front on [FileIO] whose real job is the dispatcher — see [writeDispatcher]. ported from
 * DashboardWeatherKMP.
 */
object StorageManager {
    private val log = LoggerConfig.createLogger("StorageManager")
    private val storage: FileIO by lazy { createFileIO() }

    // single-threaded on purpose, not just tidy: windows holds an exclusive lock across an append
    // or a rename, so two concurrent writes to the same file fail with "process cannot access the
    // file because it is being used by another process". Dispatchers.Default is a pool, not a thread
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val writeScope = CoroutineScope(writeDispatcher + SupervisorJob())

    /** queue a write on the single write thread; [onComplete] runs there too. */
    fun writeFile(
        folder: String,
        fileName: String,
        content: String,
        append: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        writeScope.launch {
            val isOk = storage.writeFile(folder, fileName, content, append)
            onComplete?.invoke(isOk)
        }
    }

    /**
     * write now, on the calling thread.
     *
     * for the crash handler only: the process is about to die and [writeScope] can't be trusted to
     * flush before it does.
     */
    fun writeFileSync(folder: String, fileName: String, content: String, append: Boolean = false): Boolean =
        storage.writeFile(folder, fileName, content, append)

    /**
     * raw bytes, on the same serialized thread the text writes use — suspending rather than
     * fire-and-forget because a caller saving a screenshot needs to know whether it landed.
     */
    suspend fun writeBytes(folder: String, fileName: String, bytes: ByteArray): Boolean =
        withContext(writeDispatcher) { storage.writeBytes(folder, fileName, bytes) }

    fun readFile(folder: String, fileName: String): String? = storage.readFile(folder, fileName)

    fun getAbsolutePath(folder: String, fileName: String): String? =
        storage.getAbsolutePath(folder, fileName)

    fun getFileDetails(folder: String, fileName: String): FileMetadata? =
        storage.getFileDetails(folder, fileName)

    fun listFiles(folder: String, extension: String? = null): List<String> =
        storage.listFiles(folder, extension)

    fun listFileMetadata(folder: String, extension: String? = null): List<FileMetadata> =
        storage.listFileMetadata(folder, extension)

    fun deleteFile(folder: String, fileName: String): Boolean = storage.deleteFile(folder, fileName)

    fun deleteAllFiles(folder: String, extension: String? = null): Boolean =
        storage.deleteAllFiles(folder, extension)

    fun renameFile(folder: String, oldName: String, newName: String): Boolean =
        storage.renameFile(folder, oldName, newName)
}
