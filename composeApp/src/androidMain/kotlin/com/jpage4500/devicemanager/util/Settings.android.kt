package com.jpage4500.devicemanager.util

import android.content.*

/** SharedPreferences, all values written as strings — see [SettingsManager]. */
private class AndroidSettings(context: Context) : Settings {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("device_manager_prefs", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun getAll(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> v?.toString()?.let { k to it } }.toMap()

    override fun setAll(map: Map<String, String>) {
        val editor = prefs.edit()
        map.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }
}

actual fun createSettings(): Settings = AndroidSettings(AndroidContextProvider.context)
