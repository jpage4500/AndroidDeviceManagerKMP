# ADB transports

Feasibility research for a Kotlin Multiplatform Device Manager, written before this project existed.
Everything below was checked against the Swing app's source, the published artifacts, or a live
device — the provenance is marked on each claim.

*Assessed 2026-09-06 against AndroidDeviceManager at commit `e411486`.*

There is also a rendered version of this document, with diagrams:
<https://claude.ai/code/artifact/932ff7a9-b49a-474f-b9ff-0bd506e0c9f6>

---

## Where the Swing app stands

It is a client of the **adb server**, not of any device. It opens a socket to `localhost:5037` and
issues host services; the `adb` binary on `PATH` does the USB enumeration, the RSA auth handshake and
the multiplexing on its behalf.

Verified in that repo:

- `DeviceManager.java:216` — `connection = new JadbConnection()`, defaulting to `localhost:5037`
  (`JadbConnection.java:16-20`).
- Every device operation goes `host:transport:<serial>` then a service string (`JadbDevice.java:76`).
  `Transport.send()` frames each command as `%04x` + ASCII (`Transport.java:54`).
- `src/main/resources/scripts/` shells out to the real `adb` and `scrcpy` binaries for mirroring,
  terminal and recording.

That inheritance buys USB for free and costs a hard dependency on an external binary.

## Two protocols, one service string

Everything downstream follows from the fact that "ADB" names two different wire protocols.

- **Host / server protocol** — TCP 5037, ASCII, `%04x`-length-prefixed commands, `OKAY`/`FAIL`
  replies. This is what jadb speaks, and what `adb/AdbConnection.kt` speaks here.
- **Device protocol** — spoken by `adbd` itself over a USB endpoint or TCP 5555. Binary 24-byte
  message headers, `CNXN` / `AUTH` / `OPEN` / `OKAY` / `WRTE` / `CLSE`, with stream multiplexing
  built in. This is what Kadb and dadb speak. **Not implemented in this project yet.**

The service string is identical on every hop — only the framing around it changes. `JadbDevice`
sends `"shell:"`, `"exec:"` and `"sync:"`; those are exactly the strings an `OPEN` message carries to
`adbd`. That is what makes the layer above the transport portable between the two.

## Does a phone need to run an adb server?

No. The adb server is an ordinary userspace process that does five things:

1. enumerates USB devices
2. holds the RSA key at `~/.android/adbkey`
3. performs the auth handshake with each device
4. keeps one connection per device and multiplexes many clients onto it
5. exposes all of that on port 5037

Only the first needs anything the OS won't hand an app. So on mobile you don't write "an adb server"
— you write the object that does the server's job: one authenticated connection per device, held
open, streams multiplexed over it, owned by a single manager. `manager/DeviceManager.kt` is that
object here.

`adbd` accepts multiple concurrent TCP clients, so an app connecting directly doesn't lock anyone's
laptop out of the same device.

## USB is the constraint that decides everything

Kadb and dadb are **TCP-only** — Kadb's own documentation excludes USB transport discovery. And on
desktop a USB device is exclusively claimed by whichever adb server is running; two processes cannot
own the same interface.

With 45 devices on powered hubs, USB isn't a nice-to-have in this app — it is the product. So:

> **The desktop build keeps talking to the adb server on 5037.** Not a compromise made for the port:
> it is also what lets Android Studio and the `adb` CLI keep working alongside it.

Android *can* reach USB directly through `UsbManager` in host mode without root — the ADB interface
is a standard bulk endpoint pair, and cgutman's AdbLib has done it for years — but OTG plus hub power
makes it a poor fit for a 45-device bench. iOS has no USB host access at all, so it is network-only,
permanently.

## Three routes from a phone to a device

| Route | Reaches USB fleet | Needs a desktop | Status here |
|---|---|---|---|
| adb server on `127.0.0.1:5037` | yes | it *is* the desktop | **done** — `AdbServerTransport` |
| adb server on a LAN address | yes | yes | **done** — same class, different host |
| Device Manager's HTTP API (`:8765`) | yes | yes | **done** — `RemoteHttpTransport` |
| direct to `adbd` on 5555 | no | no | not written |

Which of these is used is normally **inferred**: a remote URL filled in under Settings ▸ Connection
means the HTTP API, otherwise the adb server at whatever host and port are set. Settings ▸ Connection
▸ *Reading devices from* overrides that (`AppPref.TRANSPORT`, `TransportChoice`), and the only override that changes
anything is **adb server**, which ignores a remote URL that is still filled in — so a desktop
configured for both can switch back without clearing a field to do it. `AUTO` is the default and is
the behaviour that predates the setting. A choice with nothing behind it still falls back to the adb
server: a transport that can't reach anything is worse than the wrong one.

### The route that's easy to overlook

Port 5037 is a TCP port, and `AdbConnection.host` is a `var`. Start the desktop's server listening on
the network and a phone drives all 45 USB devices through the transport that already exists — no new
protocol, no crypto, no pairing:

