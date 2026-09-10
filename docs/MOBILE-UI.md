# The mobile UI

The phone app, on Android and iOS. [UI-PORT.md](UI-PORT.md) is the desktop's screens;
[ADB-TRANSPORTS.md](ADB-TRANSPORTS.md) is which route reaches which devices, and should be read
first — most of the decisions below are that document's constraints showing up in a layout.

The desktop and the phone diverge here more than anywhere else in the app. The desktop is a **data
table** someone watches 45 rows in; the phone is a **list** someone taps one row in. They share the
model, the transports and the actions — and almost none of the layout.

`MainActivity` and `MainViewController` both call `App(compact = true)`; that flag is the whole
switch.

---

## Screens, and what to call their parts

Every screen the phone app has, and the name of each piece of it, so a request can say `ActionTile`
or `MirrorBar` and mean exactly one thing. The names are the Compose functions — the ones drawn by
an anonymous `Text` or `Row` are named here by what they say instead, in quotes.

Everything below `##` in the rest of this document is *why* the screens are shaped this way; this is
*what they are*. Nothing here is a `DataTable`: the desktop's screens are in
[UI-PORT.md](UI-PORT.md).

Two levels and no navigation library. `CompactLayout` in `ui/App.kt` is the whole router: the list,
a full-screen mirror, and `DeviceManager.selectedId` putting the device's page over the top. Every
other screen is a `BaseDialog` on the dialog stack, which is what makes the back gesture close a
form rather than the app — the ones that are really screens pass `fullScreen = true`.

### device list

description: every device from every connection, and the two ways to add another  
screen: `DeviceListScreen` — `ui/screen/DeviceListScreen.kt`, inside `CompactLayout`

| element | what it is |
|---|---|
| `AppBar` | the band across the top: "Devices", "N attached" under it, gear at the end. lives in `App.kt` |
| `ServerGroup` | one computer, with the devices that came in through it inside it. the header opens and shuts it |
| `OptionRow` | that header: server icon (green connected, grey only remembered), name, `host:port  ·  N attached`, ⓘ button, chevron |
| `ServerNote` | what an open computer shows when it has no rows — "Not connected" with a Connect button, or "Nothing attached to this computer" |
| `DeviceRow` | one device on two lines: state icon, name and model, then `phone  ·  Android n  ·  free space` |
| `BatteryGauge` | the battery at the end of a `DeviceRow`, when the device reported one |
| `ConnectPanel` | the card at the foot of the list: one row per route, and nothing opens inside it |
| `EmptyState` | first run — nothing connected and nothing remembered: "Connect a device" over an expanded `ConnectPanel` |

### connect to a computer

description: attach a desk running Device Manager, by QR or by hand  
screen: `ServerForm` — `ui/screen/ConnectPanel.kt`, from the "Connect to a computer" row

| element | what it is |
|---|---|
| `DialogTitle` | "Connect to a computer" and its subtitle; the whole band is the way out. `RouteDialog` is the `BaseDialog` that draws it |
| `CenteredNote` | "In Device Manager on your computer: Share ▸ Start Server ▸ QR Code, then scan it here." |
| `CenteredButton` | "Scan a QR code" — the camera, through `rememberQrScanner`. a scan fills the three fields below and dials |
| `FormHeader` | "Or enter it by hand" |
| `LabelledField` | Host, Port, Auth token — three rows, always out |
| `CenteredButton` | "Connect" |
| `StatusText` | why the last attempt failed, under the button — a connection closes the form instead, and names the computer in a toast |

### connect to a device

description: pair or reach a device with no computer in the middle  
screen: `DeviceForm` — `ui/screen/ConnectPanel.kt`, opened by the "Connect to a device" row

| element | what it is |
|---|---|
| `FormHeader` | "Pair with a QR code" |
| `PairPanel` | the pairing half: the steps, the code, and what the session is doing |
| `QrImage` | the code the other device scans, 60% of the width |
| "Try again" | a `LinkButton` under the state line, on a failure worth retrying |
| `CenteredNote` | "Set the adb server host in Settings first…" in place of the code, when nothing attached can pair |
| `FormHeader` | "Found on this network" |
| `OptionRow` | one device mDNS found; tapping it fills the address fields rather than connecting |
| `CenteredNote` | "Nothing yet — still looking…" while the browse has found nothing |
| `FormHeader` | "Or enter its address" |
| `LabelledField` | Host, Port |
| `CenteredButton` | "Connect" |
| `CenteredNote` | the `adb tcpip 5555` explanation under the button |
| `StatusText` | what the last attempt said |

### computer

description: what can be done to a remembered computer  
screen: `ServerDialog` — `ui/screen/ServerGroup.kt`, from the ⓘ on a `ServerGroup` header

| element | what it is |
|---|---|
| `DialogMessage` | the `host:port` under the name |
| `SettingItem` | "Disconnect" — only while this computer is connected |
| `SettingItem` | "Forget" — red, and the token goes with it |

### device

description: what a device is, and what a finger can do to it  
screen: `DeviceSheet` — `ui/screen/DeviceSheet.kt`, put up by `CompactLayout` when a row is tapped

| element | what it is |
|---|---|
| `DialogTitle` | state icon, name, and `via  ·  Android n  ·  state`, from `deviceHeading` — the same band the mirror wears |
| `ActionGrid` | the four tiles, two by two, at the top |
| `ActionTile` | one tile: Mirror, Screenshot, and Browse / View Logs as dashed outlines because they aren't built |
| `DeviceStatus` | the two gauges straight under the tiles, with no header over them — the desktop's own, shared |
| `Gauge` | one bar: Internal Storage, then Battery |
| `CollapsibleSection` | "Commands" next, then "Details"; both fold and are remembered folded, in `InfoSection` |
| `SettingItem` | the Commands rows: "Restart", plus "Disconnect" for a wireless device |
| `DeviceDetails` | the "Details" section last — the desktop's own, shared |
| `Property` | one Details row: Serial, Name, Model, Phone, IMEI, Carrier, OS, Timezone, Booted, Custom 1, Custom 2. tapping copies it |
| pencil on Name | `Property`'s `onEdit`, on an online device: opens `InputDialog` to rename |

### screenshot

description: the PNG just taken, in place of the device's properties  
screen: `Screenshot` — `ui/screen/DeviceSheet.kt`, taking over the sheet's body

| element | what it is |
|---|---|
| `CircularProgressIndicator` | while `screencap` runs |
| the image | letterboxed into whatever the title bar left, never written to a file |
| "Done" | the sheet's own OK: it clears the shot and leaves the device's page up |

### mirror

description: the device's screen, live, with taps and keys going back to it  
screen: `MirrorScreen` — `ui/screen/MirrorScreen.kt`, from the Mirror tile

| element | what it is |
|---|---|
| `DialogTitle` | the same band the device's page wore, drawn by `CompactLayout`; the whole band closes the mirror |
| `MirrorImage` | the frame, with its border: tap, hold and swipe go to the device, pinch zooms, and a drag pans once it is zoomed |
| `MirrorFeedbackOverlay` | the rings, the swipe arrow and the key bubbles, drawn over the frame |
| `EmptyState` | "Can't mirror this device", with the reason under it |
| `MirrorBar` | the bar under the frame |
| `MirrorKey` | Back and Home, as icons |
| `MirrorKeyboard` | the keyboard toggle — filled while on; what you type lands on the device |
| `MirrorMore` | the ⋯ menu: Recents, Power, "Reset zoom", and Quality High / Mid / Low (only on a streamed mirror) |
| "N fps" | how many frames a second are arriving, at the right-hand end of the bar |

### settings

description: five categories, one dialog each  
screen: `SettingsDialog` — `ui/dialog/SettingsDialog.kt`, from the gear in the `AppBar`

| element | what it is |
|---|---|
| `SettingItem` | "Connection" — `ConnectionSettingsDialog` |
| `SettingItem` | "Device list" — `DeviceListSettingsDialog` |
| `SettingItem` | "Logs" — `LogSettingsDialog` |
| `SettingItem` | "App" — `AppSettingsDialog` |
| `SettingItem` | "About" — `AboutDialog` |

### settings ▸ connection

description: how this app reaches devices, and at what address  
screen: `ConnectionSettingsDialog` — `ui/dialog/SettingsDialog.kt`

| element | what it is |
|---|---|
| "Reading devices from" | `TransportSetting` → an `OptionsDialog` of Automatic / adb server / Device Manager |
| "adb server host" | `TextSetting` → `TextInputDialog` |
| "adb server port" | `TextSetting`, numbers only |
| "Remote Device Manager URL" | `TextSetting` |
| "Remote token" | `TextSetting`, shown obfuscated |

