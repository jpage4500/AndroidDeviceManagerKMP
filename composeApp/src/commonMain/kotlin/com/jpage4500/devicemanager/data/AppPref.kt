package com.jpage4500.devicemanager.data

/**
 * every persisted setting, in one enum.
 *
 * read and written through [com.jpage4500.devicemanager.util.SettingsManager], which decides a
 * pref's type from what [defaultValue] declares — a Boolean default makes a boolean pref, an Int
 * default an int pref, and no default at all a nullable string.
 *
 * everything is stored as a string underneath, so a pref can change type without stranding what a
 * previous build wrote. ported from DashboardWeatherKMP.
 */
enum class AppPref(val key: String, val defaultValue: Any? = null) {
    // -------------------------------------------------------------
    // -- string settings --
    // -------------------------------------------------------------
    WINDOW_RECT("window_rect"),                             // desktop window size and position
    ADB_SERVER_HOST("adb_server_host", "127.0.0.1"),        // which machine's adb server to read
    REMOTE_URL("remote_url"),                               // a Device Manager instance's HTTP API
    REMOTE_TOKEN("remote_token"),                           // its bearer token
    TRANSPORT("transport"),                                 // which DeviceTransport the app is on
    LAST_SERIAL("last_serial"),                             // the device selected when the app closed
    DEVICE_NICKNAMES("device_nicknames"),                   // JSON map of serial -> the name the user gave it
    CUSTOM_COMMANDS("custom_commands"),                     // JSON list of the user's saved shell commands
    HIDDEN_COLUMNS("hidden_columns"),                       // comma-separated DeviceColumn names the table hides
    COLUMN_ORDER("column_order"),                           // comma-separated DeviceColumn names, left to right
    COLUMN_WIDTHS("column_widths"),                         // JSON map of DeviceColumn name -> px width the user dragged to
    DEVICE_SORT("device_sort"),                             // "<DeviceColumn name>,<asc|desc>"
    INSTALL_ID("install_id"),                               // random UUID identifying this install to log uploads

    // -------------------------------------------------------------
    // -- boolean settings --
    // -------------------------------------------------------------
    DEBUG_MODE("debug_mode"),                               // log debug lines to file, and log http bodies
    MINIMIZE_TO_TRAY("minimize_to_tray"),                   // desktop: close button hides to tray
    CONFIRM_DESTRUCTIVE("confirm_destructive", true),       // ask before a command that wipes or reboots

    // -------------------------------------------------------------
    // -- int settings --
    // -------------------------------------------------------------
    ADB_SERVER_PORT("adb_server_port", 5037),               // adb's own port; only ever different behind a tunnel
    MIRROR_INTERVAL_MS("mirror_interval_ms", 250),          // screencap poll interval — the Swing app's number
    MAX_MIRROR_WINDOWS("max_mirror_windows", 8),            // how many mirrors can poll at once before the fleet suffers

    // -------------------------------------------------------------
    // -- long settings --
    // -------------------------------------------------------------
    LAST_UPDATE_CHECK("last_update_check"),                 // ms of last background app-update check
}
