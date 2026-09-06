package com.jpage4500.devicemanager.util

import androidx.compose.runtime.*
import com.jpage4500.devicemanager.data.*
import kotlinx.coroutines.flow.*

/**
 * every read and write of a persisted setting.
 *
 * one enum in front of three very different stores — SharedPreferences, a properties file and
 * NSUserDefaults — so a screen never learns which platform it is on. ported from
 * DashboardWeatherKMP.
 */
object SettingsManager {
    private val log = LoggerConfig.createLogger("SettingsManager")
    private val settings: Settings by lazy { createSettings() }

    // emits the pref that changed, for anything collecting rather than composing
    val prefChanges = MutableSharedFlow<AppPref>(extraBufferCapacity = 32)

    // one snapshot counter per pref, bumped whenever it changes — see [observePref]
    private val prefVersions: Map<AppPref, MutableState<Int>> =
        AppPref.entries.associateWith { mutableStateOf(0) }

    /**
     * subscribe the *calling composable* to [appPref], so changing the pref recomposes it.
     *
     * [getBoolAsState] and friends cover the usual shape, where a composable reads the pref itself.
     * this is for the other one: a plain function a composable calls to format a value, where there
     * is no state to hoist and no parameter to thread through every call site. reading the counter
     * inside a composition records it against the caller's recompose scope, however many plain calls
     * deep the read is; outside a composition the read does nothing.
     */
    fun observePref(appPref: AppPref): Int = prefVersions[appPref]?.value ?: 0

    fun getString(appPref: AppPref): String? =
        settings.getString(appPref.key) ?: (appPref.defaultValue as? String)

    fun getStringOrEmpty(appPref: AppPref): String = getString(appPref) ?: ""

    fun setString(appPref: AppPref, value: String?) {
        // only write and emit when it actually changed — every setter funnels through here, so a
        // slider dragged across 200 values would otherwise wake every collector 200 times
        val prevValue = getString(appPref)
        if (value == prevValue) return

        if (value == null) settings.remove(appPref.key) else settings.putString(appPref.key, value)
        prefVersions[appPref]?.let { it.value++ }
        prefChanges.tryEmit(appPref)
    }

    fun getBoolean(appPref: AppPref): Boolean =
        getString(appPref)?.toBoolean() ?: (appPref.defaultValue as? Boolean) ?: false

    fun setBoolean(appPref: AppPref, value: Boolean) = setString(appPref, value.toString())

    fun getInt(appPref: AppPref): Int =
        getString(appPref)?.toIntOrNull() ?: (appPref.defaultValue as? Int) ?: 0

    fun setInt(appPref: AppPref, value: Int) = setString(appPref, value.toString())

    fun getLong(appPref: AppPref): Long =
        getString(appPref)?.toLongOrNull() ?: (appPref.defaultValue as? Long) ?: 0

    fun setLong(appPref: AppPref, value: Long) = setString(appPref, value.toString())

    fun getFloat(appPref: AppPref): Float =
        getString(appPref)?.toFloatOrNull() ?: (appPref.defaultValue as? Float) ?: 0f

    fun setFloat(appPref: AppPref, value: Float) = setString(appPref, value.toString())

    /** the stored name as an enum constant, falling back to the default and then to the first. */
    inline fun <reified T : Enum<T>> getEnum(appPref: AppPref): T =
        getString(appPref)
            ?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
            ?: (appPref.defaultValue as? T)
            ?: enumValues<T>().first()

    fun <T : Enum<T>> setEnum(appPref: AppPref, value: T?) = setString(appPref, value?.name)

    /**
     * an ordered list of enum values, persisted as a comma-separated list of names — for prefs that
     * hold a display order rather than a choice. names that no longer exist are dropped and any
     * constant the stored value doesn't mention is appended, so a column order saved by an older
     * build still lists every column a newer one adds.
     */
    inline fun <reified T : Enum<T>> getEnumOrder(appPref: AppPref): List<T> {
        val stored = getString(appPref)
            ?.split(",")
            ?.mapNotNull { name -> runCatching { enumValueOf<T>(name.trim()) }.getOrNull() }
            ?.distinct()
            ?: emptyList()
        return stored + enumValues<T>().filter { it !in stored }
    }

    fun <T : Enum<T>> setEnumOrder(appPref: AppPref, values: List<T>) =
        setString(appPref, values.joinToString(",") { it.name })

    /**
     * an unordered set of enum values, persisted the way [getEnumOrder] persists a list — for prefs
     * that hold *which* constants are on rather than the order they're in. unlike [getEnumOrder]
     * nothing is added back, so a set saved by an older build doesn't silently gain whatever a
     * newer one declares.
     */
    inline fun <reified T : Enum<T>> getEnumSet(appPref: AppPref): Set<T> =
        getString(appPref)
            ?.split(",")
            ?.mapNotNull { name -> runCatching { enumValueOf<T>(name.trim()) }.getOrNull() }
            ?.toSet()
            ?: emptySet()

    fun <T : Enum<T>> setEnumSet(appPref: AppPref, values: Set<T>) {
        // an empty set clears the pref rather than storing "", which reads back as one bad name
        setString(appPref, values.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name })
    }

    fun clearAll() {
        log.d("clearAll: clearing all settings")
        settings.clear()
        bumpAll()
    }

    fun getAll(): Map<String, String> = settings.getAll()

    fun setAll(settingsMap: Map<String, String>) {
        log.d("setAll: ${settingsMap.size} settings")
        settings.setAll(settingsMap)
        bumpAll()
    }

    private fun bumpAll() {
        AppPref.entries.forEach {
            prefVersions[it]?.let { version -> version.value++ }
            prefChanges.tryEmit(it)
        }
    }

    @Composable
    fun <T> getPrefAsState(appPref: AppPref, initialValue: T, fetcher: (AppPref) -> T): State<T> =
        remember(appPref) {
            prefChanges
                .filter { it == appPref }
                .map { fetcher(appPref) }
                .distinctUntilChanged()
        }.collectAsState(initial = initialValue)

    @Composable
    fun getAsState(appPref: AppPref): State<String?> =
        getPrefAsState(appPref, getString(appPref)) { getString(it) }

    @Composable
    fun getBoolAsState(appPref: AppPref): State<Boolean> =
        getPrefAsState(appPref, getBoolean(appPref)) { getBoolean(it) }

    @Composable
    fun getIntAsState(appPref: AppPref): State<Int> =
        getPrefAsState(appPref, getInt(appPref)) { getInt(it) }

    @Composable
    inline fun <reified T : Enum<T>> getEnumAsState(appPref: AppPref): State<T> =
        getPrefAsState(appPref, getEnum<T>(appPref)) { getEnum<T>(it) }

    @Composable
    inline fun <reified T : Enum<T>> getEnumOrderAsState(appPref: AppPref): State<List<T>> =
        getPrefAsState(appPref, getEnumOrder<T>(appPref)) { getEnumOrder<T>(it) }

    @Composable
    inline fun <reified T : Enum<T>> getEnumSetAsState(appPref: AppPref): State<Set<T>> =
        getPrefAsState(appPref, getEnumSet<T>(appPref)) { getEnumSet<T>(it) }
}

/** key/value storage, as each platform already has one. */
interface Settings {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun getAll(): Map<String, String>
    fun setAll(map: Map<String, String>)
    fun clear()
}

expect fun createSettings(): Settings
