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

/**
 * ask where to save a file, starting in [directory] (null for the platform's own choice).
 *
 * @return the chosen path, or null if the dialog was cancelled or the platform has none — a phone,
 *   where nothing saves a file to a place the user picks yet
 */
expect suspend fun pickSaveFile(title: String, suggestedName: String, directory: String? = null): String?

/** write [bytes] to [path], replacing what's there. false if it couldn't be written. */
expect suspend fun writeLocalFile(path: String, bytes: ByteArray): Boolean

/** open a file on this machine in whatever the OS opens it with. false where there is no such thing. */
expect fun openLocalFile(path: String): Boolean

/** show a file on this machine selected in Finder or Explorer. false where there is no such thing. */
expect fun revealLocalFile(path: String): Boolean

/** which desktop this is, so a button can say Finder or Explorer; null on a phone. */
expect val localDesktopOS: DesktopOS?

/**
 * ask for a folder, starting in [directory] (null for the platform's own choice).
 *
 * @return its path, or null if the dialog was cancelled or the platform has none
 */
expect suspend fun pickLocalFolder(title: String, directory: String? = null): String?

/** `~/Downloads` on a desktop — where the Swing app saved everything; null on a phone. */
expect val defaultDownloadFolder: String?
