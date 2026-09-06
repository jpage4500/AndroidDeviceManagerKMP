package com.jpage4500.devicemanager.util

/** which desktop this is, for the handful of places macOS needs different treatment. */
fun isMac(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Win", ignoreCase = true)
