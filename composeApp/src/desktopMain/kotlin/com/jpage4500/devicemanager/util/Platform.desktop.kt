package com.jpage4500.devicemanager.util

/** which desktop this is, for the handful of places macOS needs different treatment. */
fun isMac(): Boolean = DesktopUtils.isDesktopOS(DesktopOS.MAC)

fun isWindows(): Boolean = DesktopUtils.isDesktopOS(DesktopOS.WINDOWS)
