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

**More than one connection at a time.** A desk has one adb server and that is the whole story;
`DeviceManager` holds exactly one `DeviceTransport` and `useTransport` closes the previous one. A
phone is the opposite case: two devices reached directly, plus a desk at the office, plus a desk at
home, and the list it shows is the **union** of all of them.

The Swing app already works this way — `RemoteConnectionManager` holds a list of `RemoteServerConfig`
with a colour each, and `Device.remoteConnection` says which one a row came from. The phone needs the
same shape, and the desktop keeps working because a list of one behaves like the single transport it
has today.

Two things block it right now, both worth knowing before any of this is written:

- **`AdbServerTransport.aim()` sets a global.** `AdbConnection.host` / `.port` are set before every
  call, which the class's own comment says only works because one transport is active at a time. Two
  adb servers at once would fight over it, so the address has to move onto the connection first.
- **A serial isn't a unique key across connections.** `_devices` is keyed on serial, so is the
  selection, so is `WindowManager.openOnce`. Two desks can each have an `emulator-5554`. Device
  identity on mobile is *(connection id, serial)*, and the connection id has to be on `Device` before
  a second connection is allowed to exist.

## The device list

```
┌─────────────────────────────────┐
│ Devices                    ⟳ ⚙  │
├─────────────────────────────────┤
│ CONNECTED · 2                   │
│ ● Pixel 10a            87% ⚡   │
│   192.168.0.147 · Android 16    │
├─────────────────────────────────┤
│ ● kitchen 7            3.6G     │
│   emulator-5554 · joe's imac    │  ← which connection it came through
├─────────────────────────────────┤
│ NOT CONNECTED · 3               │
│ ○ Pixel 8                       │
│   192.168.0.171 · 2 days ago    │
├─────────────────────────────────┤
│ ○ joe's imac                    │
│   192.168.0.12:8765 · a server  │
├─────────────────────────────────┤
│ ┌─────────────────────────────┐ │
│ │ + Add a device              │ │  ← the same panel the empty state shows
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

**Two sections, one list.** Connected on top, everything remembered but not currently reachable
below it, and the connect panel as the last item. One `LazyColumn` with sticky headers, not two
lists — the sections are a grouping of one collection, and a phone that shows an empty "CONNECTED"
header above a full "NOT CONNECTED" one has told the user everything they need in one glance.

**A row is two lines, not ten columns.** Name and the live numbers on the first line, identity on the
second. `Dimens.touchRowHeight` (68dp) is already the row height, and `DeviceListScreen` already
draws roughly this row — what it lacks is the sections, the connection name and the offline half.

**The second line says where the device came from** once more than one connection can exist. A
device reached directly shows its address; a device belonging to a desk shows that desk's name. This
is the Swing app's per-server colour in a form that survives on a phone, where a 4px colour chip is
not a label.

### The "not connected" half

This is the part the desktop has no equivalent of, because a desktop's adb server *is* the memory. On
a phone the list has to be persisted by the app, and three different kinds of thing land in it — two
of which the app has to remember for itself:

| Remembered | Where it lives today | Tapping it |
|---|---|---|
| a device reached directly (`host:port`) | `WirelessManager` → `AppPref.RECENT_WIRELESS` | re-dial that address |
| a Device Manager server | nowhere — `AppPref.REMOTE_URL`/`REMOTE_TOKEN` hold **one** | reconnect to that server |
| a device belonging to a connected desk, currently offline | the desk's own list | nothing; the desk owns it |

So two changes: `WirelessManager`'s cap of ten (`WirelessDevice.MAX_RECENT`) is the Swing app's
number for a dialog's history list and is too small for a phone's whole list — raise it and let the
user forget entries — and a **`SAVED_SERVERS` pref** has to exist, a JSON list mirroring the Swing
app's `RemoteServerConfig` (`id`, `name`, `host`, `port`, `token`, `enabled`), because the phone will
have more than one desk.

A remembered entry keeps the friendliest name it ever had and when it was last seen, which is what
makes the offline row readable — `WirelessDevice` already carries `name` and `lastSeenMs`.

**Long-press an offline row** to Forget it. **Tap it** to reconnect. Nothing else: an offline device
can't be mirrored or browsed, and offering the action is worse than not having it.

### Picking several at once

A **long press on any row** turns the list into a picker. In it a tap toggles a row rather than
opening it, a check appears on the right, and **back leaves the mode before it leaves the app** —
which is the one thing a mode like this owes the person in it. The title bar counts what is picked
and its back arrow is the way out for a platform with no back key.

The actions live behind a **FAB in the bottom-right** rather than in the title bar, because that is
the corner a thumb reaches without crossing the screen: Remove Device, then Exit. The connect panel
is hidden while the picker is up — it is not one of the things being picked.

The selection itself is `DeviceManager.selection`, the same `TableSelection` the desktop table
multi-selects into, so an action written against it works on both. Taking the last row out of the
selection ends the mode rather than leaving an empty picker.

## Tapping a device: the detail sheet

Tapping a **connected** row opens a sheet over the list — not a navigation, and not straight into the
mirror as the code does today. A phone user tapping a device usually wants to see what it is; going
straight to a 250ms screencap poll is an expensive guess.

```
┌─────────────────────────────────┐
│ Pixel 10a                    ✕  │
│ 192.168.0.147 · joe's imac      │
├─────────────────────────────────┤
│ [ Mirror ] [ Screenshot ]       │
│ [ Files  ] [ Logs       ]       │
├─────────────────────────────────┤
│ Serial      192.168.0.147:5555  │
│ Model       Pixel 10a           │
│ Android     16 (SDK 36)         │
│ Carrier     …                   │
│ Storage     ▓▓▓▓▓▓░░░  62%      │
│ Battery     ▓▓▓▓▓▓▓▓░  87% ⚡   │
├─────────────────────────────────┤
│ Rename · Restart · Disconnect   │
└─────────────────────────────────┘
```

- **The body is `DeviceInfoScreen`**, which already exists and already reads the live device rather
  than a snapshot, so the numbers move while the sheet is open. It is 184 lines of properties list
  and two gauges, written for the desktop's Info window; on a phone it is the same composable inside
  the sheet.
- **The actions above it are the desktop's row menu**, filtered to what a phone can do and ordered by
  what a phone is for. Mirror and Screenshot are the two the user reaches for; Files and Logs come
  when those screens exist. Rename, Restart and Disconnect go at the bottom as text, away from the
  thumb.
- **A destructive action still confirms** — `AppPref.CONFIRM_DESTRUCTIVE` is already the setting, and
  Restart on the wrong row is exactly the mistake a phone makes easier.
- **The sheet is a `DialogContainer`**, whose phone actual is already an overlay rather than a window.

**Screenshot means something different on a phone.** The desktop writes a PNG to a folder. A phone
has no folder anyone will find, so a screenshot opens full-screen with a **Share** button and goes
through the system share sheet — that needs no permission on either platform, where saving to Photos
or the gallery does. One new `expect fun shareImage(bytes: ByteArray, name: String)`.

**Mirror, Files and Logs are full screens, not sheets**, pushed on top of the list with a back
button. They already have names: `WindowContent` is a sealed interface in `WindowManager.kt` naming
exactly these — Mirror, Logs, Files, Info, Connect, Settings, Message. Reuse it as the phone's route
type and one enum names both a desktop window and a phone screen, so the shared action that opens a
mirror doesn't need to know which it is.

## Connecting a device

The screen a first-run user sees, and the one that decides whether the app is any good.

**One `ConnectPanel` composable, two placements.** With no devices it is the empty state, centred and
expanded. With devices it is the last item of the list, collapsed to a single "+ Add a device" card
that expands in place. The same composable in both cases, so the two can never drift apart.

**An open section has to look like part of the row that opened it.** A form that simply appears
underneath reads as the next item in the list, which is what the first version did wrong. Open, a
route becomes one block: its own ground, a rounded edge, an accent stripe down the left of the row
*and* its form, and no rule between the two — the rule only ever falls between blocks. The stripe is
painted with `drawBehind` rather than laid out as a sibling, because it has to be as tall as both
halves and nothing can know that height while the form is still animating. The chevron turns rather
than swapping icon, and the form expands and fades rather than appearing, so the eye follows where
the content came from.

The block's **inset does not change with its state**. Insetting only when open moved the row's icon
and title down and to the right at the instant the form appeared, and that one-frame shift was the
whole of the jank — the expand animation itself was fine.

**A form's one real action is centred.** A button hard against the left edge of a panel reads as a
label rather than as the thing to press.

**The keyboard must not sit on the fields.** The phone's whole layout takes `imePadding()`, so the
list underneath gets shorter and Compose's own scroll-the-focused-field-into-view has a viewport that
knows it shrank. Padding the field instead leaves the scroll container full height, and the field
scrolls behind the keyboard where nothing can reach it.

```
┌─────────────────────────────────┐
│  No devices yet                 │
│                                 │
│  ┌───────────────────────────┐  │
│  │ [mac]  Connect to a      ▾│  │
│  │        computer running   │  │
│  │        Device Manager     │  │
│  │ ┃                         │  │
│  │ ┃   [ ▣  Scan a QR code ] │  │
│  │ ┃   shown by Device Mgr   │  │
│  │ ┃    on your computer     │  │
│  │ ┃                         │  │
│  │ ┃    ⎘ Paste connection   │  │
│  │ ┃      Manual entry       │  │
│  ├───────────────────────────┤  │
│  │ [dev]  Connect to a device▸│ │
│  │        by IP address      │  │
│  ├───────────────────────────┤  │
│  │ [qr]   Pair a device with │  │
│  │        a QR code    (Later)│ │
│  └───────────────────────────┘  │
│                                 │
│  Found on this network:         │
│  · Pixel 8   192.168.0.171      │
└─────────────────────────────────┘
```

Ordered by how likely each is to work, not by how interesting it is.

**Scanning is not a route of its own — it is how you reach a computer.** It was a fourth row at
first, which read as a fourth unrelated thing to try; it belongs inside "Connect to a computer" as
that section's main action, because that is the only thing a scan does. Inside the section the three
ways are ordered by how little work each is: **scan** needs nothing typed, **paste connection** is
the same thing without a camera and connects straight away rather than only filling the fields, and
**manual entry** is a link that reveals the three fields — most people should never see them.

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

Three things to get right:

- **Hit `/api/info` on Connect, not `/api/devices`.** It is the cheapest call, it proves the token,
  and it hands back the server's own name — which is what the row in the list is labelled with
  rather than an IP. `ServerManager.connect` saves the entry only after it answers.
- **Accept a pasted connection string.** The Swing app's Share Server dialog copies
  `adm://<base64 of {"host","port","token","name"}>` (`RemoteConnectionUtils`), and typing a
  16-character token on a phone keyboard is the worst thing in this app. `SavedServer`
  reads and writes that format, and the panel's **Paste connection** button fills the three fields
  from it. Paste beats typing; a QR code of the same string beats paste, which is the next section.
