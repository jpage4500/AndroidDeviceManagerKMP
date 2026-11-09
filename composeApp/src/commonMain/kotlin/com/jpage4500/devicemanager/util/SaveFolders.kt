package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.data.*

private val log = LoggerConfig.createLogger("SaveFolders")

/**
 * where a pulled file and a screenshot go — Settings › Files.
 *
 * On a desktop that is a folder of the user's, `~/Downloads` unless they picked another, which is
 * where the Swing app put both. A phone has no such folder ([defaultDownloadFolder] is null) and
 * keeps writing into the app's own storage, as it always has.
 */
object SaveFolders {
    /** the download folder, or null where files go to app storage. */
    fun downloads(): String? =
        SettingsManager.getString(AppPref.DOWNLOAD_FOLDER)?.takeIf { it.isNotBlank() } ?: defaultDownloadFolder

    /** the screenshot folder: its own if one was picked, otherwise [downloads]. */
    fun screenshots(): String? =
        SettingsManager.getString(AppPref.SCREENSHOT_FOLDER)?.takeIf { it.isNotBlank() } ?: downloads()

    /**
     * write [bytes] as [name] into [folder], or into app storage's [appFolder] when [folder] is null.
     *
     * never over a file already there — a folder of the user's has things in it this app didn't
     * write — so a second `log.txt` lands as `log (2).txt`.
     *
     * @return the path written, or null if it couldn't be
     */
    suspend fun save(folder: String?, appFolder: String, name: String, bytes: ByteArray): String? {
        if (folder == null) {
            if (!StorageManager.writeBytes(appFolder, name, bytes)) return null
            return StorageManager.getAbsolutePath(appFolder, name) ?: name
        }
        val dir = folder.trimEnd('/', '\\')
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val path = generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) "$dir/$name" else "$dir/$base ($n)$ext" }
            .first { localFileInfo(it) == null }
        val ok = writeLocalFile(path, bytes)
        if (!ok) log.e("save: couldn't write $path")
        return path.takeIf { ok }
    }
}