### settings ▸ device list

description: how often devices are re-read, and how the mirror behaves  
screen: `DeviceListSettingsDialog` — `ui/dialog/SettingsDialog.kt`

| element | what it is |
|---|---|
| "Refresh battery/disk (secs)" | `SliderSetting` → `SliderDialog` |
| "Full refresh (mins)" | `SliderSetting`; 0 reads "Never" |
| "Mirror interval (ms)" | `SliderSetting`. reads and writes the pref and nothing else does: a polled mirror runs at `MirrorSource.DEFAULT_INTERVAL_MS` |
| "Mirror windows at once" | `StepperSetting`. windows are a desktop thing; the row shows here too |
| "Show Tooltips" | `SwitchSetting`. also desktop-only in effect — a phone has no pointer to hover |

### settings ▸ logs

description: the log viewer's prefs, which the phone doesn't have a viewer for yet  
screen: `LogSettingsDialog` — `ui/dialog/SettingsDialog.kt`

| element | what it is |
|---|---|
| "Log buffer (lines)" | `TextSetting`, numbers only |
| "Hold while scrolled up (lines)" | `TextSetting`, numbers only |
| "Log font size" | `SliderSetting` |

### settings ▸ app

description: the app's own behaviour  
screen: `AppSettingsDialog` — `ui/dialog/SettingsDialog.kt`

| element | what it is |
|---|---|
| "Stay in the menu bar when closed" | `SwitchSetting`; desktop-only in effect |
| "Keep windows on top" | `SwitchSetting`; desktop-only in effect |
| "Confirm destructive commands" | `SwitchSetting` — what the device page's Restart reads before it asks |
| "Reset all settings" | RESET, behind a `ConfirmationDialog` |

### settings ▸ about

description: version, and everything for getting a log out of a phone  
screen: `AboutDialog` — `ui/dialog/AboutDialog.kt`

| element | what it is |
|---|---|
| "Support / Feedback" | `SupportDialog`: say what happened, and the app log goes with it |
| "Check for updates" | `CheckUpdatesDialog` |
| "Debug Mode" | `SettingSwitchItem` — raises what the log file keeps from Info to Debug |
| "View Logs" | `LogFileDialog` |
| version line | `Android Device Manager  ·  v1.0.n  ·  build date`, at the foot |

### over any of them

description: the pieces that aren't a screen of their own  
screen: —

| element | what it is |
|---|---|
| `ToastHost` | the toast, raised from anywhere: a copied property, a command that failed. `util/Toast.kt`, hosted in `ui/App.kt` |
| `ConfirmDialog` | are-you-sure — Restart, and Reset all settings |
| `InputDialog` | one field and an OK — renaming a device |
| `BaseDialog` | the frame all of the above sit in: title band, scrolling body, optional CANCEL/OK. `fullScreen = true` is the three that are really screens — the device's page and the two connect forms. the mirror isn't a dialog at all |
| `SettingItem` | the row shape used well outside settings: an icon, a label, a value or a chevron |

**Phone-only**, so a change here can't break the desktop: `CompactLayout`, `AppBar`,
`DeviceListScreen`, `DeviceRow`, `ServerGroup`, `ConnectPanel` and its two forms, `DeviceSheet` with
`ActionGrid` and `ActionTile`.

**Shared with the desktop**, so a change here shows up in a window as well: `MirrorScreen` and
everything in it, `DeviceStatus` and `DeviceDetails`, `SettingsDialog` and every dialog under it,
`BaseDialog`, `OptionRow`, `ExpandableBlock`, `EmptyState` and the `Setting*` rows.

---

## What the phone app is for

Small on purpose: a list of devices, and a way to work on one of them. The desktop exists to manage
45 devices at once; the phone exists to reach a device when you are not at the desk. So there is no
column configuration, no multi-select, no windows and no toolbar — a `DataTable` on a phone is a
table nobody can read.

What it does have, in the order it is worth building:

1. a list of devices, connected and not
2. a way to connect a new one, which is the screen a first-run user actually lands on
3. a sheet per device: what it is, and what can be done to it
4. mirror, then screenshot, then logs and files

## The one thing the phone needs that the desktop doesn't

**More than one connection at a time — built.** A desk has one adb server and that is the whole
story. A phone is the opposite case: two devices reached directly, plus a desk at the office, plus a
desk at home, and the list it shows is the **union** of all of them.

`DeviceManager` holds a **list** of `DeviceTransport`s. Each has its own tracker and its own detail
fetch, `devices` is every connection's rows in the order the connections were added, and adding or
dropping one leaves the others exactly where they are. The desktop is unaffected: a list of one
behaves as the single transport it had before. The Swing app already works this way —
`RemoteConnectionManager` holds a list of `RemoteServerConfig` and `Device.remoteConnection` says
which one a row came from.

Two things had to change first, and both are the load-bearing part of the design:

- **A connection is identified by where it goes**, as a URI: `adb://10.0.0.4:5037`,
  `server://imac.local:8765`, `test://0.0.0.0`. Derived rather than minted, so the same desk reached
  twice is one connection — which is also what stops a saved server and a remote URL in Settings
  showing every device twice. `DeviceTransport.id`.
- **A device is identified by its connection *and* its serial**, written `<connection id>/<serial>`
  — `Device.id`. Two desks can each have an `emulator-5554`, and `_devices`, the selection and
  `WindowManager.openOnce` were all keyed on the serial alone. Everything above `DeviceManager` now
  keys on the id; everything below it is handed the bare serial, which is all adb has ever known
  about. `Device.connectionOf` / `Device.serialOf` split one back apart.

The adb server's address moved with it: `AdbServer(host, port)` is passed into every call in `adb/`,
where `AdbConnection.host` used to be a global that `AdbServerTransport.aim()` set before each one.
`AdbFeatures` is keyed on the server as well as the serial for the same reason.

**Which servers are attached is remembered.** `SavedServer.connected` says so, and
`ServerManager.restore()` — called from `DeviceManager.start()` — re-attaches them one at a time at
the next launch. A desk that doesn't answer keeps its flag and is tried again: being off is not the
same as being forgotten.

**Logcat goes through the transport too** (`DeviceTransport.logcat`), because a stream that found its
own way to an adb server would happily read the other desk's `emulator-5554`. Both real routes
implement it — an adb server runs `logcat` over `exec:`, a Device Manager server pushes it over
`/ws/logs` — and `canStreamLogs` is what a log window checks before it opens on a device it can't
follow, which now means only the test devices.

What this leaves ready is the route that isn't written yet: a **direct `adbd` transport** is one more
`DeviceTransport` with an id of its own, added with `DeviceManager.addConnection`, and every screen
gets it alongside whatever else is attached.

## The device list

