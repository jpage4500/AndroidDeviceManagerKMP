package com.jpage4500.devicemanager.util

/** [AppLogWriter] over stdout; the AWT event thread reads as `UI`. */
class DesktopLogWriter : AppLogWriter() {
    override fun getThreadId(): String = jvmThreadId("AWT-EventQueue-0")
}
