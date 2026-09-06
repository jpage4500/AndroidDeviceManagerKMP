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

- **The configuration cache goes stale on a new source file.** A `.kt` file added to a source set can
  leave `compileDebugKotlinAndroid` reporting `UP-TO-DATE` while the class is never built, and the
  failure surfaces as `Unresolved reference` from the module that uses it. Pass
  `--no-configuration-cache` once after adding files.
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

Version is `1.0.<git commit count>` through gmazzo BuildConfig, the same scheme the Swing app uses.

## iOS

`iosApp/` was copied from DashboardWeatherKMP and renamed: product `DeviceManager`, bundle id
`com.jpage4500.devicemanager`, no development team and no provisioning profile — set your own on
first open. The Xcode build phase runs `:composeApp:embedAndSignAppleFrameworkForXcode`.

`Info.plist` already declares `NSLocalNetworkUsageDescription` and the three adb Bonjour services.
Without them iOS refuses every connection this app makes, and the refusal is silent.

## Build-time keys

`LOGGING_URL` and `LOGGING_API` are read from the environment, then `~/.gradle/gradle.properties`,
and become BuildConfig constants. They're what `LogManager` uploads a log to. Missing resolves to
`""` — the project builds and runs, and the upload refuses. `IS_RELEASE` is true only for the jDeploy
fat jar, an android release variant, or an Xcode Release framework; `PlatformUtils.isDebugBuild()`
reads it.

## Releasing

The desktop app ships through jDeploy to a separate **public** repo,
`jpage4500/AndroidDeviceManagerRelease`. `.github/workflows/build-desktop.yml` does it on a merge to
main; `scripts/build-desktop.sh` does the same thing by hand. `.github/workflows/build.yml` compiles
all three targets on every push and PR and publishes nothing. See [RELEASING.md](RELEASING.md).

## Not set up yet

- No app icons except the jDeploy `icon.png` — nothing for Android, iOS, or the Compose desktop
  `nativeDistributions` bundles.
- No signing config for Android or iOS release builds, so no Play Store or TestFlight job.