```
┌─────────────────────────────────┐
│ Devices                      ⚙  │
│ 2 attached                      │
├─────────────────────────────────┤
│ ┃ ▤ joe's imac           ⓘ  ▾   │
│ ┃   192.168.0.12:8765 · 2 att.  │
│ ┃                          87%  │
│ ┃  ● joe's pixel (Pixel 10a)[==]│
│ ┃    555-0101 · Android 16      │
│ ┃                          46%  │
│ ┃  ● kitchen 7 (Pixel 8 Pro)[= ]│
│ ┃    Android 15 · 3.6G          │
├─────────────────────────────────┤
│   ▤ home imac            ⓘ  ▸   │
│     192.168.0.31:8765 · not co… │
├─────────────────────────────────┤
│ ┌─────────────────────────────┐ │
│ │ ▤ Connect to a computer   ▸ │ │  ← the same panel the empty state shows
│ ├─────────────────────────────┤ │
│ │ ▯ Connect to a device     ▸ │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

**Grouped by the computer the devices came through.** Every remembered Device Manager server is a row
in the list, and each one being read holds its own devices inside it — `ui/screen/ServerGroup.kt`,
drawn with the same `ExpandableBlock` the connect panel's routes use, so the phone has one expanding
idiom rather than two. A connected computer opens itself and the rest stay shut; an explicit tap wins
from then on. Anything no computer owns — a desk's adb server, the test fleet — is a plain row below
the groups, and the connect panel is still the last item.

**A computer's ground is a shade off the list's.** `Colors.groupBackground` behind the whole group,
full width rather than inside the block's inset, so it reads as a band across the list and shows as a
frame around the device rows. The rows themselves keep painting the plain surface, which is what
makes the *devices* the flat thing and the group around them the lifted one — shade both and neither
stands out. It is one step off `listBg` and no more: `headerBg` is a desktop table's column bar and
would read as a selection here.

The shading is the group's in **both** states, which is a change worth knowing about. It used to be
`ExpandableBlock`'s own, faded in only while open — and invisible from the day it was written, since
that tint was `Colors.listBg` and `listBg` is also `MaterialTheme.colorScheme.surface`, the ground
the rows inside already paint. A block that stands out has to stand out shut as well: a remembered
computer nobody is connected to never opens at all. So the block no longer paints a ground, and the
caller's is the whole story.

**One rule, drawn in two places, and one place it is left out.** The app has a single hairline —
`Colors.divider`, which is also the theme's `outlineVariant`, so `HorizontalDivider(color =
Colors.divider)` and `HorizontalDivider(thickness = Dimens.listSpacing)` are the same pixels. It goes
above every **section** but the first — each computer, the ungrouped rows as one block, the connect
panel — and between the rows *inside* a section. It is left out in exactly one place: between an open
computer's header and the rows it just opened, which is the connect panel's rule below and the reason
`ExpandableBlock` exists. The section rule used to be drawn only between *shut* computers on the same
reasoning, but a rule above a header never lands between that header and its own contents: it lands
under the last device of whatever came before, and without it an open computer's rows ran straight
into the next computer's name.

A group rather than a column on the row, because a serial says nothing about which desk it arrived
from and a remembered computer that isn't connected has nowhere else to be. **Any number of groups
can be live**: a row is placed by the connection it arrived on, so two desks fill two groups, and
Disconnect on one leaves the other where it is.

**A computer's row only opens and shuts.** Disconnect and Forget sit behind the ⓘ button on it, in a
dialog: as links on the row they made it wider than a phone, and Forget one tap away from a row
someone is trying to expand is a mistake waiting to happen. An open computer that isn't connected
shows a Connect button in place of rows, and a connected one with nothing attached says so — an empty
block that just sits there tells nobody anything.

**A row is two lines, not ten columns**, in 68dp (`Dimens.touchRowHeight`).

- **The first line names the device**, with the model in dimmer type after it when the two differ.
  `Device.displayName` is already the nickname when there is one and the model when there isn't, so
  appending unconditionally would give "Pixel 10a (Pixel 10a)". The name is what someone is looking
  for; the model is what tells two of them apart.
- **The second line is the phone number, the Android version and the free space** — and *not* the
  serial. A wireless serial is `adb-RFCX20NB5TY-DBjlg4._adb-tls-connect._tcp`, which eats the whole
  line to say nothing anyone reads at a glance. It is one tap away in the sheet. If a device knows
  none of the three yet the line is left out rather than drawn empty.
- **The battery is stacked**: the percentage above the bar, with a charging bolt beside it. The
  desktop's one-line `BatteryLevel` stays as it is — a 28dp table row has no second line, and across
  forty-five rows the bar is what you glance at. On a phone, at arm's length, the digits are what you
  read. The whole block is a **fixed width** with the bolt's slot always reserved, so that "100%"
  doesn't push its bar further out than "99%" and a device that starts charging doesn't shift its own
  bar sideways. A column of bars that don't line up is worse than no bars.

  Whether a device *is* charging is read from `dumpsys battery`'s `status:` as well as its
  "AC powered" / "USB powered" lines. The plug lines say which plug is live and are the better
  answer when one of them matches, but they are OEM-worded and a dock may name none of them —
  `status: 2` (CHARGING) or `5` (FULL) is the fallback, and without it the bolt was missing on
  exactly the devices most likely to be on a charger permanently.

**Which connection a device came through** is the group it sits in rather than a fourth thing on the
row: two lines and a battery leave no room for it, and a desk's name is not what anyone scans a list
for. The sheet says it too, in its subtitle next to the serial, which is where a device reached some
other way answers the same question.

### The "not connected" half

This is the part the desktop has no equivalent of, because a desktop's adb server *is* the memory. On
a phone the list has to be persisted by the app, and three different kinds of thing land in it — two
of which the app has to remember for itself:

| Remembered | Where it lives today | Tapping it |
|---|---|---|
| a device reached directly (`host:port`) | `WirelessManager` → `AppPref.RECENT_WIRELESS` | re-dial that address |
| a Device Manager server | `ServerManager` → `AppPref.SAVED_SERVERS`, with `connected` | its group opens; Connect is inside it |
| a device belonging to a connected desk, currently offline | the desk's own list | nothing; the desk owns it |

The server half of that is built: **`AppPref.SAVED_SERVERS`** is a JSON list of `SavedServer`
mirroring the Swing app's `RemoteServerConfig`, `ServerManager` owns it, and it is what the groups
above are drawn from. What is left is the device half — `WirelessManager`'s cap of ten
(`WirelessDevice.MAX_RECENT`) is the Swing app's number for a dialog's history list and is too small
for a phone's whole list; raise it and let the user forget entries.

A remembered entry keeps the friendliest name it ever had and when it was last seen, which is what
makes the offline row readable — `WirelessDevice` already carries `name` and `lastSeenMs`.

**Long-press an offline row** to Forget it. **Tap it** to reconnect. Nothing else: an offline device
can't be mirrored or browsed, and offering the action is worse than not having it.

## Tapping a device: the detail sheet

Tapping a **connected** row opens a sheet over the list — not a navigation, and not straight into
the mirror. A phone user tapping a device usually wants to see what it is; going straight to a
250ms screencap poll is an expensive guess.

```
┌─────────────────────────────────┐
│ Pixel 10a                    ✕  │
│ 192.168.0.147 · joe's imac      │
├─────────────────────────────────┤
│ ┌─────────────┐┌──────────────┐ │
│ │      ▶      ││      📷      │ │
│ │   Mirror    ││  Screenshot  │ │
│ └─────────────┘└──────────────┘ │
│ ┌ ─ ─ ─ ─ ─ ─ ┐┌ ─ ─ ─ ─ ─ ─ ┐ │
│ │      📁     ││      ≡      │ │
│ │   Browse    ││    Logs     │ │
│ └ ─ ─ ─ ─ ─ ─ ┘└ ─ ─ ─ ─ ─ ─ ┘ │
│ STATUS                       ⌄  │
│ Storage     ▓▓▓▓▓▓░░░  62%      │
│ Battery     ▓▓▓▓▓▓▓▓░  87% ⚡   │
│ COMMANDS                     ⌄  │
│ ↻  Restart                      │
│ ✕  Disconnect                   │
│ DETAILS                      ⌄  │
│ Serial      192.168.0.147:5555 ⧉│
│ Name        Pixel 10a         ✎ │
│ Model       Pixel 10a          ⧉│
│ Android     16 (SDK 36)        ⧉│
└─────────────────────────────────┘
```

The title bar is the app's own `DialogTitle`: the whole band closes the sheet, the icon is the list
row's device icon tinted by the same state colour, and the second line is where the device came from
rather than its serial — a wireless serial eats the line to say nothing, and it is the first row of
Details anyway.

- **The body is `DeviceStatus` and `DeviceDetails`**, the gauges and the rows the desktop's Device
  Details window draws, reading the live device rather than a snapshot — so the free space and the
  battery move while the sheet is open. `DeviceInfoScreen` is the desktop's scrolling wrapper around
  the same two pieces; the sheet has a scroll of its own and calls them directly. **Sections rather
  than cards on grey**: the same `SectionHeader` and flat rows a settings screen uses, because two
  white cards floating on a band of `headerBg` was the one part of the phone app that looked like a
  different product.
- **Status comes first, and Details last.** The two gauges are what a device is asked for first and
  they are two rows tall; Details is eleven, and with it directly under the tiles it pushed both of
  the others off the bottom of the screen. Commands sits between them, which puts the phone's own
  section in the middle of the two the desktop shares.
- **The gauges have no header and don't fold.** Two bars are shorter than the header that would hide
  them, so the header only ever cost a tap to get back what it was covering.
- **The sections that do fold, stay folded.** Commands and Details are `CollapsibleSection`s —
  the settings header plus a chevron that turns, so the app has one way of saying "this opens" rather
  than two. Which ones are shut lives in `AppPref.INFO_COLLAPSED` rather than in the composable, and
  is stored as the set that is *shut*: the sheet is built fresh for each device, so a section that
  forgot would have to be closed again on every one, and a build that adds a section shows it. A
  stored name that no longer matches one — `STATUS`, from before the gauges lost their header — reads
  back as nothing rather than as an error.
- **A property row copies itself and says so.** A column of text that silently copies is
  indistinguishable from a column of text, so all four affordances are on the row: it ripples under
  the finger, a copy hint sits in the gutter at the end of it, a divider closes it, and the toast
  afterwards names what went to the clipboard. Rename is the one exception in that gutter — a pencil
  on the Name row, where a user looks for it.
- **The actions above it are the desktop's row menu**, filtered to what a phone can do and ordered by
  what a phone is for. Mirror and Screenshot are the two the user reaches for; Browse and Logs are
  **dashed outlines** until those screens exist — the shape is the real one, nothing moves when they
  arrive, and an outline reads as not-yet where grey text read as broken. The rest are `SettingItem`
  rows under **Commands**, away from the thumb — named for what it becomes rather than what it holds
  today, since the Swing app's row menu has a dozen more to come.
- **A destructive action still confirms** — `AppPref.CONFIRM_DESTRUCTIVE` is already the setting, and
  Restart on the wrong row is exactly the mistake a phone makes easier.
- **The sheet is a `BaseDialog`** like every other dialog in the app, which is where its title band
  and its place in the dialog stack come from. It hand-rolled both over a bare `DialogContainer`
  until 09/08, and read as a different product because of it — the confirmation it raises now
  replaces it and hands the sheet back, rather than opening over the top of it.
- **It fills the screen rather than floating in a card.** `BaseDialog(fullScreen = true)`: on the
  phone's overlay path the card becomes the whole screen inside the safe insets — square corners, no
  side gaps, no strip of the list showing above and below. This is a device's *page*, not a prompt
  over the list, and the mirror it opens is drawn exactly this way, so the title bar doesn't jump
  when you go from one to the other. The flag is the phone's only; a desktop dialog is an OS window
  the user sizes, and ignores it.

**Screenshot means something different on a phone.** The desktop writes a PNG to a folder. A phone
has no folder anyone will find, so the shot takes over the sheet's body full-screen — letterboxed
into what is left below the title bar the way the mirror is, with the dialog's own OK reading **Done**
and clearing it rather than closing the sheet. `BaseDialog`'s scroll is off in that state, since a
child that fits itself to the height needs a bounded one to fit into; at the full width a phone's
own aspect ratio is taller than the phone. What is still missing is **Share** — one new
`expect fun shareImage(bytes: ByteArray, name: String)` through the system share sheet, which needs
no permission on either platform where saving to Photos or the gallery does.

**Mirror, Files and Logs are full screens, not sheets**, pushed on top of the list with a back
button. They already have names: `WindowContent` is a sealed interface in `WindowManager.kt` naming
exactly these — Mirror, Logs, Files, Info, Connect, Settings, Message. Reuse it as the phone's route
type and one enum names both a desktop window and a phone screen, so the shared action that opens a
mirror doesn't need to know which it is.

**Two fingers move the view, and so does one once the frame is zoomed.** Pinch zooms up to 4×, a
two-finger drag pans, and neither is anything the device is told about — it is the picture being
scaled, so it works the same over every transport. Two fingers are free to spend on this because
`input` has no multi-touch to send in the first place: pinching *inside* the mirrored app was never
possible. Five things make it work without breaking the gestures that were already there:

- **The transform is a `graphicsLayer`, so the tap arithmetic is untouched.** Compose reports
  pointer positions in a node's own pre-transform space, so `toDevice` maps a tap the same way at 4×
  as at fit size. Growing the drawn size instead would re-measure and re-letterbox the frame on
  every step of a pinch.
- **A second finger takes the gesture and doesn't give it back.** Nothing has been sent to the
  device until the finger lifts, so a tap or a swipe that turns into a pinch costs nothing to call
  off. The exception is the long press, which fires *while the finger is down* — a pinch whose two
  fingers land more than 500 ms apart sends one hold first. Letting go of one finger doesn't hand
  the gesture back either, or the remains of a pinch would go to the device as a swipe.
- **The touch slop is measured against the screen, not the frame.** The frame's pixels are `scale`
  times smaller than they look, so without multiplying it back a swipe would start on a quarter of
  the movement at 4×.
- **A one-finger drag pans while the frame is zoomed in**, and only then: at 4× a swipe could
  cross a quarter of the screen at most, and the part of the frame it was reaching for is usually
  the part that has been panned off it. Taps and holds still reach the device at every zoom — the
  swipe is the one gesture spent on the view, and **Reset zoom** or a pinch back to fit hands it
  straight back.
- **The marks are drawn outside the transform.** `MirrorFeedbackOverlay` takes the point mapping
  rather than sitting inside the layer, so a tap ring stays the size of a fingertip instead of
  growing with the zoom, and the key bubble centres on what is being looked at rather than on the
  part of the frame that has been panned off screen.

The pan is bounded by how much frame there is to see — zero at fit size, so letting go of a zoom puts
the picture back in the middle by itself, and zero in the letterboxed axis until the zoom is enough
to fill it. **Reset zoom** is in the More menu, and reads the zoom inside the menu's own content so
a pinch doesn't recompose the screen sixty times a second. The desktop has no pinch: **ctrl or cmd
with the wheel** zooms about the pointer — both modifiers, since ctrl-scroll on macOS is the system's
own screen zoom — and a plain wheel pans, but only while there is something to pan, so at fit size
the event is left unconsumed and the wheel is still free to become the device swipe the Swing window
sends.

**A drag on the mirror is a swipe, not a tap.** `MirrorScreen` reads the whole gesture itself rather
than through `detectTapGestures`, which reports a drag as a tap wherever the finger lifted — on a
phone that turns every attempt to scroll the mirrored device into a click on whatever was under the
thumb. Past the touch slop it becomes `input swipe`, replayed over the time the finger actually took
so a flick reads as a flick, clamped to 50–2000 ms — at fit size, where nothing is zoomed; past the
slop on a zoomed frame the drag pans the view instead.

**A press held in place is a long press.** adb has no such command — `DeviceInput.longPress` is an
`input swipe` from a point to itself over 650ms, which is what the Swing window sends. It fires at
500ms **while the finger is still down**, not on release: the whole point of the feedback is saying
"you can let go now", and one that waits for the lift says nothing. Past the touch slop first, it is
a swipe instead.

**And every one of them is drawn where it landed, straight away.** A frame arrives every 250ms at
best and the device takes longer than that to react, so a tap with no local feedback is a second of
nothing — and the natural response to that is to tap again, which is what a run of identical taps in
the logs is. `MirrorFeedback` draws the Swing window's own four (`Animations.java`, timings included):
a mint ring for a tap, a bigger warmer one with its middle filled for a hold, an arrow for a swipe,
and a bubble in the centre naming the key for Back / Home / Recents / Power. All of it at roughly
twice the Swing app's sizes — those are desktop pixels over a small window, and this is read at arm's
length over a screenshot of anything — with every figure in `Dimens`. The frame loop runs only while
something is animating.

**The mirror wears the dialog title bar**, not the device list's. It is a thing opened over the app
the way a dialog is, so it gets `DialogTitle` — the accent band, the close X, the whole band tappable
— except it fills the screen instead of sitting in a card. The desktop's mirror window needs none of
this: its heading is the OS title bar, the same as a dialog's there (see [UI-PORT.md](UI-PORT.md)).

**And it wears the sheet's, not one of its own.** `DeviceHeading` in `DeviceSheet.kt` is the title,
the icon, the state colour and the subtitle for a device id, and the sheet and the mirror both ask it
— the mirror is opened from the sheet and covers it exactly, so a band that changed its icon, its
colour and its second line on the way through read as a different device. What the mirror said before
was the serial, which for a wireless device is `adb-RFCX20NB5TY-DBjIg4._adb-tls-connect._tcp`
ellipsized into something nobody can read back.

**The bar under the screen is four icons and a frame rate.** Back, Home, the keyboard and **More**,
as the icons every Android device draws them as — a row of blue words was the widest thing on the
screen and named each key twice, once on the button and again in the bubble the press raises. More is
`AppPopupMenu` composed inside its own button, so it opens upwards from the foot of the window, and
it holds **Recents** and **Power**: Recents is the least-pressed of the three navigation keys, and
Power is the one key here that turns off the screen being watched, which is not something to put one
target away from a thumb. Under those is **Quality** — High, Mid, Low, ticked where it stands,
disabled while the mirror is polling because nothing on that route can act on it (see
[ADB-TRANSPORTS.md](ADB-TRANSPORTS.md)). The frame rate sits at the right-hand end of the same bar,
counted over the last second by `MirrorSource` and small enough to ignore until it is the thing being
watched; a *fallen* rate is the reading worth having, which is why it is published on a clock rather
than worked out from the gap between the last two frames. The frame itself gets a hairline border,
since a screenshot of a white app on a light window has no edge of its own.

**The keyboard key raises the phone's own keyboard and types onto the device.** What it focuses is a
field one dp wide with no colour: a focus target has to be laid out to take focus, and there is
nothing worth showing — what is being typed is on the screen above, a frame or two later, with
`MirrorFeedback` putting each key up the moment it is pressed. Three things about it are the whole
design:

- **The field keeps what has been typed** instead of being emptied after each keystroke. An empty
  field reports a backspace as no change at all and the device never sees it; with the text still
  there, what the field *lost* is what to delete. The comparison is by common prefix, so an
  autocorrect that rewrites a word comes out as the deletes and the letters that turn one into the
  other, and a paste that carries newlines comes out as enter keys with the lines between them.
- **Keystrokes go down a channel with one consumer**, because order is the point: every send
  suspends on its way to the device, and two coroutines racing put "ab" on the screen as "ba" often
  enough to see. Letters wait 50 ms for the next one and go as a single `input text` — on the polled
  route that is one shell command instead of one per character, which is the same reason the Swing
  window batches on the same timer — and a key that isn't a letter goes after them, never ahead.
- **Enter is the IME's action key**, since `input text` can't carry a newline and a single-line field
  is what turns Return into something to send rather than something to insert. Delete and Enter are
  `DeviceInput.KEY_DEL` and `KEY_ENTER`, next to the keys the bar sends.

Typing straight through is also what made `DeviceInput.shell` escape its argument: `input text` takes
its string inside double quotes, and a quote, a backslash, a `$` or a backtick in what someone types
would otherwise end that argument and leave the rest of it as a command of its own.

**A phone's menu is not a desktop's menu at a phone's width.** `AppPopupMenu` is the desktop context
menu — 24dp rows, 13sp — and `Dimens.menuItemHeight` and `menuTextSize` now read `LocalCompactUi`, so
the same menu is 44dp and 17sp under a thumb. A 24dp row on a phone is a miss, not a choice.

## Connecting a device

The screen a first-run user sees, and the one that decides whether the app is any good.

**One `ConnectPanel` composable, two placements.** With nothing to show at all — no devices and no
remembered computer — it is the empty state, centred. Otherwise it is the last item of the list. The
same composable in both cases, so the two can never drift apart, and **both routes are on show in
both**: the card used to be a single "+ Add a device" row under a list, which put one tap in front of
everything on the screen whose entire job is the first connection.

**A route opens a screen; it does not unfold.** Either form is taller than the card it used to open
inside — a QR code, three fields, a list of what mDNS found, a paragraph on what to do on the other
device — and unfolded on a phone the button at the foot of the form sat below the bottom of the
screen, in a card that scrolled inside a list that also scrolled. So a tap on a route hands it the
whole screen, as the full-screen `BaseDialog` the device sheet and the mirror already are: the same
title band, the same close, and the same place in the dialog stack, so the back gesture closes the
form rather than the app. The row's title and subtitle become that dialog's heading — once the row is
behind the form, they are the only thing still saying which of the two ways in this is.

So the row's chevron points right and stays there (`OptionRow(open = false)`). A chevron that *turns*
is a promise to unfold in place, and the device list's computers are what still make it:
`ui/components/ExpandableBlock.kt` belongs to them now rather than to this panel. It is worth reading
for what an expanding row costs. A computer is one block — a shaded ground, a rounded inset, and open,
an accent stripe down the left of the row *and* of its contents, with no rule between the two, since
the rule only ever falls between blocks — because contents that simply appear underneath read as the
next item in the list. The stripe is painted with `drawBehind` rather than laid out as a sibling, as
it has to be as tall as both halves and nothing can know that height while the body is still
animating.
And the block's **inset does not change with its state**: insetting only when open moved the row's
icon and title down and to the right at the instant the body appeared, and that one-frame shift was
the whole of the jank — the expand animation itself was fine.

The third folding idiom is a section inside a form — `SectionHeader`'s `CollapsibleSection`, which
the device sheet's Commands and Details use. A list row that opens into its own contents and a
heading over a group of rows want different chrome; what they share, and what has to agree, is the
chevron that turns rather than swaps.

Remembered computers themselves are **not** on this panel — they are rows of the device list, next to
the devices they own. Two places listing the same server is one of them going stale.

**The panel's list item is keyed.** A keyless `item {}` in a `LazyColumn` is keyed by its index, so a
connection made from the open form adds a row above the panel, shifts its index and remounts it —
taking the form down mid-flow along with the "Connected to …" line it was showing. `item(key =
"connect")` is what leaves the form up until it is closed.

**A form's one real action is centred.** A button hard against the left edge of a panel reads as a
label rather than as the thing to press.

**The content is inset from the system, the background is not.** `MainActivity` calls
`enableEdgeToEdge()`, so the window genuinely extends behind the status and navigation bars — which
is what lets the watermark run to the edges, and what had the title sitting under the clock until
this was handled. `safeDrawingPadding()` goes on the *content* column rather than on the `Box` that
paints the background, so both are true at once.

**That is also what keeps the keyboard off the fields.** `safeDrawing` is the union of the system
bars, the display cutout and the IME, so the layout shrinks when the keyboard opens — and Compose
only scrolls a focused field into view within a viewport that knows it got shorter. Padding the field
instead leaves the scroll container full height and the field scrolls behind the keyboard where
nothing can reach it.

**On iOS the same rule means telling SwiftUI to keep out of it.** `ContentView` wraps the Compose
view controller in a `UIViewControllerRepresentable`, and SwiftUI insets one of those by the safe
area unless it is told not to. With `safeDrawingPadding()` already inside, that was the notch and
the home indicator applied twice — about 93pt of white above every title bar and 68pt below every
button row, and no watermark under either. `.ignoresSafeArea()` on the `ComposeView`, with no
region argument, hands the whole question to Compose: the keyboard is one of the regions `.all`
covers, and `safeDrawing` already has it.

```
┌─────────────────────────────────┐        ┌─────────────────────────────────┐
│  No devices yet                 │        │ [mac] Connect to a computer   ✕ │
│                                 │        │       Running Device Manager    │
│  ┌───────────────────────────┐  │        ├─────────────────────────────────┤
│  │ [mac]  Connect to a      ▸│  │        │  In Device Manager on your      │
│  │        computer running   │  │  tap   │  computer: Share ▸ Start Server │
│  │        Device Manager     │  │  ────▶ │  ▸ QR Code, then scan it here.  │
│  ├───────────────────────────┤  │        │      [ ▣  Scan a QR code ]      │
│  │ [dev]  Connect to a      ▸│  │        │                                 │
│  │        device, no         │  │        │ ── Or enter it by hand ──────── │
│  │        computer involved  │  │        │ Host  [ 192.168.0.12          ] │
│  └───────────────────────────┘  │        │ Port  [ 8765                  ] │
└─────────────────────────────────┘        │ Token [ ••••••••              ] │
                                           │           [ Connect ]           │
                                           └─────────────────────────────────┘
