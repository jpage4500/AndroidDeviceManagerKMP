package com.jpage4500.devicemanager.util

// a phone has a document picker, but nothing on the phone build installs an APK onto another device
// yet — see REMAINING.md. an empty list reads as "cancelled", which every caller already handles
actual suspend fun pickLocalFiles(title: String, extensions: List<String>): List<LocalFile> = emptyList()
