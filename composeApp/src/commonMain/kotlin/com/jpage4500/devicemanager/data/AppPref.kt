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
    RECENT_INPUT("recent_input"),                           // JSON list of RecentInput, newest first (was newline-separated strings)
    RECENT_COMMANDS("recent_commands"),                     // JSON list of shell commands run from Commands, newest first
    LOGS_FILTERS("logs_filters"),                           // JSON list of LogFilter
    LOGS_SELECTED_FILTERS("logs_selected_filters"),         // JSON list of saved filter names last switched on — a new log tab starts with them
    LOGS_LEVEL_FLOOR("logs_level_floor", "VERBOSE"),        // LogLevelFloor name last picked — a new log tab starts on it
    LOGS_HIDDEN_COLUMNS("logs_hidden_columns"),             // comma-separated LogColumn names
    LOGS_COLUMN_ORDER("logs_column_order"),                 // comma-separated LogColumn names, left to right
    LOGS_COLUMN_WIDTHS("logs_column_widths"),               // JSON map of LogColumn name -> px
    LOGS_SAVE_DIR("logs_save_dir"),                         // folder the last saved log went to
    FILES_COLUMN_WIDTHS("files_column_widths"),             // JSON map of FileColumn name -> px
    FILES_RECENT_PATHS("files_recent_paths"),               // newline-separated paths from "Go To…"
    FILES_BOOKMARKS("files_bookmarks"),                     // newline-separated bookmarked folders, any device
    HIDDEN_COLUMNS("hidden_columns"),                       // comma-separated column ids the table hides — a DeviceColumn name or a CustomColumn id
    COLUMN_ORDER("column_order"),                           // comma-separated column ids, left to right
    COLUMN_WIDTHS("column_widths"),                         // JSON map of column id -> px width the user dragged to
    DEVICE_SORT("device_sort"),                             // "<column id>,<asc|desc>"
    CUSTOM_COLUMNS("custom_columns"),                       // JSON list of CustomColumn the device table adds
    INSTALL_ID("install_id"),                               // random UUID identifying this install to log uploads
    INFO_COLLAPSED("info_collapsed"),                       // comma-separated InfoSection names the device details keeps shut
    THEME("theme", "LIGHT"),                                // ThemeMode name: light, dark, or follow the OS
    DOWNLOAD_FOLDER("download_folder"),                     // desktop: where pulled files go; null = ~/Downloads
    SCREENSHOT_FOLDER("screenshot_folder"),                 // desktop: where screenshots go; null = DOWNLOAD_FOLDER
    MIRROR_WITH("mirror_with", "SCRCPY"),                   // MirrorWith name: what the Mirror action opens
    SCRCPY_PATH("scrcpy_path"),                             // null = the scrcpy found on PATH
    SCRCPY_VIDEO_CODEC("scrcpy_video_codec", "h264"),       // --video-codec=
    SCRCPY_EXTRA_ARGS("scrcpy_extra_args"),                 // whitespace-separated flags after the ones below
    DEVICES_VIEW("devices_view", "LIST"),                   // DeviceView name: the Devices page as a table or as icons
    SHARE_NAME("share_name"),                               // desktop: what Share Devices calls this computer; null = its own name
    SHARE_TOKEN("share_token"),                             // desktop: Share Devices' auth token, kept so a phone that scanned it stays paired

    // -------------------------------------------------------------
    // -- boolean settings --
    // -------------------------------------------------------------
    DEBUG_MODE("debug_mode"),                               // log debug lines to file, and log http bodies
    DEMO_MODE("demo_mode"),                                 // local builds only: show DemoTransport's invented devices
    MINIMIZE_TO_TRAY("minimize_to_tray"),                   // desktop: closing the last window leaves a tray icon
    ALWAYS_ON_TOP("always_on_top"),                         // desktop: keep windows above other apps
    FILES_SHOW_HIDDEN("files_show_hidden", false),          // Files: show names starting with a dot
    LOGS_SCROLL_SIDEWAYS("logs_scroll_sideways", true),     // log table: scroll sideways, or end the message column at the edge
    DEVICES_COMPACT("devices_compact", false),              // device table: one line a row instead of name over model
    MESSAGE_WRAP("message_wrap", false),                    // message viewer: line wrapping
    MESSAGE_AUTO_FORMAT("message_auto_format", true),       // message viewer: pretty-print JSON on open
    CHECK_UPDATES("check_updates", true),                   // desktop: look for a new release once a day
    SCRCPY_SHOW_TOUCHES("scrcpy_show_touches", true),       // --show-touches — the Swing app's defaults, all three on
    SCRCPY_STAY_AWAKE("scrcpy_stay_awake", true),           // --stay-awake
    SCRCPY_NO_AUDIO("scrcpy_no_audio", true),               // --no-audio
    SCRCPY_ASK("scrcpy_ask", true),                         // show the scrcpy options before each launch
    SHARE_ENABLED("share_enabled", false),                  // desktop: Share Devices was left running — start it again at launch

    // -------------------------------------------------------------
    // -- int settings --
    // -------------------------------------------------------------
    ADB_SERVER_PORT("adb_server_port", 5037),               // adb's own port; only ever different behind a tunnel
    MIRROR_INTERVAL_MS("mirror_interval_ms", 250),          // screencap poll interval — the Swing app's number
    DETAILS_REFRESH_SECS("details_refresh_secs", 60),       // how often battery and free space are re-read
    FULL_REFRESH_MINS("full_refresh_mins", 60),             // ...and how often everything else is, 0 to never
    MAX_MIRROR_WINDOWS("max_mirror_windows", 8),            // how many mirrors can poll at once before the fleet suffers
    LOGS_MAX_LINES("logs_max_lines", 200_000),              // ring buffer per log tab — the Swing app's default
    LOGS_FONT_SIZE("logs_font_size", 13),                   // log table text size, ⌘+/⌘- adjusts it
    SIDEBAR_WIDTH("sidebar_width", 216),                    // desktop sidebar, in dp; under 150 it is the icon rail
    DEVICES_ICON_SIZE("devices_icon_size", 0),              // Devices icon view: glyph height in dp from the status bar slider; 0 = Fit, sized to show every device
    SHARE_PORT("share_port", 8765),                         // desktop: Share Devices' port — the Swing app's default

    // -------------------------------------------------------------
    // -- long settings --
    // -------------------------------------------------------------
    LAST_UPDATE_CHECK("last_update_check"),                 // ms of last background app-update check
}
