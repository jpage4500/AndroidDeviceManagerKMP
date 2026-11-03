package com.jpage4500.devicemanager.data

/**
 * the pages of Settings — tabs across the top on the desktop, a list of categories on a phone.
 *
 * one enum for both so the two layouts can't drift apart; see docs/SETTINGS.md. [desktopOnly] tabs
 * hold nothing a phone can use and don't appear there.
 */
enum class SettingsTab(val desktopOnly: Boolean = false) {
    GENERAL,
    DEVICES,
    LOGS,
    FILES(desktopOnly = true),
    SCRCPY(desktopOnly = true),
    CONNECTION,
    ABOUT,
}

/** what the Mirror action opens — see [AppPref.MIRROR_WITH]. */
enum class MirrorWith { BUILT_IN, SCRCPY }
