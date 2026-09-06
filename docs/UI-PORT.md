# Porting the UI

How the Swing app's screens become Compose ones, starting with the device list — the entry point on
every platform. [PORT-PLAN.md](PORT-PLAN.md) covers what has already come across;
[ADB-TRANSPORTS.md](ADB-TRANSPORTS.md) covers how devices are reached.

The two platforms diverge here more than anywhere else in the app. The desktop is a **data table**
someone watches 45 rows in; the phone is a **list** someone taps one row in. They share the model,
the transport and the actions — and almost none of the layout.

---

## First: is the transport assumption right?

> "the desktop version can get the devices from a running adb server where the mobile version will
> have to connect directly to each device"

The desktop half is exactly right, and for the reason that decides the whole project: a USB device is
claimed exclusively by whichever adb server is running, so on desktop we talk to that server or we
don't see USB at all.

The mobile half is **half right**, and the half that's wrong matters. "Connect directly to each
device" is *one* of three routes, and it is the only one that **cannot** reach the USB fleet:

| Route from a phone | Reaches USB devices | Needs a desktop | Auth | Status |
|---|---|---|---|---|
| adb server on a LAN address (`AdbServerTransport`, host set) | **yes** | yes | **none at all** | written |
| Device Manager's HTTP API on `:8765` (`RemoteHttpTransport`) | **yes** | yes | bearer token | written |
| direct to `adbd` on `:5555` | **no** | no | RSA, or pairing | not written |

So:

- A phone pointed at a desk **already works today** through code that exists — it drives all 45 USB
  devices, and needs no new protocol, no crypto and no pairing.
- Direct-to-`adbd` is the only route that needs no desktop, and it only ever reaches devices that
  have **wireless debugging switched on**. A phone plugged into a hub is invisible to it.
- Direct-to-`adbd` is also the most expensive route left: the 24-byte message framing is easy and
  pure `commonMain`, but Android 11+ pairing needs SPAKE2 + TLS 1.3 client certs and no pure-Kotlin
  implementation exists. The sidestep is legacy `adb tcpip 5555` with plain RSA auth — which is what
  scrcpy-mobile ships, so the approach and the App Store outcome both have precedent.

**Recommendation:** the mobile app's "connect" flow should offer all three, in this order — a saved
desk (HTTP API), an adb server address, then a direct device. Building only the third means the
phone can't see the bench the desktop app exists to manage. Building the first two means the phone
is useful before any new protocol is written.

That is a change to the *plan*, not to the code: `DeviceTransport` already makes it a picker rather
than a branch.

---

## The desktop device screen