```

Ordered by how likely each is to work, not by how interesting it is.

**A QR code is not a route — it is how you reach the thing behind it.** Both QR flows started as
rows of their own, which read as unrelated things to try; each belongs inside the section it serves,
as that section's main action. So there are two rows, and each is ordered by how little work its ways
in take:

- **Connect to a computer** — the three clicks to make on the computer (Share ▸ Start Server ▸ QR
  Code), the *scan* button under them, and the address, port and token below that.
- **Connect to a device** — *pair with a QR code*, which this app **shows** and the other device
  scans; then what mDNS *found on this network*; then an address typed by hand.

The two QRs point in opposite directions and it is worth being clear which is which — one has our
camera reading someone else's screen, the other has our screen being read by someone else's camera.

**Connecting to a device ends by opening that device.** Both ways in — a typed or tapped address, and
pairing by QR — close the form and select the device, which is what puts its page up; the connection
was only ever a means to that. A *computer* does not do this, because it brings a fleet rather than
one device and there is no single page to open, so its form stays put and the list behind it fills
in. The device id is known from the transport rather than waited for off the device list, since the
point is to open it immediately.

**Each form leads with the part that needs nothing typed, and nothing is behind a link.** Both are
sections under `SectionHeader`s, ordered by how little work each is, and every section is on screen
at once: a full-screen form has the room, and a link that hides half of it is a link somebody has to
guess at. The computer's fields are out even though a scan fills them, since they are also where a
scan that read the wrong server gets corrected.

```
┌─────────────────────────────────┐
│ [dev] Connect to a device     ✕ │
│       Straight to another dev…  │
├─────────────────────────────────┤
│ ── Pair with a QR code ──────── │
│  On the other device: Settings  │
│  ▸ Developer options ▸ Wireless │
│  debugging ▸ Pair device with   │
│  QR code, then scan this.       │
│         ▛▀▚▘▗▚▀▜                │
│         ▐▖▟▄▙▄▖▌                │  ← up on its own; the adb server it
│         ▙▄▞▗▖▚▄▟                │    needs was started on the way in
│   Waiting for a device to scan… │
│                                 │
│ ── Found on this network ─────── │
│ ▯ Pixel 8                       │
│   192.168.0.171:5555            │
│                                 │
│ ── Or enter its address ─────── │
│ Host [ 192.168.0.12           ] │
│ Port [ 5555                   ] │
│           [ Connect ]           │
└─────────────────────────────────┘
```

**The two sections mean different things, and the device one is now two halves.** A computer is
reached over the Device Manager HTTP API. A *device* is reached **directly, with no computer
anywhere** — that is the whole point of the section — and `DirectDeviceTransport` does it: the
address field builds one, so nothing goes through a desk's adb server behind the user's back.

What still leans on a desk is **pairing**, and only pairing — or on the adb server this app can start
inside itself, which is what makes the QR the *first* thing in the device form rather than a button
at the foot of it: on an Android build carrying the binary there is no decision in it, so the server
is started on the way in and the code is simply up. Where neither is possible — iOS, or a build with
no binary — the section says so instead of drawing a code nobody could act on. The handshake needs a
TLS keying material exporter neither the JDK nor Apple exposes — see
[ADB-TRANSPORTS.md](ADB-TRANSPORTS.md). In practice that splits along a line worth knowing: a device
someone has run `adb tcpip 5555` on connects here with nothing else involved, and one that has only
ever had wireless debugging switched on needs a desk once, to pair.

### Route 3, first: connect to a Device Manager server

This is the route that works **today, against the Swing app, with no new protocol** — so it goes
first on the panel and first in the build order, whatever order the three get listed in.

`RemoteHttpTransport` already speaks it. Verified against the Swing app's `RemoteHttpServer`, the
endpoints are:

| | |
|---|---|
| `/api/info` | `{version, deviceCount, serverName}` — what Connect hits to validate an address and a token before anything is saved |
| `/api/devices` `/api/properties` `/api/setproperty` | the list and one device's properties |
| `/api/execute` `/api/install` | run a command, push an APK |
| `/api/files/list` `/api/files/download` `/api/files/upload` | the file explorer |
| `/api/screenshot` | one PNG |
| `/ws/screen` `/ws/logs` | pushed JPEG frames, and streamed logcat |

Auth is `Authorization: Bearer <token>` or a `token` query parameter, plus an `x-client-name` header
the server shows in its own UI. The default port is 8765 (`RemoteServerManager.DEFAULT_PORT`), and
the token is 16 random alphanumerics.

Four things to get right:

- **Hit `/api/info` on Connect, not `/api/devices`.** It is the cheapest call, it proves the token,
  and it hands back the server's own name — which is what the row in the list is labelled with
  rather than an IP. `ServerManager.connect` saves the entry only after it answers.
- **Accept a pasted connection string.** The Swing app's Share Server dialog copies
  `adm://<base64 of {"host","port","token","name"}>` (`RemoteConnectionUtils`), and typing a
  16-character token on a phone keyboard is the worst thing in this app. `SavedServer` reads and
  writes that format, and it is exactly what the QR carries: a scan fills the three fields from it,
  which is why the scan button heads that form and the fields under it are there for repair.
