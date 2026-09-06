package com.jpage4500.devicemanager.util

/** mirrors the helper of the same name in the Swing app, so ported code reads the same. */
object TextUtils {
    fun isEmpty(text: String?): Boolean = text.isNullOrEmpty()

    fun notEmpty(text: String?): Boolean = !text.isNullOrEmpty()

    fun equalsIgnoreCase(a: String?, b: String?): Boolean = a.equals(b, ignoreCase = true)

    /** the first non-empty value, or null. */
    fun firstValid(vararg values: String?): String? = values.firstOrNull { notEmpty(it) }
}
