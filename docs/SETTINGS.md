# Settings

`ui/dialog/SettingsDialog.kt`. On the desktop it is the Swing app's tabbed `SettingsDialog`; on a
phone the same tabs are a drill-down list. Which of the Swing app's settings came across, and with
which defaults, is the second half of this page.

## Shape

### Desktop: one window, tabs across the top

```
┌─ Settings ────────────────────────────────────────────────────────────────┐
│ ⚙ General │ ▤ Devices │ ≣ Logs │ 🗀 Files │ ▶ scrcpy │ ⇄ Connection │ ⓘ About │
│━━━━━━━━━━━                                                                 │
│  ◐ Theme                                               [ Light        ▾ ] │
│  ↻ Check for updates                                                [●]   │
│  WINDOW ───────────────────────────────────────────────────────────────── │
│  ▣ Stay in the menu bar when closed                                 [ ]   │
│  ★ Keep windows on top                                              [ ]   │
│  PREFERENCES ──────────────────────────────────────────────────────────── │
│  🗑 Reset all settings                                            RESET   │
│ ───────────────────────────────────────────────────────────────────────── │
│                                                                  [ Close ]│
└───────────────────────────────────────────────────────────────────────────┘
```

- **One `DialogWindow`** at a fixed 720×560 (`Dimens.settingsWindowWidth/Height`), resizable. A fixed
  size rather than packed to the form — `DialogContainer`'s `size` — because the tab body fills
  whatever height it's given and has no size of its own to pack to. The Swing one was 540×480 with
  five tabs; seven need the width.
- **The tab strip** is icon and label in equal shares, the selected one underlined, pinned while the
  tab's form scrolls vertically. Its grounds are the device tabs': `tabIdle` behind the strip, `listBg`
  behind the selected tab. Each tab keeps its own scroll position.
- **Each tab is a form** of the `ui/setting/` rows under `SectionHeader`s. A switch toggles in place;
  a choice of a few is an `AppDropdown` in place; a number opens `SliderDialog`, `StepperDialog` or
  `TextInputDialog` as a window beside Settings; a folder or a program opens the native chooser.
- **Every change saves as it's made**; there is no OK, only **Close** (and Escape).
- **The last tab shown** is remembered for the session, not persisted. `PageManager.openSettings(tab)`
  opens on a given tab instead.

### Phone: the drill-down

The tabs, as a list of categories, each opening its body in a `BaseDialog` — see
[MOBILE-UI.md](MOBILE-UI.md). `SettingsTab.desktopOnly` tabs (Files, scrcpy) aren't listed, and rows
that only mean something on a desktop (tray, on top, columns, update checks, Other computers) check
`localDesktopOS` and drop out. A choice is an `OptionsDialog` there rather than a dropdown.

**One body per tab, drawn by both.** `TabBody(tab)` is the only place a tab's rows are listed, so a
setting added to it appears on both platforms.

## Tabs

The *Swing* column is that app's pref and default, from
`../AndroidDeviceManager/.../ui/dialog/SettingsDialog.java` and `ScrcpyOptionsDialog.java`.

### General

| Row | Pref | Default | Swing |
|---|---|---|---|
| Theme | `THEME` | Light | — |
| Check for updates *(desktop)* | `CHECK_UPDATES` | true | `PREF_CHECK_UPDATES`, true |
| WINDOW › Stay in the menu bar when closed *(desktop)* | `MINIMIZE_TO_TRAY` | false | `PREF_EXIT_TO_TRAY`, false |
| WINDOW › Keep windows on top *(desktop)* — dialogs too, or they sink behind their owner | `ALWAYS_ON_TOP` | false | `PREF_ALWAYS_ON_TOP`, false |
| PREFERENCES › Reset all settings | — | — | Reset Preferences |

**Check for updates** is `UpdateManager.startBackgroundChecks()`, started from desktop `initApp`. It
ticks hourly and checks when `LAST_UPDATE_CHECK` is a day old, so a laptop that slept overnight still
checks in the morning. A newer release shows a toast with **Open**.

### Devices

