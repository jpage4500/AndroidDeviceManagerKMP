# Remaining work

Everything the Swing [AndroidDeviceManager](https://github.com/jpage4500/AndroidDeviceManager) does
that this port doesn't yet, found by reading its source rather than its README — the README lists
about a third of it.

Ordered by priority: **device list**, then **connect**, then **logs**. Stats are out of scope for
now. Anything deliberately not coming across is at the bottom with the reason.

Checked items are done in this repo. See [UI-PORT.md](docs/UI-PORT.md) for how the screens are built
and [ADB-TRANSPORTS.md](docs/ADB-TRANSPORTS.md) for what can reach a device.

---

## 1. Device list — the main screen

### Table
- [x] Columns: Name, Serial, Model, OS, Carrier, Phone, IMEI, Battery, Free, Custom 1/2, Booted
- [x] Sort by clicking a header
- [x] Resize columns by dragging
- [x] Reorder columns by dragging a header
- [x] Hide/show columns from the header's right-click menu
- [x] Alternating row colours, background watermark
- [x] Multi-select (click, ⌘-click, shift-click, rubber-band)
- [x] Keyboard navigation
- [x] **Filter box** in the toolbar (⌘F), matching any field
- [x] **Auto-select the first device** when the list first fills
- [x] Empty state, including a separate one while a filter is on
- [x] Tooltips on the *device* table — only for a value that doesn't fit, off with a setting
- [x] **Size to Fit** / **Size All to Fit** on the header menu (no Auto Resize toggle — columns are
      fixed-width here, so there is nothing to toggle)
- [ ] **Custom columns**: a user-defined list of app package names, each showing that app's installed
      version per device (`VER`), a `getprop` value (`PROP`), or a content-provider query (`QUERY`)
- [x] **Booted column** — device boot time and uptime, from `cat /proc/uptime`
- [x] Mark a device **busy** while a command is running (the Swing app tints its icon orange)
- [x] Show a device that hasn't finished booting as not-ready (`getprop sys.boot_completed`)

### Device details fetch
- [x] `getprop` for model / OS / SDK / carrier
- [x] Nickname, phone, IMEI, free space, battery level, charging source
- [x] Fetch in parallel across devices, batched into one round trip each
- [x] **Custom properties** pulled from `/sdcard/android_device_manager.properties` on the device
- [x] Uptime / boot time
- [x] Periodic **full** refresh (the Swing app re-reads everything every 60 minutes)

### Row actions (right-click)
- [x] Mirror, Screenshot
- [x] **Copy Field to Clipboard** / **Copy Line to Clipboard**
- [x] **Edit Device Name…** — writes `settings put global device_name`
- [x] **Edit Custom Field 1 / 2…**, **Edit Phone Number…** — written to the device's own properties file
- [x] **Device Details** window (⌘I) — a properties card plus storage and battery gauges
- [x] **Restart** the device
- [x] **Disconnect** / **Reconnect** / **Remove** a wireless device
- [x] **Install** wired up (the button exists and is disabled)
- [x] User-defined **adb commands** on the menu
- [ ] Double-click a Name / Custom / Phone cell to edit it in place

### Toolbar
- [x] The buttons, grouped and labelled
- [ ] **Customize Toolbar** — hide any button, from its own right-click menu or Settings
- [ ] **Custom scripts** — a folder of shell scripts, each one a toolbar button
- [ ] **Share** — toggle the built-in HTTP server on/off, with the button showing its state

### Status bar
- [x] Version, memory, device count
- [x] **Status events** — a running commentary of what the app is doing, with a progress bar
- [x] **History** — a dialog listing recent status events

### Window
- [x] Multiple windows, toolbar in the title bar
- [ ] **Minimize/exit to system tray**, with a tray menu listing every device (Mirror/Browse/Logs/Screenshot)
- [x] **Always on top**
- [x] **Drag and drop an APK** onto the list to install it on the selected devices
- [x] **Drag and drop any file** to copy it to the selected devices
- [ ] **Check for updates** on launch

---

## 2. Connect

- [x] **Connect dialog** — a table of remembered wireless devices with connect/disconnect state
- [x] Manual **IP + port** entry (defaults to `:5555`), remembering the last IP and preselecting its
      last octet so only that has to be retyped
- [x] **Recent wireless devices** list, persisted, with **Delete** on the right-click menu
- [ ] **Pair with QR code** — Android 11+ wireless debugging pairing over mDNS
- [x] **Connect to a remote server** — set its URL and token in Settings
- [ ] **Share my devices** — run that server here
- [x] A **transport picker**: local adb server, an adb server on the LAN, or a remote instance.
      `AppPref` already holds the four settings and nothing reads them yet

---

## 3. View logs

- [x] **Log viewer window** — `logcat` streamed over `exec:`
- [x] Columns: Date, App, TID, Level, Tag, Message
- [x] **Filter expressions** — `level:D*`, `app:com.test`, `tag:HD_*`, `tag:!HD_*`, `msg:"hello"`,
      `*:*hello*`, combined with `&&` (see the Swing app's `LOGS.md`)
- [x] **Saved filters** in a side list — add, edit, duplicate, delete, and combine several at once
- [x] **Quick add filter** from a cell's right-click menu
- [x] Start/stop logging, **clear**, and a **max lines** buffer
- [x] Hide any column from its header menu (the Swing app has one button for App/TID)
- [x] Copy line / copy message / **View Message** in a window of its own
- [ ] Font name, size and style
- [ ] **Logs-only mode** — the whole app as a log window, with a device list down the side
- [x] **Save logs to file** — what is on screen, filters applied (one device at a time)

---

## 4. File explorer

- [x] Browse a device's filesystem — Name, Size, Date
- [x] Download, delete, new folder, copy name, copy path
- [x] **Go To…** a path (no remembered list yet)
- [ ] **Root mode** toggle
- [ ] Drag files into the *explorer* to copy them there (the device list accepts them already)

---

## 5. Everything else

- [x] **Input** — type text or send a key event to selected devices
- [ ] Split APKs (`pm install-multiple`) — a single APK works
- [ ] **Terminal** — open a shell against a device
- [ ] **Device Info** window with storage and battery gauges
- [ ] **Battery history** — level, temperature and voltage over time
- [x] Settings: download/screenshot folders, refresh interval, log buffer, adb path, reset prefs
- [x] Custom adb command editor

---

## Deliberately not coming across

| Feature | Why |
|---|---|
| **scrcpy** mirroring and screen recording | Shells out to an external binary. A GUI-launched macOS app inherits neither `PATH` nor `ANDROID_HOME`, so this is unreliable by construction — and the socket route has no environment dependency at all. Tier II mirroring (`screencap` + `input`) is already in. See [ADB-TRANSPORTS.md](docs/ADB-TRANSPORTS.md) |
| **Bundled shell scripts** | Same reason |
| **Stats screen** and its charts | Out of scope for now, and it wants a charting library |
| **`se.vidstige.jadb`** | Replaced by `adb/`, not ported |
| **Serving** the HTTP API | This app is a client of it. Worth writing only if a KMP instance should host devices for another |
