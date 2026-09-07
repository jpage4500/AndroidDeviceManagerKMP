package com.jpage4500.devicemanager.util

/** a file on *this* machine, as a chooser hands it back. */
data class LocalFile(val path: String, val name: String, val size: Long)

/**
 * ask the platform for one or more files.
 *
 * @param extensions lowercase, no dot — `["apk"]`. Empty for anything.
 * @return what was chosen, or empty if the dialog was cancelled or the platform has none
 */
expect suspend fun pickLocalFiles(title: String, extensions: List<String> = emptyList()): List<LocalFile>

/** read a file off this machine, or null if it can't be read. */
expect suspend fun readLocalFile(path: String): ByteArray?

/** what a path points at, without reading it. */
expect fun localFileInfo(path: String): LocalFile?
