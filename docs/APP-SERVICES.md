# App services

The layer under the app: settings, files, logs, http. All of it ported from **DashboardWeatherKMP**,
which is where it was written and where a fix should probably go back to.

None of this is about devices. Everything an *adb device* can tell us lives in `adb/`, `transport/`
and `Device` — see [ADB-TRANSPORTS.md](ADB-TRANSPORTS.md). What's here is about the machine the app
itself is running on.

## The shape of it

Each service is one object in `commonMain` in front of a per-platform implementation, so nothing
above it learns which platform it is on:

| Front | Behind it | Where the platform half lives |
|---|---|---|
| `SettingsManager` | `Settings` | SharedPreferences / a `.properties` file / NSUserDefaults |
| `StorageManager` | `FileIO` | `JvmFileIO` (shared) / `IosFileIO` |
| `LoggerConfig` | `AppLogWriter` | logcat / stdout / stdout |
| `NetworkManager` | `createHttpClient()` | OkHttp (shared) / Darwin |
| `PlatformUtils` | — | one `actual object` per platform |

Only `Settings`, `FileIO` and `PlatformUtils` are written three times. `JvmFileIO` and the OkHttp
client are in `jvmCommonMain`, because desktop and android agree about `java.io` and about
`okhttp3`; they differ only in what root directory `FileIO` is handed.

## Settings

`AppPref` is the whole list, one enum constant per setting. The *declared default* is what gives a
pref its type — a `Boolean` default makes it a boolean pref, an `Int` default an int pref, none at
all a nullable string:

```kotlin
ADB_SERVER_PORT("adb_server_port", 5037)          // SettingsManager.getInt(...)
MINIMIZE_TO_TRAY("minimize_to_tray")              // SettingsManager.getBoolean(...) -> false
```

Everything is **stored as a string** underneath, on all three platforms, so a pref can change type
without stranding what a previous build wrote.

Three ways to read one in the UI, in order of preference:

- `SettingsManager.getBoolAsState(pref)` and friends — a composable reading a pref itself.
- `SettingsManager.observePref(pref)` — a *plain* function a composable calls, where there's no state
  to hoist and no parameter to thread through every call site. Reading the counter inside a
  composition records it against the caller's recompose scope however many calls deep it is.
- `SettingsManager.prefChanges` — a flow, for a manager that isn't a composable at all.

A setter that writes the same value it read is a no-op and emits nothing; a slider dragged across
200 values would otherwise wake every collector 200 times.

## Files and logs

`StorageManager` writes on a **single-threaded** dispatcher, and that isn't tidiness: Windows holds
an exclusive lock across an append or a rename, so two concurrent writes to one file fail with
*"process cannot access the file because it is being used by another process"*. `Dispatchers.Default`
is a pool, not a thread.

Everything is relative to a per-platform root, so nothing above `FileIO` ever writes an absolute
path:

| | Root |
|---|---|
| desktop | `~/.android-device-manager` (`Branding.desktopHomeDir`) |
| android | the app's internal storage — private, and wiped on uninstall |
| iOS | `Documents/` |

The adb identity lives at `keys/adbkey`: the RSA-2048 private key this install signs `AUTH`
challenges with, base64 of whichever DER the platform round-trips — PKCS#8 on the JVM, PKCS#1 from
`SecKeyCopyExternalRepresentation`, and only ever read back by the platform that wrote it. It has a
folder of its own so that no "clear logs" can take it: **authorising a key is a dialog somebody
physically taps on the device**, and adbd remembers the key rather than the app, so losing it means
being asked again on every device that had accepted it. `AdbIdentity` generates it once, behind a
mutex and on `ioDispatcher`, since two devices connecting at the same moment would otherwise each
make one and the loser would overwrite a key already authorised.

The log lives at `logs/device-manager.log` and rotates to `.log.old` at 2 MB. `AppLogWriter` keeps
the size in a variable rather than stat-ing the file per line; anything that writes to the log
*behind* it — `LogManager` appending a crash report synchronously — has to call
`invalidateLogFileSize()`.

A line reads `MM-dd-HH:mm:ss [L] TT Tag: message`. `TT` is two hex digits of the thread, or `UI` —
which is the point of it: a socket read that shouldn't be on the UI thread shows up in the log
rather than only in a profiler.

INFO and above go to the file; `AppPref.DEBUG_MODE` drops that to DEBUG. Verbose never does, because
`HttpLog`'s body logging alone would fill 2 MB during a fleet refresh.

## Crashes and log upload

`installCrashHandler()` **chains** the platform's handler rather than replacing it: the previous
handler is what actually kills the process (and on android puts up the crash dialog), so recording
has to happen in front of it.

`LogManager.recordCrash()` writes to `crashes/` **synchronously** — the process is about to die and
the background write scope can't be trusted to flush. On the next launch `onAppStart()` appends
those files to the log, uploads, and deletes them **only** once the upload succeeded, so a failed
upload retries next time.

The upload needs `LOGGING_URL` and `LOGGING_API`, read at build time from the environment or
`~/.gradle/gradle.properties` (see `composeApp/build.gradle.kts`). A checkout without them compiles
and runs; the upload refuses and says so. An uploaded log is prefixed with `Branding.analyticsId`,
because the bucket is shared with the other apps and every upload is an upsert.