- **A phone on this route cannot add a device to that desk.** There is no connect and no pair
  endpoint in the API, and the brief is not to change it. So when the active connection is a Device
  Manager server, the "connect a device by IP" option is about *this phone*, not about the desk — and
  the panel should say so rather than silently doing something else.
- **`/api/execute` takes a JSON body**, `{"serial", "command"}`, and so does `/api/setproperty`. The
  server reads the post body and never the query string, so the same two values as query parameters
  come back as a 400 "Missing serial or command" — which was every tap on a mirrored screen. It
  answers `{isSuccess, resultList}` rather than the command's raw output, and `isSuccess` is whether
  it could *run* the command, not the command's exit status.

**Nothing on this route runs a shell command.** `/api/devices` already carries the battery, the free
space, the carrier, the phone number, the IMEI, the custom properties and the boot time — the server
did that work when it built the list. Asking again with `/api/execute` is two `adb shell` round trips
per device per refresh, run on someone else's machine, for answers that arrived with the list. So
`DeviceTransport.hasDeviceDetails` is true here, `DeviceManager.fetchDetails` returns immediately,
and the merge that protects locally-fetched values is skipped — left in, it would hold the first
battery reading forever, because it exists to defend fields the adb device list has no way of
knowing.

**And the list itself is not re-read on a fast timer.** `/api/info` is polled every five seconds,
which is an in-memory device count and nothing more; `/api/devices` is re-read only when that count
changes, or when `DETAILS_REFRESH_SECS` elapses. A device plugged in at the desk still appears within
seconds, without the phone asking the desk to survey its whole bench every three.