| Row | Pref | Default | Swing |
|---|---|---|---|
| Full refresh (mins) | `FULL_REFRESH_MINS` | 60, 0–600, 0 = never | `PREF_REFRESH_TIME_MINS`, 60 (5–600) |
| Refresh battery/disk (secs) | `DETAILS_REFRESH_SECS` | 60 | — |
| TABLE › Columns *(desktop)* | `HIDDEN_COLUMNS` | `DeviceColumn.defaultHidden` | Manage Columns |
| TABLE › Reset table layout *(desktop)* | `COLUMN_ORDER`, `COLUMN_WIDTHS`, `HIDDEN_COLUMNS` | — | Manage Columns' reset |
| TABLE › Compact device rows | `DEVICES_COMPACT` | false | — |
| TABLE › Show Tooltips | `SHOW_TOOLTIPS` | true | — |
| BUILT-IN MIRROR › Mirror interval (ms) | `MIRROR_INTERVAL_MS` | 250 | 250, hard-coded |
| BUILT-IN MIRROR › Mirror windows at once | `MAX_MIRROR_WINDOWS` | 8 | — |

Columns is a `MultiSelectOptionsDialog` through `DeviceTableState.setHidden`, which won't hide every
column — the same rule as the header menu's `toggleHidden`.

### Logs

| Row | Pref | Default | Swing |
|---|---|---|---|
| Log buffer (lines) | `LOGS_MAX_LINES` | **200,000**, 10,000–9,999,999 | `PREF_LOGS_MAX_LINES`, 200000 (10000–9999999) |
| Log font size | `LOGS_FONT_SIZE` | 13 pt | the system font size |

The buffer is 200,000 to match the Swing app (it was 50,000). `LogsSource` clamps to the same range
(`MIN_BUFFER`/`MAX_BUFFER`) and reads it when a tab opens, so a change applies to tabs opened after it.
The hold while scrolled up is `LogsSource.HOLD_LINES` (25,000), no longer a setting; scrolling
sideways (`LOGS_SCROLL_SIDEWAYS`) is toggled from the log view's own toolbar, not here.
**Not measured yet:** the buffer is per tab and every tab keeps capturing, which the Swing app never
did — it showed one log at a time. Heap with several busy tabs at 200,000 is the number to take; if
it hurts, the fix is a smaller buffer for background tabs, not a smaller one for the tab being read.

### Files *(desktop)*

| Row | Pref | Default | Swing |
|---|---|---|---|
| Download folder | `DOWNLOAD_FOLDER` | `~/Downloads` | `PREF_DOWNLOAD_FOLDER`, ~/Downloads |
| Screenshot folder | `SCREENSHOT_FOLDER` | the download folder | `PREF_SCREENSHOT_FOLDER`, the download folder |
| Show hidden files | `FILES_SHOW_HIDDEN` | false | — |

A folder row opens `pickLocalFolder` (the native panel on macOS, Swing's chooser elsewhere, since
AWT's `FileDialog` can't pick a folder there); its × puts it back to the default. `SaveFolders` is
where both are read: `FileBrowser.download` and `DeviceManager.saveScreenshot` write through
`SaveFolders.save`, which never overwrites — a second `log.txt` lands as `log (2).txt` — because
`~/Downloads` holds files this app didn't write. A phone has no `defaultDownloadFolder` and keeps
writing into app storage.

### scrcpy *(desktop)*

| Row | Pref | Default | Swing |
|---|---|---|---|
| Mirror opens | `MIRROR_WITH` | Built-in mirror | separate buttons |
| scrcpy path | `SCRCPY_PATH` | unset → the scrcpy found on `PATH` | `PREF_SCRCPY_PATH`, found on PATH |
| Version | — | `scrcpy --version`, or Not found | — |
| OPTIONS › Show touches | `SCRCPY_SHOW_TOUCHES` | true | `--show-touches`, on |
| OPTIONS › Stay awake | `SCRCPY_STAY_AWAKE` | true | `--stay-awake`, on |
| OPTIONS › No audio | `SCRCPY_NO_AUDIO` | true | `--no-audio`, on |
| OPTIONS › Video codec | `SCRCPY_VIDEO_CODEC` | h264 | `--video-codec=`, h264 |
| OPTIONS › Extra flags | `SCRCPY_EXTRA_ARGS` | blank | custom flags |
| OPTIONS › Ask before mirroring | `SCRCPY_ASK` | true | `PREF_SCRCPY_DO_NOT_SHOW_AGAIN`, false — the same thing the right way round |

