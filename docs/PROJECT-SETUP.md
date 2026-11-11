# Project setup

How this project is put together, and the handful of settings that would cost an afternoon to
rediscover.

## Modules

| Module | What it is |
|---|---|
| `composeApp/` | The KMP library: all shared Kotlin and all the Compose UI. Targets `androidTarget`, `iosArm64`, `iosSimulatorArm64` and `jvm("desktop")` |
| `androidApp/` | The Android application — a manifest, a theme and one `MainActivity` that calls `App(compact = true)` |
| `iosApp/` | The Xcode project. Two Swift files; everything else is the framework `composeApp` builds |

The desktop app has no module of its own — `compose.desktop.application` inside `composeApp` builds
it, with `main()` in `desktopMain`.

## Source sets

```
commonMain      every target: data, adb, transport, manager, ui
 ├── jvmCommonMain   desktop + android — anything that wants java.*
 │    ├── androidMain
 │    └── desktopMain
 └── iosMain
      ├── iosArm64Main
      └── iosSimulatorArm64Main
```

`jvmCommonMain` is hand-wired, which is why `gradle.properties` sets
`kotlin.mpp.applyDefaultHierarchyTemplate=false` — the default template has no node shared by
android and desktop but not iOS. `ioDispatcher`, `JvmFileIO`, the OkHttp `createHttpClient()` and the
JVM crash handler all live there; anything reaching for `java.io` or `java.net` belongs there too
rather than being written twice.

An `actual` in `jvmCommonMain` satisfies both android and desktop, so a service only needs three
implementations when the platforms genuinely disagree — `Settings`, `FileIO`'s root directory and
`PlatformUtils` are the three that do. See [APP-SERVICES.md](APP-SERVICES.md).

## Three build settings that aren't obvious

- **Gradle goes stale on a newly added file.** A `.kt` added to a source set can leave a compile task
  reporting `UP-TO-DATE` while the class is never built; a `.png` added to `composeResources/drawable`
  can leave `Res.drawable` generated empty. Both surface as `Unresolved reference`. Pass
  `--no-configuration-cache` after adding source files; for resources the daemon's own directory
  listing is what's stale, so `./gradlew --stop` and delete `composeApp/build/generated/compose`.
- **JDK 21, not 25.** Gradle 8.14 cannot run on Java 25, and `JAVA_HOME` on this machine points at a
  Homebrew JDK 25. Android Studio and IntelliJ supply their own JDK so they are fine; from a terminal
  use `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew …`.
  `iosApp/.xcode.env` pins the same JDK for the Xcode build phase.
- **ktor 3.5.2, not 3.2.0.** 3.2.0 ships a method name with a space in it
  (`io.ktor.client.plugins.Messages`), which D8 refuses below DEX version 040 — so an Android build
  on minSdk 24 fails to dex `ktor-client-core` with `Space characters in SimpleName … are not allowed`.

## Versions

Compose Multiplatform artifacts are **not all on one version number** — `material3` is on its own
release line (1.9.0 while the rest are 1.11.1). The plugin's `compose.runtime` style accessors are
deprecated in 1.11 and fail the build, so every artifact is named explicitly in
`gradle/libs.versions.toml`.

## Building

| Task | Command |
|---|---|
| Compile desktop | `./gradlew :composeApp:compileKotlinDesktop` |
| Run desktop | `./gradlew :composeApp:run` |
| Compile android | `./gradlew :composeApp:compileDebugKotlinAndroid` |
| Build the APK | `./gradlew :androidApp:assembleDebug` |
| Compile iOS | `./gradlew :composeApp:compileKotlinIosSimulatorArm64` |
| Link the iOS framework | `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64` |
| Print version | `./gradlew -q :composeApp:printVersion` |
| jDeploy fat jar | `./gradlew :composeApp:buildExecutableJar` |

There is no test suite.

Version is `2.0.<git commit count>` through gmazzo BuildConfig — the Swing app's scheme with the major bumped, so this app always sorts above Swing's 1.0.x releases.

### Running the desktop app from IntelliJ

Two Gradle tasks start it, and they are not the same task:

- **`:composeApp:run`** — the Compose plugin's task. It runs everything declared in
  `compose.desktop { application { … } }`, so this is the one to use. In IntelliJ: Run ▸ Edit
  Configurations ▸ **+** ▸ Gradle, Gradle project `AndroidDeviceManagerKMP:composeApp`, task `run`.
- **`desktopRun`** — the KMP plugin's plain `JavaExec` for the `desktop` jvm target. This is what
  IntelliJ generates from the green arrow next to `main()`, and it knows nothing about the
  `compose.desktop` block: without a main class of its own it fails at execution with *No main class
  specified and classpath is not an executable jar*. `jvm("desktop") { mainRun { mainClass … } }` in
  `composeApp/build.gradle.kts` is what supplies it.

Neither is hot reload — the Compose hot-reload plugin (and its `hotRun` task) is not applied here, so
a code change means stopping and running again.

Set **Gradle JVM to JDK 21** in Settings ▸ Build, Execution, Deployment ▸ Build Tools ▸ Gradle;
`.idea/gradle.xml` has it on `#JAVA_HOME`, which is JDK 25 on this machine. `.idea/` is gitignored,
so run configurations are per-checkout and never travel with a clone.

## iOS

`iosApp/` was copied from DashboardWeatherKMP and renamed: product `DeviceManager`, bundle id
`com.jpage4500.devicemanager`, no development team and no provisioning profile — set your own on
first open. The Xcode build phase runs `:composeApp:embedAndSignAppleFrameworkForXcode`.

`Info.plist` already declares `NSLocalNetworkUsageDescription` and the three adb Bonjour services.
Without them iOS refuses every connection this app makes, and the refusal is silent.

It also declares `CADisableMinimumFrameDurationOnPhone`. Compose checks for that key on the first
frame and throws if it's missing, so the app aborts on launch without it; the key is what lets a
120Hz iPhone draw above 60fps, which the mirror screen needs. A target with its own `Info.plist`
needs the key too — `ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false })`
turns the check off, but then the frame rate stays capped.

## Build-time keys

`LOGGING_URL` and `LOGGING_API` are read from the environment, then `~/.gradle/gradle.properties`,
and become BuildConfig constants. They're what `LogManager` uploads a log to. Missing resolves to
`""` — the project builds and runs, and the upload refuses. `IS_RELEASE` is true only for the jDeploy
fat jar, an android release variant, or an Xcode Release framework; `PlatformUtils.isDebugBuild()`
reads it.

## Releasing

The desktop app ships through jDeploy to this repo's GitHub releases.
`.github/workflows/build-desktop.yml` does it on a merge to
main; `scripts/build-desktop.sh` does the same thing by hand. `.github/workflows/build.yml` compiles
all three targets on every push and PR and publishes nothing. See [RELEASING.md](RELEASING.md).

## Not set up yet

- No app icons except the jDeploy `icon.png` — nothing for Android, iOS, or the Compose desktop
  `nativeDistributions` bundles.
- No signing config for Android or iOS release builds, so no Play Store or TestFlight job.