**Mirroring over this route goes down `/ws/screen`, not polled `/api/screenshot`** —
`RemoteScreenStream`, reached through `DeviceTransport.screenStream`. One connection carrying JPEG at
an interval the server keeps, with every tap a text frame back up it instead of a POST of its own:
on a phone the polled version was a round trip and a fresh full-size PNG every 250ms, and each
gesture queued behind one. `/api/screenshot` is still what the device sheet's single screenshot uses,
and still the fallback when a stream never delivers a frame. The protocol details, and why a scaled
frame changes where a tap lands, are in docs/ADB-TRANSPORTS.md.

### Route 3b: scan a QR code from the computer — **built**

The same `adm://` connection string, read by the camera instead of the keyboard. Device Manager's
Share Server dialog shows a QR of it (`ConnectionQrCodeDialog` in the Swing app); the phone points at
the screen and is connected. Nothing about the protocol changes — this is purely how a sixteen
character token gets across without anyone typing it, and it is the single largest usability win in
the app for what it costs.

`rememberQrScanner` returns the function that starts a scan, or **null** where a platform has no
scanner — the button is disabled rather than offered and doing nothing.

- **iOS decodes QR with no dependency at all** — an `AVCaptureSession` with an
  `AVCaptureMetadataOutput` asked for `AVMetadataObjectTypeQRCode`. What has to be hand-written is
  the view controller around it, because Compose has no camera preview. Needs
  `NSCameraUsageDescription`, which is now in `Info.plist`.
- **Android takes one dependency**, `play-services-code-scanner`. It is the smallest thing that
  works: the scan runs in a Play Services activity outside this process, so there is **no camera
  permission to request** and no preview, permission flow or lifecycle to write. The alternative was
  ML Kit's 2.5 MB bundled model plus a hand-written CameraX preview, for the same one screen.
- **The desktop has no scanner and shouldn't** — a desktop is the machine *showing* the QR, and a
  webcam pointed at your own screen is not a connection flow.

Pasting the connection string used to sit next to this and has been removed: scanning is strictly
less work, and two ways to do the same thing on the same panel is one of them going stale.

### Route 1: connect to a device

Pairing by QR is the main path here (below); typing an address is the fallback behind **Manual
entry**. An address and a port, defaulting to 5555, with Connect — and what that actually does
depends on what the phone is connected to:

| Situation | What "connect" means | Status |
|---|---|---|
| the phone is reading a desk's **adb server** | `host:connect:<host>:<port>` on that server — the desk dials the device, the phone sees it appear | **done** — `AdbServerTransport.connect` |
| the phone is on its own | speak the **device** protocol to `adbd` on 5555 directly: 24-byte framing, RSA auth | **done** — `DirectDeviceTransport`, one transport per device |
| the phone is reading a **Device Manager server** | nothing — no endpoint for it | not possible without an API change |

**Nobody has to type an IP address** — the panel browses mDNS and lists what answers, above the
field. The field stays, for a device on another subnet.

The service type browsed for **here** is `_adb._tcp`, not `_adb-tls-connect._tcp`, and that is
deliberate: `_adb._tcp` is the plain port `adb tcpip` opens and is the only adb service
`DirectDeviceTransport` can actually reach, while `_adb-tls-connect._tcp` is the wireless-debugging
port and wants a client certificate from pairing — so listing those in *this* form would offer
connections that cannot be made.

`_adb-tls-connect._tcp` is browsed in one other place, where it does work: `PairingSession` uses it
to dial a device it has just paired, through the adb server that holds the certificate. See
ADB-TRANSPORTS.md — pairing attaches nothing on its own, which is a trap worth knowing about.

