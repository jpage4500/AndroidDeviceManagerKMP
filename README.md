# AndroidDeviceManagerKMP

A Kotlin Multiplatform rewrite of [AndroidDeviceManager](https://github.com/jpage4500/AndroidDeviceManager),
targeting desktop, Android and iOS.

- **Desktop** (macOS / Windows / Linux) — the full app, intended to replace the Java/Swing one.
  Manages many devices at once over `adb`.
- **Android / iOS** — a smaller app: see the devices attached to your computer and remote control
  one.

## Status

Early. What works today: the device list, live hot-plug detection, device properties, and mirroring a
device by polling its screen and sending taps back. More than one window on the desktop.

## Building

Needs **JDK 21** (Gradle 8.14 can't run on Java 25).

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home

./gradlew :composeApp:run                # desktop
./gradlew :androidApp:assembleDebug      # android APK
open iosApp/iosApp.xcodeproj             # ios — set your signing team on first open
```

## Releasing

The desktop app ships as a native installer through [jDeploy](https://www.jdeploy.com), published to
[AndroidDeviceManager2](https://github.com/jpage4500/AndroidDeviceManager2) on every
merge to main. [docs/RELEASING.md](docs/RELEASING.md) has the details.

## How it reaches devices

Through an **adb server** on port 5037 — the same route the Swing app takes, and the only one that
reaches USB-attached devices. A phone can point at a desktop's adb server over the LAN, or at a
running Device Manager's HTTP API.

[docs/ADB-TRANSPORTS.md](docs/ADB-TRANSPORTS.md) explains why, and what it would take to talk to
`adbd` directly instead.

## Docs

[docs/](docs/) — the research behind the design, how the project is put together, and what has been
ported so far.