**One pref per option, not Swing's JSON list of flags.** The list form is why the Swing dialog had to
parse its own output back into checkboxes. `MirrorLauncher.flags()` assembles them in Swing's order,
and the command line is Swing's: `scrcpy -s <serial> --window-title <name> <flags…>`.

**Every Mirror action goes through `MirrorLauncher.mirror`** — the selection bar, a row's menu and
double-click, the details panel, ⌘M and the tray. With *Mirror opens* on scrcpy, a device read from
an adb server goes to scrcpy and any other device (a remote Device Manager server's) to the built-in
window, since scrcpy needs adb to the device. With Ask on, or no scrcpy found, `ScrcpyPromptDialog`
opens first: the same rows without *Mirror opens*, with **Mirror** enabled once a scrcpy is found.

`ScrcpyHost` is the `expect` (desktop actual in `desktopMain`, no-ops on the phones). A launch sets
`PATH` through `HostPath` — the same extended `PATH` the Scripts page gives a script, so scrcpy can
find `adb` from an app started in the Dock — and `ANDROID_ADB_SERVER_ADDRESS`/`_PORT` from that
device's own `AdbServerTransport.server`, so scrcpy reaches the server the device came from. A
scrcpy that exits within 1.5 s (a bad flag, a device that went away) toasts its last error line.

### Connection

| Row | Pref | Default | Swing |
|---|---|---|---|
| Reading devices from | `TRANSPORT` | Automatic | — |
| adb server host / port | `ADB_SERVER_HOST`, `ADB_SERVER_PORT` | 127.0.0.1, 5037 | — |
| Remote Device Manager URL / token | `REMOTE_URL`, `REMOTE_TOKEN` | blank | — |
| REMOTE › Other computers *(desktop)* | — opens Connect | — | Connect to Remote Servers |

Each field applies through `DeviceManager.applySettings()` when its dialog is confirmed — see
[ADB-TRANSPORTS.md](ADB-TRANSPORTS.md).

### About

`AboutBody`, shared with `AboutDialog`: Support / Feedback, Check for updates, Debug Mode (Swing's
Log Level, as a switch — Info or Debug), Demo Mode (local builds only — forty invented devices for
screenshots, see [MOBILE-UI.md](MOBILE-UI.md#demo-devices)), View Logs, and the version line.

## Not ported yet

Each waits on the feature it configures:

- **Custom columns** (`PREF_CUSTOM_APPS`, `LABEL:VER|PROP|QUERY:VALUE` lines) — `DeviceColumn` needs
  dynamic columns first.
- **Stats history** (`PREF_STATS_RETENTION_DAYS`, 3 days, 1–30) — waits on the Stats screen.
- **Use root** (`PREF_USE_ROOT`, false) — waits on the file explorer running `su -c`.
- **Share my devices** (`PREF_SERVER_ENABLED`, port 8765, device name) — waits on hosting.

## Left behind from the Swing app

- **Customize Toolbar**, `PREF_HIDDEN_TOOLBAR_ITEMS` — there is no toolbar; REDESIGN gave every
  action another home.
- **Show background image**, `PREF_SHOW_BACKGROUND` — the background image went with the redesign.
- **`PREF_ADB_PATH`** — Swing used it to start a server that wasn't running. This app never runs
  `adb start-server` (see `AdbStream`).
- **Log font name / style, `PREF_FONT_SIZE_OFFSET`** — one bundled mono font and one size pref.
- **`PREF_DEVICE_AUTO_RESIZE`, `PREF_LOGS_AUTO_RESIZE`** — `DataTable` keeps the widths you drag.