- **A phone on this route cannot add a device to that desk.** There is no connect and no pair
  endpoint in the API, and the brief is not to change it. So when the active connection is a Device
  Manager server, the "connect a device by IP" option is about *this phone*, not about the desk — and
  the panel should say so rather than silently doing something else.

**Mirroring over this route should use `/ws/screen`, not polled `/api/screenshot`.** The websocket
pushes JPEG at a server-controlled interval with `intervalMs` and `quality` messages back;
`RemoteHttpTransport` currently polls, which on a phone is a round trip and a fresh JPEG decision
every 250ms. That is the mobile mirror's path, not a nice-to-have.

### Route 3b: scan a QR code from the desktop

The same connection string, read by the camera instead of the keyboard. The desktop shows a QR of its
`adm://…` string; the phone points at the screen and is connected. Nothing about the protocol changes
— this is purely how the token gets across.

This is the single largest usability win available and it costs almost nothing:

- **iOS decodes QR with no dependency at all** — `AVCaptureMetadataOutput` with
  `AVMetadataObject.ObjectType.qr`.
- **Android needs one.** ML Kit barcode scanning is the obvious choice and is several MB; CameraX
  plus ZXing core is smaller. Either is a real dependency and wants the reason written down before
  it lands — see the rule in CLAUDE.md.
