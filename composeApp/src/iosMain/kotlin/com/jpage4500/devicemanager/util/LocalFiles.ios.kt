package com.jpage4500.devicemanager.util

import kotlinx.cinterop.*
import platform.Foundation.*

// as on android: nothing on the phone build pushes a local file to a device yet
actual suspend fun pickLocalFiles(title: String, extensions: List<String>): List<LocalFile> = emptyList()

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readLocalFile(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val size = data.length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
actual fun localFileInfo(path: String): LocalFile? {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(path)) return null
    val size = (manager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
    return LocalFile(path, path.substringAfterLast('/'), size)
}

// the log viewer, the only thing that saves to a place the user picks, is the desktop's alone
actual suspend fun pickSaveFile(title: String, suggestedName: String, directory: String?): String? = null

@OptIn(ExperimentalForeignApi::class)
actual suspend fun writeLocalFile(path: String, bytes: ByteArray): Boolean {
    // addressOf(0) on an empty array throws, and an empty file needs no bytes pinned
    val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { pinned ->
        NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert())
    }
    return data.writeToFile(path, atomically = true)
}

actual fun openLocalFile(path: String): Boolean = false

actual fun revealLocalFile(path: String): Boolean = false

actual val localDesktopOS: DesktopOS? = null

actual suspend fun pickLocalFolder(title: String, directory: String?): String? = null

// a phone saves into the app's own storage and shares from there
actual val defaultDownloadFolder: String? = null
