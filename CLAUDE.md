# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**AndroidDeviceManagerKMP** — a Kotlin Multiplatform Compose rewrite of
[AndroidDeviceManager](../AndroidDeviceManager) (Java/Swing), targeting **desktop, Android and iOS**.
Base package `com.jpage4500.devicemanager`.

The desktop app is meant to replace the Swing one: managing many devices at once over `adb`, tested
there with up to 45 over USB hubs. The mobile apps are deliberately smaller — a list of connected
devices and a way to remote control one, with a log viewer and file explorer later, in a UI meant for
a phone.

**Read [docs/ADB-TRANSPORTS.md](docs/ADB-TRANSPORTS.md) before touching anything under `adb/` or
`transport/`.** It carries the reason the desktop keeps talking to an adb server, why the adb client
is hand-written rather than taken from a library, and which route reaches which devices.

## Working agreement

**Never launch the app.** Not `./gradlew :composeApp:run`, not in the background, not "just to read
one log line". Compiling is how a change is checked; Joe runs it. Hand him a **what to test** list
instead — what to click and what should happen — and say plainly which parts are unverified.

**Definition of done**, for every change:

1. It compiles on every target it touches — see the table below. There is no test suite.
2. `CHANGES.txt` has its line, appended at the end under today's `MM/DD` (see **CHANGES.txt**).
3. The `docs/` file for the area says what is true now. A note describing the code as it was is worse
   than no note.
4. The response ends with a short **what to test** list.

## Build & Run

**Use JDK 21.** Gradle 8.14 cannot run on Java 25, which is what `JAVA_HOME` points at on this
machine. From a terminal:
`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew …`