- Behind one `expect suspend fun scanQrCode(): String?`, so the panel doesn't know which.
- Needs a camera permission on both, and a usage string in `Info.plist`.

The desktop side is a new dialog next to the existing Share Server one, and **generating** a QR is
pure `commonMain` — a QR encoder is a few hundred lines and draws to a Compose `Canvas`, no
dependency and no platform code.

### Route 1: connect to a device by IP address

An address and a port, defaulting to 5555, with Connect. What actually happens depends on what the
phone is connected to, and this is where it gets interesting:

| Situation | What "connect" means | Status |
|---|---|---|
| the phone is reading a desk's **adb server** | `host:connect:<host>:<port>` on that server — the desk dials the device, the phone sees it appear | **done** — `AdbServerTransport.connect` |
| the phone is on its own | speak the **device** protocol to `adbd` on 5555 directly: 24-byte framing, RSA auth | not written |
| the phone is reading a **Device Manager server** | nothing — no endpoint for it | not possible without an API change |

One thing left to fix here, and it helps the desktop's own Connect dialog too:

- **Nobody should have to type an IP address.** Both platforms can browse mDNS for
  `_adb-tls-connect._tcp`, which is how a device with wireless debugging on advertises itself:
  `NsdManager` on Android, `NWBrowser` on iOS. `iosApp/Info.plist` already declares that service and
  its two siblings, and `NSLocalNetworkUsageDescription` is already there. Behind one `expect`, this
  turns the field into a list — and the field stays, for a device on another subnet.

  ktor-network has no mDNS, so this is platform code on both sides, and on Android it wants
  `CHANGE_WIFI_MULTICAST_STATE` and a multicast lock. That permission is also one of the ones the
  foreground service needs (below), which is convenient.

