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
android and desktop but not iOS. Right now only `ioDispatcher` lives there; anything reaching for
`java.io` or `java.net` belongs there too rather than being written twice.

## Two build settings that aren't obvious

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

## Not set up yet

- No app icons on any platform.
- No CI. The Swing app's `.github/workflows/jdeploy.yml` is the model for the desktop half.
- No signing config for Android release builds.
- No `composeResources/` strings — the UI has its text inline so far.
