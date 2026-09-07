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
