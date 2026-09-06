package com.jpage4500.devicemanager.util

import com.jpage4500.devicemanager.data.*
import java.util.*

/**
 * a properties file under [DesktopUtils.DESKTOP_HOME] — the same place the Swing app keeps its own,
 * so the two are readable side by side.
 */
private class DesktopSettings : Settings {
    private val settingsFile = DesktopUtils.getHomeDir("settings.properties")
    private val properties = Properties()

    init {
        if (settingsFile.exists()) {
            runCatching { settingsFile.inputStream().use { properties.load(it) } }
                .onFailure { println("DesktopSettings: unable to read ${settingsFile.absolutePath}: ${it.message}") }
        }
    }

    override fun getString(key: String): String? = properties.getProperty(key)

    override fun putString(key: String, value: String) {
        properties.setProperty(key, value)
        save()
    }

    override fun remove(key: String) {
        properties.remove(key)
        save()
    }

    override fun clear() {
        properties.clear()
        save()
    }

    override fun getAll(): Map<String, String> =
        properties.entries.associate { (k, v) -> k.toString() to v.toString() }

    override fun setAll(map: Map<String, String>) {
        properties.putAll(map)
        save()
    }

    // the whole file is rewritten on every set, which is fine at this size: settings change when a
    // person changes one, not on a timer
    private fun save() {
        runCatching {
            settingsFile.outputStream().use { properties.store(it, "${Branding.appName} Settings") }
        }.onFailure { println("DesktopSettings: unable to write ${settingsFile.absolutePath}: ${it.message}") }
    }
}

actual fun createSettings(): Settings = DesktopSettings()
