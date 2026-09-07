# Porting the Swing app

What has come across from `AndroidDeviceManager` (Java/Swing), what is next, and what is deliberately
staying behind.

The plan this follows is in [ADB-TRANSPORTS.md](ADB-TRANSPORTS.md): put the protocol in `commonMain`
behind a transport interface, keep the desktop on the adb server, and let the phone pick a route.

## Done

### The adb client — `commonMain/adb/`

Written against ktor-network so one implementation serves desktop, Android and iOS. Where a decision
differs from jadb it is because jadb was measured to be wrong; those measurements come from
`FileExplorerKMP/docs/ANDROID-DEVICES.md`, taken against real devices.

| File | What it does |
|---|---|
| `AdbStream.kt` | The one platform-facing thing: a TCP socket over ktor-network |
| `AdbConnection.kt` | The host/server protocol — `%04x` framing, `host:transport:`, features cache |
| `AdbDevices.kt` | `host:devices-l` and `host:track-devices-l`, parsed into `Device` |
| `AdbExec.kt` | `exec:` (never `shell:`), `getprop`, and the `test`-based path probe |
| `AdbSync.kt` | The `sync:` sub-protocol — LIST/LIS2, STAT/STA2, RECV, SEND |
| `AdbOutcome.kt` | How a failure leaves the package; nothing thrown ever escapes it |

Corrections carried over from jadb, each one already paid for:

- **Device list is split by *state name*, not by column.** A serial can contain spaces — two phones
  registering the same mDNS name get ` (2)` appended — and adb pads with spaces, not a tab. Splitting
  on whitespace reads half a serial and marks a working phone offline.
- **File type uses the `S_IFMT` mask**, not single bit tests. A socket sets both bits jadb looks at
  and reads as a directory *and* a symlink.
- **`stat_v2`/`ls_v2` are preferred where present.** v1's `STAT` doesn't follow symlinks, and
  `/sdcard` is itself a symlink, so v1 reports the one folder anyone wants as a 21-byte file. v1's
  size field is a signed 32-bit int that goes negative past 2 GB, so it is read unsigned.
- **`DONE` carries a zero trailer** (16 bytes v1, 72 v2) that must be consumed to stay in frame. That
  is what lets one sync session serve a whole operation instead of jadb's connect-transport-sync per
  file.
- **`.` and `..` are filtered** — adb sends them as ordinary records.
- **A v1 listing carries no ownership**, so `ownerKnown` is false and nothing may infer permissions
  from it.

### The rest

| Area | Where | Note |
|---|---|---|
| `Device` model | `data/Device.kt` | From `data/Device.java`, minus the live handles — it is a value now |
| Device state names | `data/DeviceState.kt` | The full `connection_state_name()` set, matched longest-first |
| Transports | `transport/` | `AdbServerTransport` and `RemoteHttpTransport` behind one interface |
| Device list + refresh | `manager/DeviceManager.kt` | `host:track-devices-l` instead of the Swing app's timer |
| Mirroring | `manager/MirrorSource.kt` | Tier II — `screencap` poll plus `input` events |
| Multiple windows | `manager/WindowManager.kt` + `desktopMain/main.kt` | Ported from FileExplorerKMP |
| UI | `ui/` | One device list and one mirror view; `compact` picks phone or desktop layout |
| Settings, files, logs, http | `util/`, `manager/` | Ported from DashboardWeatherKMP — see [APP-SERVICES.md](APP-SERVICES.md) |
| Device screen | `ui/screen/DeviceScreen.kt`, `ui/components/DataTable.kt` | The desktop's toolbar/table/status bar, and the phone's list — see [UI-PORT.md](UI-PORT.md) |
| Icons | `commonMain/composeResources/drawable/` | All 91 PNGs from the Swing app's `resources/images`, tinted at draw time |

`RemoteHttpTransport` speaks the **existing** Swing app's API — `/api/devices`, `/api/execute`,
`/api/properties`, `/api/files/list`, `/api/screenshot` with bearer auth. So the phone app can be
pointed at today's app and work before any more of it is ported.

## Next, roughly in order

The screen-by-screen detail is in [UI-PORT.md](UI-PORT.md) — this is the summary.

1. **Device actions.** The right-click menu on a row: details, copy field, rename, custom fields,
   reconnect, disconnect. All are `exec` calls that already work, and the table already raises the
   context click.
2. **A connection picker.** Nothing sets a transport yet — it defaults to `127.0.0.1:5037`. The phone
   builds need a screen to enter a host, a port and a token; `AppPref` already has the four prefs to
   keep them in (`ADB_SERVER_HOST`, `ADB_SERVER_PORT`, `REMOTE_URL`, `REMOTE_TOKEN`) and nothing
   reads them yet.
3. **File explorer.** `AdbSync` already lists, stats, pulls and pushes; nothing has a UI on it.
4. **Log viewer.** `logcat` over `exec:`, plus the filter syntax in the Swing app's `LOGS.md`.
5. **Native menus, drag and drop, file dialogs.** See [WINDOWS.md](WINDOWS.md).
6. **Direct-to-`adbd` transport**, if the phone should work without a desktop at all. The hard part is
   pairing, not framing — see [ADB-TRANSPORTS.md](ADB-TRANSPORTS.md).

## Staying behind

- **The `se.vidstige.jadb` package.** Replaced, not ported. Its corrections came across; its code
  did not.
- **`scripts/*.sh` and `*.bat`.** The Swing app extracts shell scripts to a temp directory and runs
  them for mirroring, terminal and recording. A GUI-launched macOS app inherits neither `PATH` nor
  `ANDROID_HOME`, so shelling out to `adb` is unreliable by construction — the socket has no
  environment dependency at all. Where a real terminal or scrcpy is genuinely wanted, launch it
  explicitly rather than through a bundled script.
- **`RemoteHttpServer` / `RemoteServerManager` (the server half).** This app is a client of that API
  for now. Serving it is only worth writing if a KMP instance should host devices for another one.
- **Swing-specific everything** — `BaseScreen`, the table models, cell renderers, row sorters,
  `PreferenceUtils`. Compose has its own answers and they aren't shaped the same. `PreferenceUtils`
  in particular is replaced by `SettingsManager` + `AppPref`, which came from DashboardWeatherKMP
  rather than from here.