The prefill rule from the desktop's `ConnectScreen` is worth keeping: open on the **last** address
connected to with its final octet selected, so the phone next to the last one is a few keystrokes.
With a numeric IME.

### Route 2: pair a device with a QR code

The phone shows a QR; the target Android device scans it from Settings ▸ Developer options ▸ Wireless
debugging ▸ *Pair device with QR code*.

The QR itself is trivial and already documented by the Swing app's `QrCodeDialog`:

```
WIFI:T:ADB;S:ADB_WIFI_<20 random chars>;P:<20 random chars>;;
```

What happens after the scan is the whole problem. The target starts advertising
`_adb-tls-pairing._tcp.local.` on a random port, and whoever wants to pair with it must complete a
**SPAKE2 handshake and then a TLS 1.3 session with client certificates**, using that password. There
is no pure-Kotlin implementation of SPAKE2, which ADB-TRANSPORTS.md calls the one genuinely unsolved
piece of this project.

The Swing app does not implement it either — and how it avoids doing so is the answer for mobile
too. `QrCodeDialog` only does the **discovery**, over JmDNS; when the service resolves it hands the
address and the password to the adb server as `host:pair:<code>:<host>:<port>` (jadb's
`HostPairWithRemoteTcpDevice`) and lets the server do the crypto.

So there are two versions of this button, and only one of them is buildable now:

- **Delegated (buildable).** The phone shows the QR and browses mDNS for the pairing service — both
  platform APIs, no crypto — then sends `host:pair:…` to whichever adb server it is reading. The
  newly paired device attaches to that desk, which is where the phone is already getting its list
  from, so it simply appears. Needs a `pair(host, port, code)` method on `DeviceTransport`, an
  `AdbServerTransport` implementation of it, and the mDNS `expect` that route 1 wants anyway.
