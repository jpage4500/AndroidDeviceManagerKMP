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
│ │ + Add a device              │ │  ← the same panel the empty state shows
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

**Grouped by the computer the devices came through.** Every remembered Device Manager server is a row
in the list, and the one being read holds its devices inside it — `ui/screen/ServerGroup.kt`, drawn
with the same `ExpandableBlock` the connect panel's routes use, so the phone has one expanding idiom
rather than two. The connected computer opens itself and the rest stay shut; an explicit tap wins
from then on. Anything no computer owns — a desk's adb server, the test fleet — is a plain row below
the groups, and the connect panel is still the last item.

A group rather than a column on the row, because a serial says nothing about which desk it arrived
from and a remembered computer that isn't connected has nowhere else to be. At most one group is ever
live, since `DeviceManager` holds a single transport — but the shape is already what several
connections need, which is the point.

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
| a Device Manager server | `ServerManager` → `AppPref.SAVED_SERVERS` | its group opens; Connect is inside it |
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

**One `ConnectPanel` composable, two placements.** With nothing to show at all — no devices and no
remembered computer — it is the empty state, centred and expanded. Otherwise it is the last item of
the list, collapsed to a single "+ Add a device" card that expands in place. The same composable in
both cases, so the two can never drift apart.

**An open section has to look like part of the row that opened it.** A form that simply appears
underneath reads as the next item in the list, which is what the first version did wrong. Open, a
route becomes one block: its own ground, a rounded edge, an accent stripe down the left of the row
*and* its form, and no rule between the two — the rule only ever falls between blocks. The stripe is
painted with `drawBehind` rather than laid out as a sibling, because it has to be as tall as both
halves and nothing can know that height while the form is still animating. The chevron turns rather
than swapping icon, and the form expands and fades rather than appearing, so the eye follows where
the content came from.

That drawing is `ui/components/ExpandableBlock.kt`, not this file's own: the device list's computers
expand the same way, and a phone with two expanding idioms has one of them drawn slightly wrong.
Remembered computers themselves are **not** on this panel — they are rows of the device list, next to
the devices they own. Two places listing the same server is one of them going stale.