The prefill rule from the desktop's `ConnectScreen` is worth keeping: open on the **last** address
connected to with its final octet selected, so the phone next to the last one is a few keystrokes.
With a numeric IME.

### Route 2: pair a device with a QR code — **built**

Inside the device section, as its main action. This app shows a QR; the target Android device scans
it from Settings ▸ Developer options ▸ Wireless debugging ▸ *Pair device with QR code*.

The QR content is the format the Swing app's `QrCodeDialog` already documents:

```
WIFI:T:ADB;S:ADB_WIFI_<20 random chars>;P:<20 random chars>;;
```

What happens after the scan is the part that decides the design. The target starts advertising
`_adb-tls-pairing._tcp.local.` on a **random** port, and whoever pairs with it must complete a
**SPAKE2 handshake and then a TLS 1.3 session with client certificates** using that password. There
is no pure-Kotlin SPAKE2 — ADB-TRANSPORTS.md calls it the one genuinely unsolved piece of this
project — so the phone does not do it. It does what the Swing app does instead: **find the device,
and hand it to an adb server**, which already holds the key material and speaks both halves.

So the flow is split across four pieces, and each is where it is for a reason:

| Piece | Where | Why there |
|---|---|---|
| the QR encoder | `util/QrCode.kt` | pure `commonMain`, ~350 lines, no dependency |
| drawing it | `ui/components/QrImage.kt` | a Compose `Canvas`, so no image encoder either |
| finding the device | `util/Mdns.kt` + three actuals | mDNS is the one part no socket library gives you |
| the handshake | `DeviceTransport.pair` → `host:pair` | only an adb server can do it at all |

**The encoder is hand-written**, for the reason the adb client is: everything this app needs a QR for
is short ASCII, and every library that does it is either Android-only or a megabyte of jar. Byte
mode, versions 1–10, all four ECC levels, with the mask chosen by the spec's four penalty rules. It
was checked by rendering 56 codes across every version and ECC level and decoding each of them back
with Apple's Vision framework; all 56 round-tripped, and anything longer than a version-10 code
returns null rather than guessing.

**mDNS is platform code on all three targets** and there is no way around it — ktor-network has no
mDNS, and multicast DNS by hand is a protocol rather than a helper. `NsdManager` on Android,
`NSNetServiceBrowser` on iOS, and **nothing on the desktop**: the JVM has no mDNS without JmDNS,
which is a dependency for one screen a desktop doesn't need, since a desktop running this app is
already running the adb server that `adb pair` talks to. Two details cost time and are worth keeping:
Android's `resolveService` handles one lookup at a time and fails rather than queues, so the actual
queues; and iOS hands back raw `sockaddr` bytes rather than a string, read octet by octet so no host
byte order is assumed.

**No code is drawn where it can't work, and the server is asked before anyone is asked.** Both halves
of that were learned by running it, and they are what lets the section come first without being a
trap:

- `canPair` was true for any `AdbServerTransport`, and a phone's default address is `127.0.0.1`.
  There is no adb server inside an Android or iOS app, so a QR was shown, a device scanned it, mDNS
  found it at `192.168.0.85:45615` — and only *then* did it say `no adb server on 127.0.0.1`.
  `canPair` now excludes loopback on anything that isn't a desktop, and the section says to set the
  adb server host in Settings instead of drawing a code.
- The remaining case is a host that *is* set but isn't answering — the desk asleep, or its server not
  started with `-a`. So `PairingSession` calls `DeviceTransport.probe()` (`host:version`, the
  cheapest thing the server answers) **before** it shows a QR at all, and shows the failure instead.

**A failure can be retried without starting over.** mDNS announces a service once and then goes
quiet: `onServiceFound` does not fire again for a device already known, so a pairing that failed for
any transient reason would otherwise need the target's dialog closed and reopened. The resolved
address is kept and a Try again link re-attempts with it.

**A code that has been used comes down.** A password pairs once, so the QR is drawn only while it is
still worth scanning: not while the server is still in question, not once it turned out not to be
there, and not after a device has paired with it. Pairing a *second* device means closing the form
and reopening it, which is also the only way to stop the browse — the session lives for exactly as
long as the form is composed, so there is nothing to back out of and no "stop waiting" link.

**Settings had to become reachable on the phone for any of this to work** — the adb server host lives
there, and there was no way in. See below.

