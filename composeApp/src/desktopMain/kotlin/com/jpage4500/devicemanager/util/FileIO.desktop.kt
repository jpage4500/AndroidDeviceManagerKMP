package com.jpage4500.devicemanager.util

/** everything under `~/.android-device-manager` — see [DesktopUtils.DESKTOP_HOME]. */
actual fun createFileIO(): FileIO = JvmFileIO(DesktopUtils.getHomeDir())
