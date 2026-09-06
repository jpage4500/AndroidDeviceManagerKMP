package com.jpage4500.devicemanager.util

/** app-internal storage: private to the app and wiped on uninstall, which is where logs belong. */
actual fun createFileIO(): FileIO = JvmFileIO(AndroidContextProvider.context.filesDir)
