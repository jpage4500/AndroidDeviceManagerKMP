package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
