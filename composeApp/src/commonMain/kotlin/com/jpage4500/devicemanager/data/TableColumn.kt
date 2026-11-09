package com.jpage4500.devicemanager.data

import kotlinx.serialization.*
import kotlin.random.*

/**
 * one column of the device table: a built-in [DeviceColumn], or a [CustomColumn] someone added.
 *
 * the table, its saved layout and its sort are all keyed by [id], so a custom column can be moved,
 * resized, hidden and sorted exactly like a built-in one. A built-in's id is its enum name, which is
 * what the layout prefs held before custom columns existed — so a saved layout still loads.
 */
sealed interface TableColumn {
    val id: String
    val label: String
    val defaultWidth: Int

    /** the text a cell shows. */
    fun text(device: Device): String?

    /** what a sort compares — see [DeviceColumn.sortKey]. */
    fun sortKey(device: Device): Comparable<*>?
}

/** what a [CustomColumn] reads off each device. */
@Serializable
enum class CustomColumnType {
    /** an app's `versionName`, from `dumpsys package`. [CustomColumn.key] is the package name. */
    VERSION,

    /** a system property. [CustomColumn.key] is the `getprop` key. */
    PROPERTY,
}

/**
 * a column someone added to the device table — Settings › Columns, or a header's right-click.
 *
 * the Swing app's `LABEL:TYPE:VALUE` lines, less QUERY. Values arrive with the rest of a device's
 * details (see `DeviceManager.fetchFixed`) and land in [Device.customValues] under [valueKey].
 */
@Serializable
data class CustomColumn(
    override val id: String,
    override val label: String,
    val type: CustomColumnType,
    val key: String,
) : TableColumn {
    override val defaultWidth: Int get() = DEFAULT_WIDTH

    /**
     * where this column's value is kept on a [Device].
     *
     * the type and key rather than [id], so renaming a column doesn't have to read anything again,
     * and two columns asking the same thing share one answer.
     */
    val valueKey: String get() = valueKey(type, key)

    override fun text(device: Device): String? = device.customValues[valueKey]

    // a version or a build number sorted as text puts 11.10 before 11.9
    override fun sortKey(device: Device): Comparable<*>? = text(device)?.let { NaturalKey(it) }

    companion object {
        private const val DEFAULT_WIDTH = 100
        private const val ID_PREFIX = "custom:"

        fun valueKey(type: CustomColumnType, key: String): String = "${type.name}:$key"

        /** a fresh id — the prefix keeps it clear of every [DeviceColumn] name. */
        fun newId(): String = ID_PREFIX + Random.nextLong().toULong().toString(36)

        // what a package name or a property key can hold. both go into a shell line, quoted — this
        // is what keeps a typo from becoming something the shell takes as a command
        private val PACKAGE = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
        private val PROPERTY = Regex("""[A-Za-z0-9_.\-]+""")

        fun isValidKey(type: CustomColumnType, key: String): Boolean = when (type) {
            CustomColumnType.VERSION -> PACKAGE.matches(key)
            CustomColumnType.PROPERTY -> PROPERTY.matches(key)
        }

        // the tail of a package that names a build rather than the app
        private val GENERIC = setOf("android", "app", "apps", "mobile", "web", "client", "debug", "release", "beta", "free", "pro", "lite")

        /**
         * a header for [key] until someone types their own: the last part of it that says
         * something. `org.telegram.messenger.web` → Messenger, `ro.build.fingerprint` → Fingerprint.
         */
        fun suggestLabel(type: CustomColumnType, key: String): String {
            val parts = key.split('.').filter { it.isNotBlank() }
            val pick = when (type) {
                CustomColumnType.VERSION -> parts.lastOrNull { it.lowercase() !in GENERIC } ?: parts.lastOrNull()
                CustomColumnType.PROPERTY -> parts.lastOrNull()
            } ?: return ""
            return pick.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/**
 * text that sorts the way a person reads it: runs of digits as numbers, the rest ignoring case.
 *
 * `11.9.2` < `11.10.0`, and `build 9` < `build 10`.
 */
class NaturalKey(val text: String) : Comparable<NaturalKey> {
    private val parts: List<String> = RUN.findAll(text.lowercase()).map { it.value }.toList()

    override fun compareTo(other: NaturalKey): Int {
        for (i in 0 until minOf(parts.size, other.parts.size)) {
            val a = parts[i]
            val b = other.parts[i]
            val result = if (a[0].isDigit() && b[0].isDigit()) compareDigits(a, b) else a.compareTo(b)
            if (result != 0) return result
        }
        return parts.size.compareTo(other.parts.size)
    }

    override fun equals(other: Any?): Boolean = other is NaturalKey && text == other.text

    override fun hashCode(): Int = text.hashCode()

    private companion object {
        val RUN = Regex("""\d+|\D+""")

        // as numbers, without parsing — a build number can be longer than a Long
        fun compareDigits(a: String, b: String): Int {
            val x = a.trimStart('0')
            val y = b.trimStart('0')
            return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
        }
    }
}