| Task | Command |
|---|---|
| Compile desktop | `./gradlew :composeApp:compileKotlinDesktop` |
| Compile android | `./gradlew :composeApp:compileDebugKotlinAndroid` |
| Compile iOS | `./gradlew :composeApp:compileKotlinIosSimulatorArm64` |
| Build the APK | `./gradlew :androidApp:assembleDebug` |
| Link the iOS framework | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` |
| jDeploy fat jar | `./gradlew :composeApp:buildExecutableJar` |

A change to shared code should compile on **all three** before it's done — `commonMain` is most of
the app, and desktop-only verification is how iOS breaks.

Version is `1.0.<git commit count>` (gmazzo BuildConfig), the same scheme the Swing app uses.

## Architecture

`composeApp/` is the KMP library holding everything; `androidApp/` and `iosApp/` are thin shells, and
the desktop app is built by `compose.desktop.application` inside `composeApp` with `main()` in
`desktopMain`. Source sets are `commonMain` → `jvmCommonMain` (desktop + android) → the leaves, plus
`iosMain`. [docs/PROJECT-SETUP.md](docs/PROJECT-SETUP.md) has the details.

### Packages in `commonMain`

- `data/` — `Device`, `DeviceState`, `DeviceFile`, `Branding`. Values, not handles: nothing here owns
  a socket or a connection, so a device can cross a coroutine boundary or a serializer freely.
- `adb/` — the adb client, written against the **server** protocol on port 5037. `AdbStream` is the
  only platform-facing file; everything above it is pure `commonMain`.
- `transport/` — `DeviceTransport` and its implementations. **The seam the whole port turns on**: add
  a way of reaching devices here, and every screen gets it for free.
- `manager/` — `DeviceManager` (the device list and the current transport), `MirrorSource`,
  `WindowManager`, plus the http layer: `NetworkManager`, `HttpClientFactory`, `HttpLog`, `LogManager`.
- `ui/` — `App` (root, `compact` picks the phone or desktop layout), `theme/`, `components/`,
  `screen/`. `components/DataTable.kt` is the app's one table — keyboard, multi-select, sortable,
  resizable — and the file explorer and log viewer are meant to reuse it rather than grow their own.
  See [docs/UI-PORT.md](docs/UI-PORT.md).
- `util/` — the services under the app: `SettingsManager`, `StorageManager`/`FileIO`, `LoggerConfig`
  /`AppLogWriter`, `PlatformUtils`, `ToastManager`, plus `DateUtils`, `JsonUtils`, `TextUtils`,
  `Utils`, `Timer`, `LruCache` and the `ioDispatcher` `expect`. See
  [docs/APP-SERVICES.md](docs/APP-SERVICES.md).

## Rules that apply everywhere

- **Never block the UI thread.** Every adb call is a suspend function that hops to `ioDispatcher`
  first. A socket read on the UI thread is itself the bug.
- **Nothing thrown leaves `adb/`.** Failures come back as `AdbOutcome.Err` with an `AdbError`, and
  the transport layer turns those into `Result`. A caller further up should never be catching
  `IOException`.
- **New ways of reaching a device go behind `DeviceTransport`**, never as a branch inside a screen.
- **Everything platform-specific stays behind `expect`/`actual`**, with the `actual` in the narrowest
  source set that can hold it — `jvmCommonMain` when desktop and android agree.
- **Don't add a dependency** without a reason that survives reading. The adb client is hand-written
  precisely because the two credible libraries cost 3.4 MB and 9.7 MB of jar to do what a few hundred
  lines of socket code does for nothing — and neither publishes an iOS artifact.
- **The desktop is the workload that matters.** 45 devices over USB hubs is the case to protect; a
  change that helps the phone and costs the desktop is the wrong trade.

## Conventions

Carried over from the Swing app and FileExplorerKMP, so all three read the same.

- **Logging** — `LoggerConfig.createLogger("ClassName")` (Kermit). Start each line with the enclosing
  function name: `log.d("connect: ...")`. For errors log `e.message`, not `e`.
- **Comments** — start lowercase, and say what the code is rather than restating it. Where a value or
  a branch exists because something was measured, the comment is the place that measurement lives.
- **Imports** — wildcard imports; no fully-qualified class names in code.
- **Colors and sizes** — `Colors` and `Dimens` in `ui/theme/`, never inline.
- **End-user strings** — `commonMain/composeResources/values/strings.xml`, reached as
  `Res.string.*`; never a literal in a composable.
- **Settings** — a new one is a constant in `AppPref`, read through `SettingsManager`.
- **Re-read before editing** — files may have changed since the last read.

## CHANGES.txt

Every fix or feature appends **one line** to `CHANGES.txt` in the repo root, at the end of the file,
under a bare `MM/DD` header for the current day — add the header only for that day's first entry:

```
09/09
- Rooms: long-press any room to open the tile's edit dialog
```

Write the line as part of making the change rather than sweeping it up afterwards. Work that changes
no code adds nothing.

- **One line per change, no wrapping.** Two changes worth mentioning separately get two lines; don't
  join them with a semicolon or an em dash to keep the count down.
- **High level and behavioural** — what someone using the app would notice, not which files moved
  or how it was done. No file names, selector ids, or rationale; those belong in the commit message.
- **The headline and nothing after it.** Aim under 80 characters, and never past 100. The failure
  mode is a good first clause followed by a colon, comma or dash and then everything the feature can
  do — the fix is to delete from that punctuation onward, not to shorten the sentence evenly.
- **Prefix the line with the part of the app that changed** — a screen, a platform, a feature area
  (`Rooms:`, `iOS:`, `Settings:`) — unless the prefix would name the whole app, which says
  nothing: no `Weather:` in a weather app, no `Files:` in a file manager. Those start with the verb.
- **A fix says what stopped happening**, in the same voice as everything else:
  `- Radar: fixed the app quitting a few seconds after a tile refreshed`.
- **A line earns its place by being something new**, not by explaining something already listed. A
  consequence of a feature, a limit it has, or a rule it follows is not its own entry — the reader
  finds those out by using it. Nothing that starts with "also" or "and now", or restates a feature
  with a caveat attached.

```
- Rooms: long-press any room to open the tile's edit dialog                   ← yes
- Rooms: long-press any room to open the tile's edit dialog — handy when the tile title is hidden
- Devices: track device stats over time and graph them in a new Stats screen  ← yes
- Devices: track battery, temperature and free space over time and graph them in a new Stats
  screen, with filters by OS, model and carrier
```
