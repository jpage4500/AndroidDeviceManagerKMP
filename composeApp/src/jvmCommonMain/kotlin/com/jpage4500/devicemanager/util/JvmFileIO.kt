package com.jpage4500.devicemanager.util

import java.io.File

/**
 * [FileIO] on java.io, shared by desktop and android.
 *
 * the two differ only in where the root is — internal storage on android,
 * `~/.android-device-manager` on desktop — so each supplies a [baseDir] and nothing else is written
 * twice.
 */
class JvmFileIO(private val baseDir: File) : FileIO {
    private val log = LoggerConfig.createLogger("JvmFileIO")

    init {
        baseDir.mkdirs()
        log.d("init: base directory: ${baseDir.absolutePath}")
    }

    private fun folder(folderPath: String) = File(baseDir, folderPath)

    private fun file(folderPath: String, fileName: String) = File(folder(folderPath), fileName)

    private fun listing(folderPath: String, extension: String?): Array<File> {
        val folder = folder(folderPath)
        if (!folder.exists()) return emptyArray()
        return folder.listFiles { f -> f.isFile && (extension == null || f.extension == extension) }
            ?: emptyArray()
    }

    override fun writeFile(folderPath: String, fileName: String, content: String, append: Boolean): Boolean {
        return try {
            folder(folderPath).mkdirs()
            val file = file(folderPath, fileName)
            if (append) file.appendText(content) else file.writeText(content)
            true
        } catch (e: Exception) {
            // printed rather than logged: log.e would re-enter StorageManager.writeFile and try to
            // persist this error to the file that just failed
            println("JvmFileIO: writeFile: exception for $folderPath/$fileName: ${e.message}")
            false
        }
    }

    override fun writeBytes(folderPath: String, fileName: String, bytes: ByteArray): Boolean {
        return try {
            folder(folderPath).mkdirs()
            file(folderPath, fileName).writeBytes(bytes)
            true
        } catch (e: Exception) {
            log.e("writeBytes: exception for $folderPath/$fileName: ${e.message}")
            false
        }
    }

    override fun readFile(folderPath: String, fileName: String): String? {
        return try {
            val file = file(folderPath, fileName)
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            log.e("readFile: exception for $folderPath/$fileName: ${e.message}")
            null
        }
    }

    override fun getAbsolutePath(folderPath: String, fileName: String): String? {
        val file = file(folderPath, fileName)
        return if (file.exists()) file.absolutePath else null
    }

    override fun getFileDetails(folderPath: String, fileName: String): FileMetadata? {
        val file = file(folderPath, fileName)
        if (!file.exists()) return null
        return FileMetadata(file.name, file.lastModified(), file.length())
    }

    override fun listFiles(folderPath: String, extension: String?): List<String> =
        listing(folderPath, extension).map { it.name }

    override fun listFileMetadata(folderPath: String, extension: String?): List<FileMetadata> =
        listing(folderPath, extension).map { FileMetadata(it.name, it.lastModified(), it.length()) }

    override fun deleteFile(folderPath: String, fileName: String): Boolean {
        return try {
            file(folderPath, fileName).delete()
        } catch (e: Exception) {
            log.e("deleteFile: exception for $folderPath/$fileName: ${e.message}")
            false
        }
    }

    override fun deleteAllFiles(folderPath: String, extension: String?): Boolean {
        return try {
            listing(folderPath, extension).forEach { it.delete() }
            true
        } catch (e: Exception) {
            log.e("deleteAllFiles: exception for $folderPath: ${e.message}")
            false
        }
    }

    override fun renameFile(folderPath: String, oldName: String, newName: String): Boolean {
        return try {
            file(folderPath, oldName).renameTo(file(folderPath, newName))
        } catch (e: Exception) {
            log.e("renameFile: exception for $folderPath/$oldName->$newName: ${e.message}")
            false
        }
    }
}