What's on screen today, from `ui/DeviceScreen.java` (1235 lines) and `table/DeviceTableModel.java`:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ [+ Connect] [Browse] [View Logs] [Input] │ [Mirror] [Screenshot]         │  toolbar
│ [Install] │ [Stats] [ADB] [Scripts]        …  [Refresh][Share][Logs][⚙]  │  (icon + label)
├──────────────────────────────────────────────────────────────────────────┤
│ Name ▲    │ Serial      │ Model   │ Battery │ OS │ Custom 1 │ Free │ PM  │  sortable header
├──────────────────────────────────────────────────────────────────────────┤
│ 🤖 bathroom 2   adb-2025…  T26-EEA   [▓▓▓]⚡  13   HD+        42.8G      │  alternating rows
│ 🤖 front door…  192.168…   Fire HD10 [▓▓▓]   7.1  HD+        21.7G      │
├──────────────────────────────────────────────────────────────────────────┤
│ ⟳ v1.0.0  🖫 17.2M              History                        total: 7  │  status bar
└──────────────────────────────────────────────────────────────────────────┘
```

Three regions, each its own composable:

**Toolbar** — icon-above-label buttons, grouped by separators, with a right-aligned trailing group.
Which buttons show is user-configurable (`ToolbarButton.hideByDefault()`, a right-click "Manage
Toolbar"). Most act on the *selected* device, so they're disabled with none selected.

**Table** — the hard part, see below.

**Status bar** — refresh state and version on the left, memory used, a "History" affordance in the
centre, device count on the right.

### The table is the piece with no shortcut

Compose has no data table. `LazyColumn` gives scrolling and nothing else; everything the Swing
`JTable` provides for free has to be built:

| What `JTable` gives | What we build |
|---|---|
| click-to-sort with an indicator | header click → `DeviceSort(column, ascending)` in state |
| drag-to-resize columns | header divider with `draggable`, widths in state |
| drag-to-reorder columns | header `detectDragGestures`, order in state |
| hide/show columns | right-click header menu, set in state |
| per-cell renderers | a `when` over the column enum returning a composable |
| alternating row colour | index parity in the row composable |
| "size to fit" | measure text, which Compose can do with `TextMeasurer` |

Proposal: one reusable `ui/components/DataTable.kt` — generic over a row type and a column enum —
rather than a device-specific table. The file explorer and the log viewer are both tables too, and
both are on the list; writing this once is the difference between three big screens and one.

The column set becomes a `DeviceColumn` enum in `data/`, mirroring `DeviceTableModel.Columns`:

```
NAME, SERIAL, MODEL, OS, CARRIER, PHONE, IMEI, BATTERY, FREE, CUSTOM1, CUSTOM2, BOOTED
```

plus the *custom* columns, which in the Swing app are app package names whose installed version is
shown per device ("PM" in the screenshot is one of these). `Device.customProperties` already carries
them.

Column order, hidden set and widths persist through `SettingsManager` —
`AppPref.HIDDEN_COLUMNS` exists already; order and widths need two more constants.

### The menu-bar / tray icon

A second, smaller device list: the icon shows the device count, and the menu lists every device with
four inline actions — Mirror, Browse, Logs, Screenshot — plus Open and Quit.

The Swing app gets this from **dorkbox SystemTray**, and had to force a Swing-drawn tray on macOS to
get icons at all, then hand-draw each row (`TrayUiFactory`) because a native menu can't hold buttons.
Compose Desktop has `Tray` in `androidx.compose.ui.window`, but it takes an ordinary `Menu` — text
items and separators, no per-row buttons.

So the honest options are: accept a submenu per device (native, plain, one extra click per action),
or draw the whole thing in an undecorated always-on-top `Window` positioned under the tray icon
(matches the screenshot, costs the platform's own menu behaviour). **Recommend the submenu first** —
it is a fraction of the work, and the row-of-buttons version can replace it later without anything
above it changing.

---

## The mobile device screen

Deliberately not the same screen. A phone has one column of usable width, a finger instead of a
mouse, and no fleet — its list is short.

```
┌─────────────────────────────┐
│ Devices              ⟳  ⚙   │
├─────────────────────────────┤
│ ● Pixel 10a                 │
│   192.168.0.147   87%  ⚡   │
├─────────────────────────────┤
│ ● kitchen 7                 │
│   192.168.0.175   3.6G      │
├─────────────────────────────┤
│                             │
│         (+ Connect)         │  ← the primary action, not a toolbar button
└─────────────────────────────┘
```

- **A row is two lines, not ten columns.** Name and status on the first, whatever else fits on the
  second. No column configuration on a phone.
- **Connect is the primary action**, because a phone starts with an empty list where the desktop
  starts with a full one. A FAB or a prominent empty state, opening a sheet that offers the three
  routes above.
- **Tap opens the device**, rather than selecting it — there is no second pane to fill. That is what
  `App(compact = true)` already does.
- **Swipe or long-press for actions** where the desktop right-clicks.

---

## Phases

Each phase compiles on all three targets and leaves the app usable.

1. **The device screen.** `DataTable` + `DeviceColumn` + toolbar + status bar on desktop; the two-line
   list and the empty state on mobile. Actions wired only where they already exist (mirror,
   screenshot, refresh); the rest disabled with a tooltip.
2. **Device actions.** Right-click / long-press menu: details, copy field, rename, custom fields,
   reconnect, disconnect. All are `exec` calls that already work.
3. **Connect.** The transport picker and the wireless-connect sheet. `AppPref` already holds the four
   settings; nothing reads them yet.
4. **File explorer.** `AdbSync` already lists, stats, pulls and pushes — this is a `DataTable` and a
   path bar on top of it.
5. **Log viewer.** `logcat` over `exec:`, plus the Swing app's filter syntax.
6. **The tray menu**, submenu form.
7. **Everything else** — stats, install, input, scripts, share server.

## Deliberately later

- **scrcpy and screen recording.** Both shell out to external binaries in the Swing app; see
  PORT-PLAN.md for why that route isn't coming across.
- **Tier III live video.** ~70% of the total work, per ADB-TRANSPORTS.md.
- **Logs-only mode.** A whole second app shape; not worth carrying until the log viewer exists.
