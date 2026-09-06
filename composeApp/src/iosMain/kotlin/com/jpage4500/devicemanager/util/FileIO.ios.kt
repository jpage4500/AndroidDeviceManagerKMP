package com.jpage4500.devicemanager.util

import kotlinx.cinterop.*
import platform.Foundation.*

actual fun createFileIO(): FileIO = IosFileIO()

/** [FileIO] under the app's Documents directory. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileIO : FileIO {
    private val log = LoggerConfig.createLogger("IosFileIO")
    private val fileManager = NSFileManager.defaultManager
    private val baseDir: String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    init {
        log.d("init: base directory: $baseDir")
    }

    private fun folderPathOf(folderPath: String) = "$baseDir/$folderPath"

    private fun filePathOf(folderPath: String, fileName: String) = "$baseDir/$folderPath/$fileName"

    private fun ensureFolder(folderPath: String): String {
        val full = folderPathOf(folderPath)
        if (!fileManager.fileExistsAtPath(full)) {
            fileManager.createDirectoryAtURL(NSURL.fileURLWithPath(full), true, null, null)
        }
        return full
    }

    private fun names(folderPath: String, extension: String?): List<String> {
        val full = folderPathOf(folderPath)
        if (!fileManager.fileExistsAtPath(full)) return emptyList()
        val contents = fileManager.contentsOfDirectoryAtPath(full, null)
        val files = contents?.filterIsInstance<String>() ?: emptyList()
        return if (extension != null) files.filter { it.endsWith(".$extension") } else files
    }

    private fun metadata(folderPath: String, fileName: String): FileMetadata? {
        val filePath = filePathOf(folderPath, fileName)
        if (!fileManager.fileExistsAtPath(filePath)) return null
        val attrs = fileManager.attributesOfItemAtPath(filePath, null)
        val modDate = attrs?.get(NSFileModificationDate) as? NSDate
        val size = attrs?.get(NSFileSize) as? NSNumber
        return FileMetadata(
            name = fileName,
            lastModified = (modDate?.timeIntervalSince1970?.times(1000))?.toLong() ?: 0L,
            size = size?.longValue ?: 0L,
        )
    }

    override fun writeFile(folderPath: String, fileName: String, content: String, append: Boolean): Boolean {
        return try {
            ensureFolder(folderPath)
            val filePath = filePathOf(folderPath, fileName)
            if (append && fileManager.fileExistsAtPath(filePath)) {
                val handle = NSFileHandle.fileHandleForWritingAtPath(filePath)
                if (handle == null) {
                    // printed rather than logged: log.e would re-enter StorageManager.writeFile
                    println("IosFileIO: writeFile: failed to open file handle for $filePath")
                    return false
                }
                handle.seekToEndOfFile()
                NSString.create(string = content).dataUsingEncoding(NSUTF8StringEncoding)
                    ?.let { handle.writeData(it) }
                handle.closeFile()
            } else {
                NSString.create(string = content)
                    .writeToFile(filePath, true, NSUTF8StringEncoding, null)
            }
            true
        } catch (e: Exception) {
            println("IosFileIO: writeFile: exception for $folderPath/$fileName: ${e.message}")
            false
        }
    }

    override fun writeBytes(folderPath: String, fileName: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return try {
            ensureFolder(folderPath)
            val data = bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert())
            }
            data.writeToFile(filePathOf(folderPath, fileName), atomically = true)
        } catch (e: Exception) {
            log.e("writeBytes: exception for $folderPath/$fileName: ${e.message}")
            false
        }
    }

    override fun readFile(folderPath: String, fileName: String): String? {
        return try {
            val filePath = filePathOf(folderPath, fileName)
            if (!fileManager.fileExistsAtPath(filePath)) return null
            NSString.stringWithContentsOfFile(filePath, NSUTF8StringEncoding, null)
        } catch (e: Exception) {
            log.e("readFile: exception for $folderPath/$fileName: ${e.message}")
            null
        }
    }

    override fun getAbsolutePath(folderPath: String, fileName: String): String? {
        val filePath = filePathOf(folderPath, fileName)
        return if (fileManager.fileExistsAtPath(filePath)) filePath else null
    }

    override fun getFileDetails(folderPath: String, fileName: String): FileMetadata? =
        metadata(folderPath, fileName)

    override fun listFiles(folderPath: String, extension: String?): List<String> =
        try {
            names(folderPath, extension)
        } catch (e: Exception) {
            log.e("listFiles: exception for $folderPath: ${e.message}")
            emptyList()
        }

    override fun listFileMetadata(folderPath: String, extension: String?): List<FileMetadata> =
        try {
            names(folderPath, extension).mapNotNull { metadata(folderPath, it) }
        } catch (e: Exception) {
            log.e("listFileMetadata: exception for $folderPath: ${e.message}")
            emptyList()
        }

    override fun deleteFile(folderPath: String, fileName: String): Boolean =
        try {
            fileManager.removeItemAtPath(filePathOf(folderPath, fileName), null)
        } catch (e: Exception) {
            log.e("deleteFile: exception for $folderPath/$fileName: ${e.message}")
            false
        }

    override fun deleteAllFiles(folderPath: String, extension: String?): Boolean =
        try {
            names(folderPath, extension).forEach {
                fileManager.removeItemAtPath(filePathOf(folderPath, it), null)
            }
            true
        } catch (e: Exception) {
            log.e("deleteAllFiles: exception for $folderPath: ${e.message}")
            false
        }

    override fun renameFile(folderPath: String, oldName: String, newName: String): Boolean =
        try {
            // returns false (no exception) if the destination already exists — a caller that means
            // to overwrite has to delete it first, which is what checkRotateLog does
            fileManager.moveItemAtPath(
                filePathOf(folderPath, oldName),
                filePathOf(folderPath, newName),
                null,
            )
        } catch (e: Exception) {
            log.e("renameFile: exception for $folderPath/$oldName->$newName: ${e.message}")
            false
        }
}
