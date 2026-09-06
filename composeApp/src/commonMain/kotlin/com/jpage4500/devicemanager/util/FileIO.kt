package com.jpage4500.devicemanager.util

expect fun createFileIO(): FileIO

/**
 * the filesystem, as little of it as the app needs.
 *
 * everything is relative to a per-platform root — the app's internal storage on android,
 * `~/.android-device-manager` on desktop, Documents on iOS — so nothing above this ever writes an
 * absolute path. reached through [StorageManager], which serialises the writes; nothing should call
 * an implementation of this directly.
 */
interface FileIO {
    /** @return true if the write landed */
    fun writeFile(folderPath: String, fileName: String, content: String, append: Boolean): Boolean

    /**
     * write raw bytes, replacing whatever was there.
     *
     * everything else here is text — this exists for a screenshot or a pulled file, which is the
     * only binary the app writes itself.
     */
    fun writeBytes(folderPath: String, fileName: String, bytes: ByteArray): Boolean

    /** @return the file's content, or null if it doesn't exist or couldn't be read */
    fun readFile(folderPath: String, fileName: String): String?

    /**
     * the absolute path of a file, or null if it isn't there. used to hand a file off to the
     * platform's own viewer — a log file to Console, a pulled file to Finder.
     */
    fun getAbsolutePath(folderPath: String, fileName: String): String?

    fun getFileDetails(folderPath: String, fileName: String): FileMetadata?

    /** @param extension filters by extension ("json"), null for everything */
    fun listFiles(folderPath: String, extension: String? = null): List<String>

    fun listFileMetadata(folderPath: String, extension: String? = null): List<FileMetadata>

    fun deleteFile(folderPath: String, fileName: String): Boolean

    fun deleteAllFiles(folderPath: String, extension: String? = null): Boolean

    /** @return true if the rename landed; renaming *over* an existing file fails on iOS and windows */
    fun renameFile(folderPath: String, oldName: String, newName: String): Boolean
}