- **Standalone (not buildable).** A phone with no desk anywhere has to do SPAKE2 itself. Deferred
  indefinitely; the sidestep, if a standalone phone matters, is a device already on legacy
  `adb tcpip 5555` with plain RSA auth — which is route 1's direct case and is what
  [scrcpy-mobile](https://github.com/wsvn53/scrcpy-mobile) ships with.

**So the button has a precondition**, and the panel must say it rather than fail: pairing needs a
computer running adb on this network. Offering an action that cannot work is worse than not offering
it.

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

### What "reconnect on foreground" means, per transport

There has to be a lifecycle signal in `commonMain` for any of this: one `expect` object exposing
`StateFlow<Boolean> isForeground` — `ProcessLifecycleOwner` on Android, `UIApplication`'s
notifications on iOS, and permanently `true` on desktop. Everything below hangs off it.

| Transport | On background | On foreground |
|---|---|---|
| `AdbServerTransport` | close the tracker deliberately; left alone, the `host:track-devices-l` socket dies without erroring and the tracker just goes quiet | `DeviceManager.refresh()` already cancels and restarts the tracker — that is the hook, and it exists |
| `RemoteHttpTransport` | stop the 3s poll | call `evictIdleConnections()` before the first request — APP-SERVICES.md documents this for exactly this case, and nothing calls it. The half-open socket after a wifi re-associate looks healthy and stalls ~14s |
| direct to `adbd` | connection gone | full reconnect and re-auth |

And two things must **stop** when the app goes to the background, notification or not:

- `MirrorSource` polls a screencap every 250ms (`AppPref.MIRROR_INTERVAL_MS`). A backgrounded mirror
  is pure waste and, on a metered link, rude.
- The details refresh timer (`AppPref.DETAILS_REFRESH_SECS`, 60s) — battery and free space on a
  screen nobody is looking at.

A device that fails to come back should show up in the **not connected** section with its last-seen
time, not vanish. That is the section's whole purpose.

## Settings

Small, and mostly the connection list. `SettingsScreen` exists for the desktop; the phone's version
is the same prefs at touch size: which connections are saved (with Edit and Forget), Keep connected,
mirror quality/interval, and the log-file link the desktop has.

## Build order

Each step leaves the app usable and compiles on all three targets.

1. ~~**The connect panel, and Device Manager servers.**~~ **Done.** `ui/screen/ConnectPanel.kt` in
   both placements — expanded as the empty state, collapsed as the list's last item —
   `data/SavedServer.kt` (the `adm://` connection string, read and written),
   `manager/ServerManager.kt` over a new `AppPref.SAVED_SERVERS`, and
   `RemoteHttpTransport.info()` on `/api/info` as the thing Connect actually calls. The two QR rows
   are drawn disabled, so the panel is the real shape rather than a stub. `0.0.0.0` in either field
   loads `transport/FakeTransport.kt`.
2. ~~**The device sheet.**~~ **Done.** `ui/screen/DeviceSheet.kt`: `DeviceInfoScreen` inside a
   `DialogContainer`, the 2×2 action grid above it, and Copy serial / Rename / Restart / Disconnect
   below. Mirror and Screenshot are wired; Files and Logs are drawn disabled. Restart confirms when
   `CONFIRM_DESTRUCTIVE` is on. A screenshot is shown and thrown away — the share sheet is still
   waiting on `shareImage`.
3. **The lifecycle seam.** `isForeground`, reconnect on return, stop polling on leave. iOS needs it
   and everything after it assumes it.
4. **The list's two sections**, and remembered offline devices.
5. **QR scan** for a server's connection string, and QR generate on the desktop.
6. **Many connections at once** — connection id on `Device`, address off the `AdbConnection` global,
   `DeviceManager` holding a list. The one change that must be measured against 45 devices on hubs
   before it lands.
7. **`host:connect` and mDNS discovery**, which is route 1 through a desk.
8. **The Android foreground service.**
9. **Delegated QR pairing** — mDNS plus `host:pair` on the adb server.
10. **Direct to `adbd`** with RSA auth, if a phone should work with no desk at all. SPAKE2, only if
    ever.

## Deliberately later

- **`DataTable` on a phone.** The file explorer and the log viewer reuse it on the desktop; on a
  phone both want a list with a path bar or a filter bar, not a table.
- **Multi-select and bulk actions.** The desktop's reason for existing; a phone has no fleet.
- **Standalone pairing (SPAKE2).** See above, and ADB-TRANSPORTS.md.
- **Tier III live video.** ~70% of the total work, per ADB-TRANSPORTS.md, on the platform least able
  to spare it.
- **Serving the HTTP API from a phone.** A phone has nothing attached to serve.
