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
    WINDOW_SIZES("window_sizes"),                           // JSON map of WindowKind name -> "<w>x<h>"
    ADB_SERVER_HOST("adb_server_host", "127.0.0.1"),        // which machine's adb server to read
    REMOTE_URL("remote_url"),                               // a Device Manager instance's HTTP API
    REMOTE_TOKEN("remote_token"),                           // its bearer token
    TRANSPORT("transport"),                                 // which DeviceTransport the app is on
    LAST_SERIAL("last_serial"),                             // the device selected when the app closed
    DEVICE_NICKNAMES("device_nicknames"),                   // JSON map of serial -> the name the user gave it
    CUSTOM_COMMANDS("custom_commands"),                     // JSON list of the user's saved shell commands
    RECENT_WIRELESS("recent_wireless"),                     // JSON list of WirelessDevice, most recent last
    SAVED_SERVERS("saved_servers"),                         // JSON list of SavedServer — the Device Manager instances this app knows
    RECENT_INPUT("recent_input"),                           // newline-separated strings typed onto devices
    LOGS_FILTERS("logs_filters"),                           // JSON list of LogFilter
    LOGS_HIDDEN_COLUMNS("logs_hidden_columns"),             // comma-separated LogColumn names
    LOGS_COLUMN_ORDER("logs_column_order"),                 // comma-separated LogColumn names, left to right
    LOGS_COLUMN_WIDTHS("logs_column_widths"),               // JSON map of LogColumn name -> px
    FILES_COLUMN_WIDTHS("files_column_widths"),             // JSON map of FileColumn name -> px
    FILES_RECENT_PATHS("files_recent_paths"),               // newline-separated paths from "Go To…"
    HIDDEN_COLUMNS("hidden_columns"),                       // comma-separated DeviceColumn names the table hides
    COLUMN_ORDER("column_order"),                           // comma-separated DeviceColumn names, left to right
    COLUMN_WIDTHS("column_widths"),                         // JSON map of DeviceColumn name -> px width the user dragged to
    DEVICE_SORT("device_sort"),                             // "<DeviceColumn name>,<asc|desc>"
    INSTALL_ID("install_id"),                               // random UUID identifying this install to log uploads

    // -------------------------------------------------------------
    // -- boolean settings --
    // -------------------------------------------------------------
    DEBUG_MODE("debug_mode"),                               // log debug lines to file, and log http bodies
    MINIMIZE_TO_TRAY("minimize_to_tray"),                   // desktop: closing the last window leaves a tray icon
    ALWAYS_ON_TOP("always_on_top"),                         // desktop: keep windows above other apps
    CONFIRM_DESTRUCTIVE("confirm_destructive", true),       // ask before a command that wipes or reboots
    SHOW_TOOLTIPS("show_tooltips", true),                   // tables: show a value that doesn't fit on hover
    MESSAGE_WRAP("message_wrap", false),                    // message viewer: line wrapping
    MESSAGE_AUTO_FORMAT("message_auto_format", true),       // message viewer: pretty-print JSON on open

    // -------------------------------------------------------------
    // -- int settings --
    // -------------------------------------------------------------
    ADB_SERVER_PORT("adb_server_port", 5037),               // adb's own port; only ever different behind a tunnel
    MIRROR_INTERVAL_MS("mirror_interval_ms", 250),          // screencap poll interval — the Swing app's number
    DETAILS_REFRESH_SECS("details_refresh_secs", 60),       // how often battery and free space are re-read
    FULL_REFRESH_MINS("full_refresh_mins", 60),             // ...and how often everything else is, 0 to never
    MAX_MIRROR_WINDOWS("max_mirror_windows", 8),            // how many mirrors can poll at once before the fleet suffers
    LOGS_MAX_LINES("logs_max_lines", 50000),                // ring buffer per log window
    LOGS_HOLD_LINES("logs_hold_lines", 25000),              // ...and how far past it the buffer may grow while a window reads history
    LOGS_FONT_SIZE("logs_font_size", 13),                   // log table text size, ⌘+/⌘- adjusts it
    LOGS_FILTER_WIDTH("logs_filter_width", 155),            // width of the log window's filter pane

    // -------------------------------------------------------------
    // -- long settings --
    // -------------------------------------------------------------
    LAST_UPDATE_CHECK("last_update_check"),                 // ms of last background app-update check
}
