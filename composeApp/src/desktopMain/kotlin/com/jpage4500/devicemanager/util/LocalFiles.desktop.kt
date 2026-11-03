package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

private val log = LoggerConfig.createLogger("LocalFiles")

/**
 * `java.awt.FileDialog` rather than Swing's `JFileChooser`.
 *
 * FileDialog maps to a real `NSOpenPanel` on macOS and the native dialog on Windows; JFileChooser
 * draws its own, which looks like nothing else on the machine. The trade is that its extension
 * filter is advisory on some platforms, so the result is filtered again here.
 */
actual suspend fun pickLocalFiles(title: String, extensions: List<String>): List<LocalFile> =
    withContext(Dispatchers.Main) {
        runCatching {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isMultipleMode = true
            if (extensions.isNotEmpty()) {
                dialog.setFilenameFilter { _, name ->
                    extensions.any { name.endsWith(".$it", ignoreCase = true) }
                }
            }
            dialog.isVisible = true
            dialog.files.orEmpty().toList()
        }.onFailure { log.e("pickLocalFiles: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { file: File -> extensions.isEmpty() || extensions.any { file.name.endsWith(".$it", true) } }
            .map { LocalFile(it.absolutePath, it.name, it.length()) }
    }

/** the same native dialog, in SAVE mode — it asks before replacing a file itself. */
actual suspend fun pickSaveFile(title: String, suggestedName: String, directory: String?): String? =
    withContext(Dispatchers.Main) {
        runCatching {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            dialog.directory = directory?.takeIf { File(it).isDirectory }
                ?: File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }?.absolutePath
            dialog.file = suggestedName
            dialog.isVisible = true
            val name = dialog.file ?: return@runCatching null
            File(dialog.directory, name).absolutePath
        }.onFailure { log.e("pickSaveFile: ${it.message}") }.getOrNull()
    }

actual fun openLocalFile(path: String): Boolean = runCatching {
    Desktop.getDesktop().open(File(path))
}.onFailure { log.e("openLocalFile: ${it.message}") }.isSuccess

/**
 * `Desktop.browseFileDirectory` selects the file in Finder; Windows has no such action, so Explorer
 * is asked directly, and anything else gets the folder opened with nothing selected.
 */
actual fun revealLocalFile(path: String): Boolean = runCatching {
    val file = File(path)
    val desktop = Desktop.getDesktop()
    when {
        desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
        isWindows() -> ProcessBuilder("explorer", "/select,", file.absolutePath).start()
        else -> desktop.open(file.parentFile)
    }
}.onFailure { log.e("revealLocalFile: ${it.message}") }.isSuccess

actual val localDesktopOS: DesktopOS? get() = DesktopUtils.getDesktopOS()

/**
 * the native panel on macOS, which FileDialog only opens on folders while this property is set;
 * elsewhere FileDialog can't pick a folder at all, so it's Swing's chooser there.
 */
actual suspend fun pickLocalFolder(title: String, directory: String?): String? =
    withContext(Dispatchers.Main) {
        val start = directory?.takeIf { File(it).isDirectory } ?: defaultDownloadFolder
        runCatching {
            if (DesktopUtils.isDesktopOS(DesktopOS.MAC)) {
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                try {
                    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                    dialog.directory = start
                    dialog.isVisible = true
                    val name = dialog.file ?: return@runCatching null
                    File(dialog.directory, name).absolutePath
                } finally {
                    System.setProperty("apple.awt.fileDialogForDirectories", "false")
                }
            } else {
                val chooser = JFileChooser(start).apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
            }
        }.onFailure { log.e("pickLocalFolder: ${it.message}") }.getOrNull()
    }

actual val defaultDownloadFolder: String? = File(System.getProperty("user.home"), "Downloads").absolutePath