The block's **inset does not change with its state**. Insetting only when open moved the row's icon
and title down and to the right at the instant the form appeared, and that one-frame shift was the
whole of the jank — the expand animation itself was fine.

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
│  │        pair with its      │  │
│  │        wireless debugging │  │
│  └───────────────────────────┘  │
│                                 │
│  Found on this network:         │
│  · Pixel 8   192.168.0.171      │
└─────────────────────────────────┘
```

Ordered by how likely each is to work, not by how interesting it is.

**A QR code is not a route — it is how you reach the thing behind it.** Both QR flows started as
rows of their own, which read as unrelated things to try; each belongs inside the section it serves,
as that section's main action. So there are two rows, and each is ordered by how little work its ways
in take:

- **Connect to a computer** — *scan* the QR that Device Manager shows on its Share Server dialog
  (nothing typed at all), then *manual entry* behind a link for the address, port and token.
- **Connect to a device** — *pair with a QR code*, which this app **shows** and the other device
  scans. No address field, deliberately: see below.

The two QRs point in opposite directions and it is worth being clear which is which — one has our
camera reading someone else's screen, the other has our screen being read by someone else's camera.

**The two sections mean different things, and only one of them is finished.** A computer is reached
over the Device Manager HTTP API and works today. A *device* is meant to be reached **directly, with
no computer anywhere** — that is the whole point of the section — and that needs the `adbd`
transport, which is not written. So there is no address field there: typing one would quietly go
through a desk's adb server, which is the opposite of what the section says it does. The QR pairing
in it works, but it still leans on a desk for the handshake, and the section says so.

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

**Mirroring over this route should use `/ws/screen`, not polled `/api/screenshot`.** The websocket
pushes JPEG at a server-controlled interval with `intervalMs` and `quality` messages back;
`RemoteHttpTransport` currently polls, which on a phone is a round trip and a fresh JPEG decision
every 250ms. That is the mobile mirror's path, not a nice-to-have.

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

**The button is only offered where it can work, and the server is asked before anyone is asked.**
Both halves of that were learned by running it:

- `canPair` was true for any `AdbServerTransport`, and a phone's default address is `127.0.0.1`.
  There is no adb server inside an Android or iOS app, so the button was offered, a QR was shown, a
  device scanned it, mDNS found it at `192.168.0.85:45615` — and only *then* did it say
  `no adb server on 127.0.0.1`. `canPair` now excludes loopback on anything that isn't a desktop, and
  the row says to set the adb server host in Settings.
- The remaining case is a host that *is* set but isn't answering — the desk asleep, or its server not
  started with `-a`. So `PairingSession` calls `DeviceTransport.probe()` (`host:version`, the
  cheapest thing the server answers) **before** it shows a QR at all, and shows the failure instead.

**A failure can be retried without starting over.** mDNS announces a service once and then goes
quiet: `onServiceFound` does not fire again for a device already known, so a pairing that failed for
any transient reason would otherwise need the target's dialog closed and reopened. The resolved
address is kept and a Try again link re-attempts with it.

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

The gear in the title bar opens the desktop's own `SettingsScreen` in the phone's overlay container.
It had to be reachable before anything else could be tested, because **the adb server address lives
there**: a phone's `AdbServerTransport` defaults to `127.0.0.1:5037`, which on a phone is nothing at
all, so until that host is set to a desk the app can only read a Device Manager server or the test
devices — and pairing, which needs an adb server behind it, can never be offered.

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

Still missing on the phone: which connections are saved (with Edit and Forget), Keep connected, and
mirror quality.

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
2. ~~**The device sheet.**~~ **Done.** `ui/screen/DeviceSheet.kt`: `DeviceInfoScreen` inside a
   `DialogContainer`, the 2×2 action grid above it, and Copy serial / Rename / Restart / Disconnect
   below. Mirror and Screenshot are wired; Files and Logs are drawn disabled. Restart confirms when
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
10. **Direct to `adbd`**, which is what "Connect to a device" is *for* and the reason its address
    field is switched off today. Two problems, and only one is hard: the **transport** is the adb
    device protocol — 24-byte framing, `CNXN`/`OPEN`/`WRTE`, pure `commonMain` — and the **trust**
    step is the one with no pure-Kotlin answer. Three ways through it, cheapest first:

    - **Legacy `adb tcpip 5555`.** Plain RSA auth, no TLS: sign a 20-byte token with RSA-2048 and a
      SHA-1 DigestInfo prefix *without hashing it*, plus adb's 524-byte pubkey blob. `scrcpy-mobile`
      ships this and is on the App Store. The catch is the bootstrap — something must run
      `adb tcpip 5555` on the target once — **which composes with the pairing that now exists**: pair
      once through a desk, run `adb tcpip` through that desk, and the phone talks to that device
      directly ever after.
    - **SPAKE2 for real**, plus TLS 1.3 with a generated client certificate and platform TLS on both
      sides. The only route to "scan and go, no computer ever", and probably the largest remaining
      piece of work outside Tier III video.
    - **Bundle the `adb` binary** on Android, as LADB does. A weekend's work, Android only, a couple
      of MB per ABI, and against the reason `scripts/*.sh` is on PORT-PLAN.md's staying-behind list.

## Deliberately later

- **`DataTable` on a phone.** The file explorer and the log viewer reuse it on the desktop; on a
  phone both want a list with a path bar or a filter bar, not a table.
- **Multi-select and bulk actions.** The desktop's reason for existing; a phone has no fleet. Built
  once — long press for a picker, a FAB, Remove Device — and taken back out: it was a mode to get
  into and out of, over a list of three rows, for one action nobody was asking a phone for.
- **Standalone pairing (SPAKE2).** See above, and ADB-TRANSPORTS.md.
- **Tier III live video.** ~70% of the total work, per ADB-TRANSPORTS.md, on the platform least able
  to spare it.
- **Serving the HTTP API from a phone.** A phone has nothing attached to serve.
