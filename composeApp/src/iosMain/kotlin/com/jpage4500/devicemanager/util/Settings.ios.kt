package com.jpage4500.devicemanager.util

import platform.Foundation.NSUserDefaults

/** NSUserDefaults, all values written as strings — see [SettingsManager]. */
private class IosSettings : Settings {
    private val userDefaults: NSUserDefaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = userDefaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        userDefaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        userDefaults.removeObjectForKey(key)
    }

    override fun clear() {
        userDefaults.dictionaryRepresentation().keys.forEach { key ->
            (key as? String)?.let { userDefaults.removeObjectForKey(it) }
        }
    }

    override fun getAll(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (entry in userDefaults.dictionaryRepresentation().entries) {
            val key = entry.key as? String ?: continue
            map[key] = entry.value?.toString() ?: ""
        }
        return map
    }

    override fun setAll(map: Map<String, String>) {
        map.forEach { (key, value) -> userDefaults.setObject(value, forKey = key) }
    }
}

actual fun createSettings(): Settings = IosSettings()
