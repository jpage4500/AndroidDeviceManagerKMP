package com.jpage4500.devicemanager.data

/**
 * the app's name and identity, in the one place every platform reads it from.
 *
 * keep [appName] in sync with `brandAppName` in composeApp/build.gradle.kts (the desktop installer
 * and window title) and androidApp/build.gradle.kts (the launcher label).
 */
object Branding {
    const val appName = "Android Device Manager"
    const val shortName = "Device Manager"

    /** directory under the user's home where the desktop keeps its settings and logs. */
    const val desktopHomeDir = ".android-device-manager"

    /**
     * the prefix on an uploaded log file's name.
     *
     * the upload bucket is shared with the other apps and every upload is an upsert, so an
     * un-prefixed name can overwrite someone else's report. never derived from [appName]: that's
     * user-facing copy, and changing it would silently split the history in two.
     */
    const val analyticsId = "adm"
}