**Settings ▸ About is the front end for all of this**: Debug Mode (which is what raises the file's
level from Info to Debug), View Logs, and Support / Feedback. Support takes a description and hands
it to `uploadLogs(text = …)`, which writes it into the log as a `SUPPORT:` line *before* uploading —
so it arrives at the top of the file it describes and nothing has to be correlated by timestamp
afterwards.

## Updates

`UpdateManager.checkForUpdates()` reads the GitHub releases API for `latest` on the **public release
repo** — this one is private, and an API call against it would need a token no install has. See
[RELEASING.md](RELEASING.md). `latest` skips drafts and prereleases, so the rolling `0.0.0-<branch>`
build a branch push publishes never offers itself as an update.

It only ever *reports*, and `isNewer` compares the `2.0.<commit count>` scheme segment by segment as
numbers — a string compare puts 1.0.100 before 1.0.99, and the third segment passes 99 quickly.
Nothing downloads or installs: jDeploy's launcher updates the desktop app on its next start and there
is no store channel for the phone builds, so the offer is a link to the release page.

## HTTP

`NetworkManager` holds **one** client for the whole app — a client owns a connection pool and a
thread pool, and one per request is how an app ends up with hundreds of idle sockets.

Every client `createHttpClient()` builds logs through `HttpLog`, so filtering a log by that one tag
shows the whole network conversation, request correlated to response by its `(N)`. Bodies are logged
only in DEBUG_MODE, only for small textual responses, and API keys and tokens are redacted out of
logged URLs.

**Self-signed certificates are accepted.** The LAN routes this app takes — an adb server on another
machine, a Device Manager instance serving its HTTP API — will not have a certificate a public CA
signed. That is a trusted network only, which port 5037 already required of you.

`evictIdleConnections()` exists for one specific stall: while backgrounded the wifi link drops and
re-associates without the other end getting to send a FIN, so the pooled socket is half-open. It
still looks healthy, the request gets written to it, and nothing comes back until TCP exhausts its
retransmit backoff ~14s later. `AppLifecycle` calls it on the way back to the foreground — before
it flips its flag, so the first request of the new session can't be the one that finds the dead
socket — and again on the way out, to let go of what it can while nobody is looking. It's a no-op on
iOS, which tears its own connections down on suspend.

**Websockets share that client**, and there are two: a Device Manager server's mirrored screen and
its log stream. `install(WebSockets)` is in both platform halves, the keepalive ping is 30s — on
**OkHttp's own engine config**, since the OkHttp engine ignores `install(WebSockets){pingInterval}`
and without it a half-open socket never errors — and `HttpLog` knows to leave a 101 response's body
alone, because reading it would consume the live socket. ktor's `HttpTimeout` exempts a `ws://` URL
from the request timeout, which is why those URLs carry the scheme rather than being `http://` with
an upgrade header.

`gunzip()` in `util/` is there for the log stream: batches arrive as gzipped JSON, because log lines
compress to about a tenth and a busy device prints thousands of them a second. Desktop and android
share `GZIPInputStream`; iOS calls **zlib**, which is in its SDK, with the window-bits value that
says "read whichever header this is" — Foundation's own `decompressed(using:)` speaks raw DEFLATE and
would leave the gzip container to be unpicked by hand. It never throws: a batch that won't decompress
is a batch to drop.

**The adb routes do not come through here.** They speak adb's own framing over a raw socket.

## Strings

Every string the user reads is in `composeApp/src/commonMain/composeResources/values/strings.xml`,
reached as `Res.string.*` from `com.jpage4500.devicemanager.resources` (the package is set in
`compose.resources {}` rather than derived from the module path).

From a composable that's `stringResource(Res.string.foo)`; from a manager that has no composition —
`LogManager` raising a toast — it's the suspend `getString(Res.string.foo)`.

## Toasts

`ToastManager.showToast()` is callable from anywhere, which is the point: a manager has no
composable to show a snackbar from. One at a time, newest wins.

On the desktop it's drawn **in the status bar**, the way FileExplorerKMP does it: the Devices,
Files and Logs bars each wrap one segment in `StatusToastSlot`, which shows that segment normally
and a tinted pill (icon, message, optional action button) while a toast is up — Devices gives up
its centre activity line, Files and Logs their count. Clicking the pill dismisses it; a tooltip
repeats the message when it was clipped. In the bar it covers no row somebody is about to click.

`ToastHost()`, placed once in `App`, floats the toast over the bottom of the window **only while no
`StatusToastSlot` is composed** — the phone, the Input, Commands and Scripts pages, and the Logs and
Files pages with no tab open. Each slot counts itself into `ToastManager.statusBars` while composed.

## Not brought across

Deliberately left in DashboardWeatherKMP, and worth taking if the need turns up:

- `NetworkMonitor` — connectivity callbacks and the local IP. A USB fleet doesn't care, and the
  android half needs `ACCESS_NETWORK_STATE`. Would matter for a phone on a flaky LAN.
- `AnalyticsManager`, `BillingManager`, `UpdateManager` — a session/exit table, IAP, and GitHub
  release checks.
- `ImageLoaderConfig`, `PhotoPicker`, `LocationProvider`, `CpuSampler`, `Toast`'s per-platform
  overlay container.
- `PlatformUtils` came across trimmed: identity, memory and `openUrl`. Everything about screen wake,
  brightness, haptics, permissions and APK installs was dashboard-specific.
