package com.jpage4500.devicemanager.util

import kotlinx.coroutines.*
import java.io.File

actual suspend fun readLocalFile(path: String): ByteArray? = withContext(ioDispatcher) {
    runCatching { File(path).readBytes() }.getOrNull()
}

actual fun localFileInfo(path: String): LocalFile? {
    val file = File(path)
    if (!file.isFile) return null
    return LocalFile(file.absolutePath, file.name, file.length())
}