```sh
adb kill-server
adb -a -P 5037 nodaemon server start     # -a = listen on all interfaces
# or: ADB_SERVER_SOCKET=tcp:0.0.0.0:5037 adb server
```

The catch is that 5037 has **no authentication whatsoever** — anyone who can reach the port owns
every attached device. Trusted LAN only. That is exactly why the Swing app's `RemoteHttpServer`, with
its bearer token, is the properly productized version of the same idea, and why the HTTP route beats
the bare 5037 one for anything shipped.

## What Kadb gives you — and what it doesn't

[Kadb](https://github.com/flyfishxu/Kadb) is a Kotlin ADB client that talks to `adbd` directly:
shell, sync push/pull, install, port forwarding, Android 11+ wireless pairing, optional mDNS
discovery. Apache 2.0.

It is KMP-*structured*, not KMP-*portable*. Maven Central publishes exactly three artifacts:

```
com.flyfishxu:kadb
com.flyfishxu:kadb-jvm
com.flyfishxu:kadb-android
```

No `kadb-iosarm64`. Both the repo and klibs.io list the targets as Android and JVM only — largely
because pairing leans on Conscrypt, a JNI wrapper over BoringSSL with no Kotlin/Native equivalent. It
also excludes USB transport and has nothing for screen capture.

**This is why the adb client here is hand-written over ktor-network rather than taken from Kadb.**
ktor-network publishes jvm, android *and* ios artifacts, so one implementation in `commonMain` serves
all three targets.

### If the direct-to-`adbd` transport is ever written

- **The message framing is easy.** 24-byte headers and a handful of commands; pure `commonMain`.
- **Auth has one quirk worth knowing up front.** `adbd` sends a 20-byte token which the client signs
  with RSA-2048 PKCS#1 v1.5 using a SHA-1 DigestInfo prefix, *without hashing it*. Apple's
  `SecKeyCreateSignature` with `kSecKeyAlgorithmRSASignatureDigestPKCS1v15SHA1` does precisely that,
  so it maps cleanly through cinterop. You also hand-roll adb's 524-byte little-endian public-key
  blob.
- **Pairing is the real gap.** Android 11+ wireless debugging needs SPAKE2 plus TLS 1.3 client
  certificates, and no pure-Kotlin implementation exists. Either cinterop into BoringSSL, or sidestep
  it: connect to a legacy `adb tcpip 5555` port with plain RSA auth and let the target's "Allow
  debugging?" dialog establish trust. That second option is the constraint
  [scrcpy-mobile](https://github.com/wsvn53/scrcpy-mobile) ships with, and it is on the App Store —
  so both the approach and the review outcome have precedent.

## Three tiers hide behind "remote control"

| Tier | What it is | Cost |
|---|---|---|
| I | Shell, files, install, logcat | low — pure adb services |
| II | Screenshot-poll mirror with `input tap/swipe/text/keyevent` | low-medium — **implemented**, `manager/MirrorSource.kt` |
| III | scrcpy-grade live video | high — roughly 70% of the total work |

Tier II is why a useful app is close: at ~4 fps it is a real remote control, and no video decoder is
needed on either platform. The Swing app's built-in mirror window is exactly this — `LocalScreenSource`
polls on a fixed delay and `ScreenStreamWebSocket` streams JPEG at a 250 ms default with JSON control
actions for tap, swipe, text, keyevent, quality and interval.

Tier III means pushing `scrcpy-server.jar`, running it via `app_process`, opening two adb streams and
decoding H.264 through MediaCodec on Android and VideoToolbox on iOS. Deliberately not attempted.

## Risks and open questions

- **Wireless debugging is fragile as a product surface.** It switches off on reboot, drops when the
  device leaves the network, and `adb tcpip` needs a one-time USB bootstrap. Someone must also be
  physically holding the target to accept the auth dialog. A support problem, not a code problem.
- **iOS backgrounding kills sockets.** Reconnect logic isn't optional.
- **Local network permission.** iOS needs `NSLocalNetworkUsageDescription` and `NSBonjourServices` —
  both are in `iosApp/iosApp/Info.plist` already.
- **SPAKE2 on Kotlin/Native** is the one genuinely unsolved piece. Decide early whether iOS pairs at
  all.
- **Don't regress the desktop.** 45 devices over hubs is the workload that matters; any transport
  rework should be measured against it before the mobile app gets attention.

## Sources

- [Kadb](https://github.com/flyfishxu/Kadb) · [klibs.io/Kadb](https://klibs.io/project/flyfishxu/Kadb)
- [scrcpy-mobile](https://github.com/wsvn53/scrcpy-mobile) · [scrcpy-ios](https://github.com/wsvn53/scrcpy-ios)
- [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)
- [OVERVIEW.TXT](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/OVERVIEW.TXT) — the adb protocol
- [SYNC.TXT](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/SYNC.TXT) — the sync sub-protocol
- `FileExplorerKMP/docs/ANDROID-DEVICES.md` — the protocol measurements this port reuses