**Still not possible: a phone with no desk at all.** That needs SPAKE2 in the app. The sidestep, if
it ever matters, is a device already on legacy `adb tcpip 5555` with plain RSA auth — route 1's
direct case, and what [scrcpy-mobile](https://github.com/wsvn53/scrcpy-mobile) ships with.

### Test devices

`0.0.0.0` in either Connect field loads `FakeTransport` — an invented bench of six devices covering
the states a row has to draw differently: USB and wireless, online, offline, unauthorised, and one
still booting.

A route rather than a debug menu, and deliberately so: a fake fleet is another way of reaching
devices, so it goes behind `DeviceTransport` like the real ones and every screen gets it without
knowing. It answers the **real** `DeviceDetails` batches with plausible `df`, `dumpsys battery`,
`getprop` and Parcel-dump output, so the parsers run for real and a row fills in the way it does on a
bench — a fake transport that answers with an error only exercises the failure paths. What it cannot
fake is a screen: `screencap` returns a PNG and inventing one means writing an encoder, so mirror and
screenshot fail here with a message that says why.

Intercepted in `DeviceManager.connect`, so the desktop's own Connect dialog reaches it too.

## Connections and the app lifecycle

A phone that connects directly to devices loses those connections the moment it is backgrounded, and
has to re-establish them on the way back. That much is true on both platforms. What is *not*
symmetrical is whether it can avoid losing them in the first place — and that asymmetry decides the
build order.

### iOS: reconnect, there is no alternative

A suspended iOS app's sockets are gone, and there is no ongoing-notification equivalent. Keeping a
socket alive in the background needs a real background mode — VoIP, audio, location — that Apple will
not approve for this app. `beginBackgroundTask` buys tens of seconds, not minutes — enough to finish
an in-flight command and close cleanly so the other end isn't left holding a half-written adb stream,
and that is all it is for.

So iOS is: tear down on background, re-dial on foreground. **Which means the reconnect path has to
exist anyway** — build it first, and treat the Android notification as an optimisation on top of it,
never as a way to avoid writing it.

### Android: an ongoing notification does work

A foreground service does let the process hold sockets indefinitely. Verified against the current
Android documentation, the right service type is **`connectedDevice`** — its stated use case is
interacting with an external device over Bluetooth, NFC, IR, USB **or a network connection**, which
is exactly this. It requires:

- `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest, and
- at least one of `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`
  or `TRANSMIT_IR` — and `CHANGE_WIFI_MULTICAST_STATE` is already wanted for mDNS, so it comes free
- `POST_NOTIFICATIONS` at runtime on Android 13+

`dataSync` is the other candidate and needs no runtime prerequisite, but its stated use case is
moving data to and from the cloud and recent releases have been adding timeouts to it. Prefer
`connectedDevice`.

The manifest holds only `INTERNET` and `ACCESS_NETWORK_STATE` today, so all of this is new.

### But it should be off by default

An adb connection held open while nobody is looking costs the target device battery, and on a shared
desk it holds a slot. The rule:

- **Default off.** A pref — `KEEP_CONNECTED` in `AppPref` — and a plain explanation of what it costs.
- **Only while something is actually running.** A live mirror or a running logcat capture is a reason
  to stay connected; a list nobody is looking at is not. The service starts when one of those starts
  and stops when the last one does, even with the pref on.
- **The notification says something true**: "2 devices connected · Pixel 10a", with a **Disconnect**
  action that actually disconnects rather than just hiding the notification.

### Closing on background, reopening on foreground — **built**

`manager/AppLifecycle.kt` is the signal everything hangs off: a `StateFlow<Boolean> isForeground` in
`commonMain`, fed by an `expect fun observeAppLifecycle()` that each `initApp` calls once.

- **Android** — `Application.ActivityLifecycleCallbacks`, counting started activities.
  `ProcessLifecycleOwner` is the canonical answer and needs `androidx.lifecycle:lifecycle-process`;
  that is a whole artifact for a callback registered in ten lines against a class the app already
  has. `onActivityStopped` is what fires when the screen goes off, which is the case this was
  written for.
- **iOS** — `UIApplicationDidEnterBackgroundNotification` and `WillEnterForeground`, *not* the
  resign-active pair: pulling down Control Centre resigns active, and dropping every connection for
  that only to dial the desk again a second later is worse than leaving them up.
- **desktop** — a no-op, so the flag is true for the life of the process. A minimised window, or no
  window at all under `MINIMIZE_TO_TRAY`, is still managing 45 devices; tying this to window focus
  would break the workload the app exists for.

Nothing reads the flag directly. `whileForeground { … }` wraps a body and is the only call site
pattern: it cancels the body on the way out and re-enters it on the way back, so a caller's `launch`
or `LaunchedEffect` owns the lifetime exactly as it did before. Three things are wrapped —
`DeviceManager.track`, the details refresh timer, and `MirrorScreen`'s capture loop.

| Transport | On background | On foreground |
|---|---|---|
| `AdbServerTransport` | the tracker is closed deliberately by cancelling the collection — `AdbDevices.track`'s own `finally` closes the socket. Left alone, `host:track-devices-l` dies without erroring and the tracker just goes quiet | re-entering the collection opens a new one, which is what `DeviceManager.refresh()` does for a server restarted underneath us |
| `RemoteHttpTransport` | the `/api/info` poll stops | `evictIdleConnections()` runs **before** the flag flips, so the first request of the new session can't be the one written to a half-open socket — that one looks healthy and stalls ~14s. See APP-SERVICES.md |
| direct to `adbd` | connection gone | `DirectDeviceTransport.open()` reconnects and re-authenticates on the next call, and the row it publishes goes to `OFFLINE` in the meantime. The key is stored, so the device does not ask again |

Two things that must stop, and now do:

- `MirrorSource` is a frame every 250ms, whether it polls for one or has one pushed. A backgrounded
  mirror is pure waste and, on a metered link, rude — and leaving the `/ws/screen` socket open would
  also keep the device's screen awake at the other end.
- The details refresh timer (`AppPref.DETAILS_REFRESH_SECS`) — two shell commands per device per
  minute for a battery percentage nobody is looking at.

The device rows are deliberately **not** cleared on the way out: a list nobody can see doesn't need
blanking, and keeping it means coming back to the table that was there rather than to a frame of
nothing. A device that then fails to come back shows up in the **not connected** section with its
last-seen time rather than vanishing — that is the section's whole purpose.

Still open: the log viewer's stream is not wrapped (nor the `ps` poll behind its App column, on the
route that still has one). Pausing a capture loses the lines that arrived while the app was away,
which is worse than the socket it saves, and the log viewer isn't reachable on a phone yet — so that
is a decision to make when it is.

## Settings

The gear in the title bar opens the desktop's own `SettingsScreen` in the phone's overlay container.
It had to be reachable before anything else could be tested, because **the adb server address lives
there**: a phone's `AdbServerTransport` defaults to `127.0.0.1:5037`, which on a phone is nothing at
all, so until that host is set to a desk the app can only read a Device Manager server or the test
devices, and pairing — which needs an adb server behind it — can never be offered. Connecting
**straight to a device** is the one route that needs nothing set there at all.

It is no longer a desktop form squeezed onto a phone. Settings is a **drill-down of dialogs**:
`SettingsDialog` is five rows — Connection, Device list, Logs, App, About — each opening a dialog of
its own, and a row in one of those opens the control that fits what it holds: a slider for a
duration, +/- for a small count, radio buttons for the transport, a field for an address. On a phone
each level replaces the one before it and comes back when the inner one closes, so there is only ever
one card on screen; on the desktop each is its own window. The rows come from `ui/setting/` (ported
from DashboardWeatherKMP) and read `LocalCompactUi` through `Dimens`, so the phone gets a 26dp icon
and a 20sp label against the desktop's 18dp and 14sp. See the Dialogs section of
[UI-PORT.md](UI-PORT.md) for the rest of what differs between the two.

**About is the phone's whole support story**, and the order of its rows is the order to work through
when something is wrong: turn Debug Mode on, reproduce it, then Support / Feedback — which asks what
happened and sends the app log with it. View Logs shows the tail of that same file on the device,
which is the only way to read it on a phone at all.

Still missing on the phone: which connections are saved (with Edit and Forget), and Keep connected.

## Build order

Each step leaves the app usable and compiles on all three targets.

1. ~~**The connect panel, and Device Manager servers.**~~ **Done.** `ui/screen/ConnectPanel.kt` in
   both placements — expanded as the empty state, collapsed as the list's last item —
   `data/SavedServer.kt` (the `adm://` connection string, read and written),
   `manager/ServerManager.kt` over a new `AppPref.SAVED_SERVERS` — drawn in the device list as
   `ui/screen/ServerGroup.kt`, a computer with its devices inside it — and
   `RemoteHttpTransport.info()` on `/api/info` as the thing Connect actually calls. The two QR rows
   are drawn disabled, so the panel is the real shape rather than a stub. `0.0.0.0` in either field
   loads `transport/FakeTransport.kt`.
2. ~~**The device sheet.**~~ **Done.** `ui/screen/DeviceSheet.kt`: `DeviceStatus` and
   `DeviceDetails` inside a `BaseDialog`, the 2×2 grid of action tiles above them, and Restart /
   Disconnect as `SettingItem` rows under Commands, between the two. Mirror and Screenshot are wired;
   Browse and Logs are dashed outlines. Rename is a pencil on the Name row and Copy serial is gone —
   every property row copies itself. Commands and Details each fold away, remembered in
   `AppPref.INFO_COLLAPSED`; the gauges have no header and don't. Restart confirms when
   `CONFIRM_DESTRUCTIVE` is on. A screenshot is shown and thrown away — the share sheet is still
   waiting on `shareImage`.
3. **The lifecycle seam.** `isForeground`, reconnect on return, stop polling on leave. iOS needs it
   and everything after it assumes it.
4. **The list's two sections**, and remembered offline devices.
5. ~~**QR scan** for a server's connection string~~ **Done.** `ui/components/QrScanner.kt` with a
   Play Services actual on Android and an AVFoundation one on iOS; the desktop has none. The Swing
   app already shows the QR.
6. **Many connections at once** — connection id on `Device`, address off the `AdbConnection` global,
   `DeviceManager` holding a list. The one change that must be measured against 45 devices on hubs
   before it lands.
7. ~~**`host:connect` and mDNS discovery**~~ **Done.** `AdbServerTransport.connect`, and
   `util/Mdns.kt` with actuals for Android and iOS.
8. ~~**Delegated QR pairing**~~ **Done.** `util/QrCode.kt`, `ui/components/QrImage.kt`,
   `manager/PairingSession.kt` and `host:pair` behind `DeviceTransport.pair` — compile-verified and
   encoder-verified, but the pairing round trip itself needs two real devices and an adb server.
9. **The Android foreground service.**
10. ~~**Direct to `adbd`**~~ **Done, including wireless debugging.** `adb/device/` is the device
    protocol — 24-byte framing, `CNXN`/`AUTH`/`OPEN`/`WRTE`, one held-open connection per device with
    streams multiplexed over it, and `A_STLS` upgrading that connection to TLS in place.
    `DirectDeviceTransport` sits on top, with `AdbSync` reused through `AdbByteStream` so the file
    explorer comes along. Two ways in: the plain port `adb tcpip 5555` opens, and the
    wireless-debugging port once the device has been paired with.

11. ~~**Standalone pairing**~~ **Done on Android.** `crypto/` and `adb/pairing/` do the whole
    handshake in this app — SPAKE2 over Ed25519, a self-signed certificate over the app's own adb
    key, and the TLS keying-material exporter reached through the platform's Conscrypt. No adb
    server, no computer, nothing bundled. `canPairDirectly` is false on desktop and iOS, which pair
    through a server instead; iOS can never do otherwise, because no Apple TLS API exports keying
    material.

    The constraint that shaped it: **pairing and connecting are the same key or neither works.**
    `adbd` matches on the public key inside the certificate, so whoever pairs is the only one who can
    then connect — which is why the `A_STLS` work above had to land in the same pass.

## Deliberately later

- **`DataTable` on a phone.** The file explorer and the log viewer reuse it on the desktop; on a
  phone both want a list with a path bar or a filter bar, not a table.
- **Multi-select and bulk actions.** The desktop's reason for existing; a phone has no fleet. Built
  once — long press for a picker, a FAB, Remove Device — and taken back out: it was a mode to get
  into and out of, over a list of three rows, for one action nobody was asking a phone for.
- **Pairing on iOS.** No Apple TLS API exports RFC 5705 keying material, so the SPAKE2 password can
  never be assembled there. iOS pairs through an adb server elsewhere, permanently.
- **Tier III live video.** ~70% of the total work, per ADB-TRANSPORTS.md, on the platform least able
  to spare it.
- **Serving the HTTP API from a phone.** A phone has nothing attached to serve.
