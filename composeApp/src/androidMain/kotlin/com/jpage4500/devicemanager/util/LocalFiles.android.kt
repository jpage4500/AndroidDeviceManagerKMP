package com.jpage4500.devicemanager.util

// a phone has a document picker, but nothing on the phone build installs an APK onto another device
// yet — see REMAINING.md. an empty list reads as "cancelled", which every caller already handles
actual suspend fun pickLocalFiles(title: String, extensions: List<String>): List<LocalFile> = emptyList()

// the log viewer, the only thing that saves to a place the user picks, is the desktop's alone
actual suspend fun pickSaveFile(title: String, suggestedName: String, directory: String?): String? = null

actual fun openLocalFile(path: String): Boolean = false

actual fun editLocalFile(path: String): Boolean = false

actual fun revealLocalFile(path: String): Boolean = false

actual val localDesktopOS: DesktopOS? = null

actual suspend fun pickLocalFolder(title: String, directory: String?): String? = null

// a phone saves into the app's own storage and shares from there
actual val defaultDownloadFolder: String? = null
