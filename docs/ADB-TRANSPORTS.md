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
  built in. This is what Kadb and dadb speak, and what `adb/device/` speaks here.

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

## Four routes from a phone to a device

| Route | Reaches USB fleet | Needs a desktop | Status here |
|---|---|---|---|
| adb server on `127.0.0.1:5037` | yes | it *is* the desktop | **done** — `AdbServerTransport` |
| adb server on a LAN address | yes | yes | **done** — same class, different host |
| Device Manager's HTTP API (`:8765`) | yes | yes | **done** — `RemoteHttpTransport` |
| direct to `adbd` on 5555 | no | no | **done** — `DirectDeviceTransport` |

**They are not exclusive.** `DeviceManager` holds a *list* of transports and the device list is the
union of what they all report — a desk at the office and a desk at home are two connections and one
table. A device is identified by its connection and its serial (`Device.id`), because two desks can
each have an `emulator-5554`. See docs/MOBILE-UI.md for the shape.

What the settings describe is **one** of those connections — the one this install is pointed at by
default — and it is inferred the same way it always was: a remote URL filled in under Settings ▸
Connection means the HTTP API, otherwise the adb server at whatever host and port are set. Settings ▸
Connection ▸ *Reading devices from* overrides that (`AppPref.TRANSPORT`, `TransportChoice`), and the
only override that changes anything is **adb server**, which ignores a remote URL that is still
filled in — so a desktop configured for both can switch back without clearing a field to do it.
`AUTO` is the default and is the behaviour that predates the setting. A choice with nothing behind it
still falls back to the adb server: a transport that can't reach anything is worse than the wrong
one.

Saved Device Manager servers are connections **in addition** to that one, connected and disconnected
from the device list rather than from Settings; `SavedServer.connected` is what brings them back at
the next launch. `DeviceManager.applySettings()` replaces only the settings' own connection, so
changing the adb host on a phone doesn't drop the desk it is reading.

### The route that's easy to overlook

Port 5037 is a TCP port, and the address is a value (`AdbServer`) carried into every call rather than
a fixed constant — several servers can be read at once, each with its own. Start the desktop's server
listening on the network and a phone drives all 45 USB devices through the transport that already
exists — no new protocol, no crypto, no pairing:

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

### The direct-to-`adbd` transport, as built

`adb/device/` is the device protocol and `transport/DirectDeviceTransport.kt` is the transport over
it. It reaches a device on a **plain** port — the one `adb tcpip 5555` opens — with no adb server
anywhere, which is the sidestep [scrcpy-mobile](https://github.com/wsvn53/scrcpy-mobile) ships with
and is on the App Store, so both the approach and the review outcome have precedent.

- **The framing was easy, as expected.** `AdbMessage` is the 24-byte header and the eight commands;
  pure `commonMain`. The only integrity check on the wire is `magic == command xor -1`, so a bad
  magic ends the connection rather than trying to resynchronise — there is no frame boundary to
  resynchronise *to*.
- **One connection per device, held open, streams multiplexed over it.** `AdbDeviceConnection` is
  the object that does the adb server's job without being one. Not a nicety: the handshake costs a
  round trip *and* an RSA signature, so a mirror polling `screencap` at 4 fps would otherwise pay
  both on every frame. One reader coroutine owns the read side and routes each message by the
  receiver id in `arg1`; every `WRTE` waits for its `OKAY`, because that is adb's entire flow control
  and sending without waiting deadlocks rather than going faster.
- **Auth's quirk is real and is the easiest thing to get wrong.** `adbd` sends a 20-byte token and
  wants PKCS#1 v1.5 over a SHA-1 `DigestInfo` whose digest field is those bytes *verbatim* — it is
  treating the token as if it were a hash. The JVM reaches that with `NONEwithRSA` and the 15-byte
  header prepended by hand; iOS reaches the same bytes with
  `kSecKeyAlgorithmRSASignatureDigestPKCS1v15SHA1`, which takes a digest and builds the header
  itself. Signing `sha1(token)` produces a well-formed signature that adbd rejects.
- **The 524-byte public-key blob is computed in `commonMain`, not by the platform.** `AdbPublicKey`
  carries `n0inv` and `rr` on top of the modulus, because adbd's verifier is Montgomery-form with no
  bignum library. Neither needs general arithmetic — `n0inv` is a five-step Newton iteration on one
  32-bit word, `rr` is 4096 doublings with a conditional subtract — which is what keeps it off an
  `expect`, since Kotlin/Native has no big-integer type to have done it with.
- **The sync protocol is reused rather than rewritten.** `AdbSync` was written against a socket; it
  now takes an `AdbByteStream`, and `AdbDeviceByteStream` stitches the device protocol's discrete
  `WRTE` chunks back into a byte stream and gathers small writes until a flush. So the file explorer
  works on this route with no second implementation.

### Which port, and why the wireless-debugging one is not it

Worth being exact, because the two ports look alike and only one of them can be dialled:

| Port | Advertised as | What it wants | Reachable here |
|---|---|---|---|
| the one `adb tcpip 5555` opens | `_adb._tcp`, and only while tcpip mode is on (`setup_mdns` runs from `setup_adb`, which needs `service.adb.tcp.port`) | plain `AUTH`: sign a token, and an unknown key raises **"Allow debugging?"** on the device | **yes** |
| the random one on the Wireless debugging screen | `_adb-tls-connect._tcp` | `STLS`, then TLS 1.3 with a client certificate | no, not yet |

**The wireless-debugging port cannot authorise a new key at all**, which is the part that is easy to
get wrong: `adbd_tls_verify_cert` walks the keys the device already trusts, compares each against the
client certificate's public key, and returns 0 if none match — after which
`adbd_auth_tls_handshake` kicks the connection, "only allow one attempt at the handshake". There is
no prompt on that path. `adbd_auth_prompt_user` belongs to the plain `AUTH` path, and that is the
only way a key gets added other than pairing.

So the ordering is forced, and it is also why pairing through a desk does **not** help a phone
connect directly — pairing hands over the *desk's* key, not this app's:

1. get this app's key into the device's `adb_keys`, which today means the plain port and its dialog
2. once it is there, `IteratePublicKeys` finds it on **every** transport, the TLS one included

Which is the real prize in implementing `STLS`: not a new way to gain trust, but the ability to use
trust already gained on the port that survives a reboot and needs no `adb tcpip`. It needs TLS with a
client certificate and **no** exporter — `ktor-network-tls` can do it on desktop and android,
`Network.framework` with a `SecIdentity` on iOS — so it is a much smaller job than pairing.

One exception worth knowing: `adbd_tls_verify_cert` short-circuits with *"auth not required"* when
`ro.adb.secure=0`, so on an emulator or a userdebug build any certificate is accepted.

### Pairing is still the gap, and now for a specific reason

**Wireless-debugging pairing needs a TLS exporter that no platform here exposes.** Reading
`pairing_connection.cpp` settles it: the pairing socket is *itself* TLS — each side presents a
self-signed certificate and accepts the other's unverified — and the SPAKE2 password is not the
six-digit code but `code || SSL_export_keying_material(64, "adb-label")`. That mixing landed in
platform-tools 31 (it is absent in 30.0.5), so every device since Android 12 requires it.

RFC 5705 keying-material export is **not** in the JDK's `SSLSocket`/`SSLEngine` at all, and Apple
offers it in neither Network.framework nor SecureTransport. It exists in BoringSSL, which is what
adb, Conscrypt and Android's own TLS stack use. So pairing directly costs one of:

| Option | Desktop | Android | iOS |
|---|---|---|---|
| **reflect into the platform's own Conscrypt** — no dependency at all | no | **yes**, and it is the cheapest route by far | no |
| Conscrypt as a dependency (`conscrypt-openjdk-uber`, ~8 MB; `conscrypt-android`, ~4 MB per ABI) | yes | yes | **never** — no artifact |
| vendor BoringSSL through cinterop | yes | yes | yes, and it is a C build in the KMP graph |
| ship Google's `adb` binary and run it | not needed | works, and costs ~10 MB and a 16 KB-alignment problem | never — iOS cannot exec |

**The first row is the correction.** Android's own TLS stack *is* Conscrypt, and its
`exportKeyingMaterial` is reachable from an app with no dependency and no binary:

```java
Class.forName("com.android.org.conscrypt.Conscrypt")
     .getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class)
     .invoke(null, sslSocket, "adb-label\u0000", null, 64);
```

A hidden API, but a stable one, and it is how
[libadb-android](https://github.com/MuntashirAkon/libadb-android) pairs — `PairingConnectionCtx`
does the whole handshake in Java. Note the label carries its trailing NUL, matching
`sizeof(kExportedKeyLabel)` on the adb side. TLS 1.3 needs API 29+; below that the platform provider
can't do it and a bundled Conscrypt would be required.

So the earlier conclusion here — that the exporter was out of reach without vendoring BoringSSL, and
that shipping the binary was the only way out on Android — was **wrong about Android**. It remains
true for the desktop JVM and for iOS, where no equivalent exists.

SPAKE2 itself is *not* the blocker it was assumed to be: BoringSSL's variant is Ed25519 with fixed M
and N points, and [spake2-java](https://github.com/MuntashirAkon/spake2-java) is a pure-Java port of
`spake25519.c` with its own Ed25519, which ports to `commonMain` mechanically. The names are
`"adb pair client"`/`"adb pair server"` including their NUL, roles alice/bob, and the derived key
feeds AES-128-GCM for one `PeerInfo` exchange. All of that is portable. The TLS exporter under it is
not.

### Pairing is not connecting

Worth its own heading because it cost an afternoon. `host:pair` exchanges keys and **stops there** —
it attaches nothing, and a successful pair leaves the device list exactly as it was. The device is
then reachable on a *different* random port, the one it advertises as `_adb-tls-connect._tcp`, and
something has to dial it.

`adb` on a desktop never has to be told this, because the server discovers that service itself and
auto-connects. That discovery runs **inside the server process**, and inside an app it needs a
multicast lock this app does not hold — so a bundled server pairs happily and then sits there. Since
the browse in `util/Mdns.kt` already works, `PairingSession.connectPaired` asks directly and then
calls `host:connect`.

It matches on the **guid** `host:pair` reports back (`Successfully paired to … [guid=adb-XXXX]`),
because that guid *is* the connect service's name. With two devices paired, connecting to whichever
answered first would attach the wrong one.

Two smaller things in the same area, both found in a log rather than reasoned about:

- **`$ANDROID_USER_HOME/.android` has to exist before adb starts.** adb does not create it, and it
  writes `adbkey` and `adb_known_hosts.pb` there through a write-then-rename that fails with *"No
  such file or directory"* when it doesn't. The key then lives only in that process, so every restart
  of the server would mint a new one and every paired device would need pairing again.
- **A failure after pairing is not a failure to pair.** The trust is established and survives, so
  `retry()` re-dials rather than pairing again — a second `host:pair` with the same code is refused,
  which would read as the pairing itself having broken.

### The crypto, and how far it is actually verified

`crypto/` is the portable half: `Spake2` byte-compatible with `spake25519.c`, on an `Ed25519` that
does decode/encode/add/subtract/multiply, over a deliberately slow `BigUInt` — schoolbook
multiplication and bit-by-bit division, chosen because a pairing does one scalar multiplication and
then nothing, and the fast 51-bit-limb representation is where this kind of code goes wrong.
Measured at 146 ms for one side on a desktop JVM, which is a spinner, not a problem. `PairingCipher`,
`X509Self` and `Der` sit beside it; only `PairingTls` and the AEAD primitives are platform code.

It is **verified against vectors that did not come from this repo** — `composeApp/src/desktopTest`,
the only tests in the project, and they exist because the alternative is unfalsifiable: this either
matches BoringSSL byte for byte or a handshake fails with nothing to debug.

- SHA-512 against FIPS 180-4, including a message longer than one block
- **M and N decoded to the decimal coordinates written in `spake25519.c`'s own comments**, which
  checks the decoder against BoringSSL rather than against itself
- Ed25519 key generation against RFC 8032, exercising SHA-512, scalar clamping, the ladder and point
  encoding end to end in one comparison
- `L * B == identity`, which is the group order and the ladder agreeing
- the hand-rolled certificate handed to the JVM's own X.509 parser, signature verified
- DER's long-form length boundary, and `PeerInfo`'s fixed 8192 bytes

Three constants differ from each other in a way that is easy to get backwards:

- the **exporter label** `"adb-label"` carries its trailing NUL (`sizeof` on a `char[]`), and so do
  the SPAKE2 names `"adb pair client"` / `"adb pair server"`
- the **HKDF info** `"adb pairing_auth aes-128-gcm key"` does *not* — adb passes `sizeof(info) - 1`

And a wrong pairing code is **not** detected by SPAKE2: both sides complete it holding different
keys, and it is the AES-GCM tag on the first `PeerInfo` that fails. That is where "the pairing code
was wrong" comes from.

What remains unanchored is the SPAKE2 transcript layout — the order of names and messages and their
8-byte length prefixes — which was read straight from the source. Only a device confirms that.


### Pairing, in this app, on Android

No server, no bundled binary. `manager/PairingSession` shows a QR, finds the device over mDNS and
then does the handshake itself through `adb/pairing/AdbPairing` — TLS with a self-signed certificate
over this app's own adb key, 64 bytes of keying material exported and appended to the code, SPAKE2,
then AES-128-GCM for one fixed-size `PeerInfo` each way.

`canPairDirectly` decides which route a platform takes, and it is true only where the exporter is:
Android. A desktop pairs through its own adb server and iOS through one elsewhere.

**Pairing and connecting are the same key or neither works**, and this is the single most important
thing in this file. `adbd_tls_verify_cert` matches on the public key inside the certificate, so
whoever pairs is the only one who can then connect — which is why the two switched together and why
pairing through someone else's server leaves this app still unable to reach the device.

So the connect side had to exist first: `A_STLS` in `AdbDeviceConnection`. The device answers the
opening `CNXN` with `A_STLS`; the client replies with the same command and `A_STLS_VERSION`, hands
over the certificate, and then **waits** — adbd sends `CNXN` once the handshake succeeds, so nothing
is re-sent. That upgrade happens *in place* on the live socket, which is why `AdbSocket` is an
`expect`: only a `java.net.Socket` can be handed to `SSLSocketFactory`, so Android uses one and the
other two keep the ktor socket and answer false to `canStartTls`.

adbd rejects a certificate for a key it has not been paired with by dropping the connection and
saying nothing, so a failed upgrade is reported as "pair with it first" — which is the true reason
almost every time.

### What this replaced, and what it cost

An earlier pass shipped Google's `adb` binary in the APK and ran it as a loopback server, on the
reading that the exporter was out of reach without vendoring BoringSSL. That was wrong about
Android, and the binary is gone. Worth keeping the measurements, because they are the argument
against going back:

| | bundled binary | in-process |
|---|---|---|
| APK | ~5 MB of compressed `libadb.so` entries, two ABIs | nothing |
| 16 KB page sizes | the prebuilt's LOAD segments were 4 KB-aligned, which **blocks a Play release** targeting SDK 35+, and could not be patched — segment offsets would have to move, so it needs relinking | no native code involved |
| side effects | `useLegacyPackaging = true` is required to execute anything out of `nativeLibraryDir`, and it compresses *every* native library, which made the 16 KB checker report "Unknown error" for an unrelated, correctly-aligned androidx library | none; `extractNativeLibs` is back to false and the remaining libraries are stored uncompressed |
| supply chain | a third-party prebuilt running with the app's permissions, pinned by checksum and recorded in a provenance file | code in this repo |
| lifecycle | a child process that could outlive the app and hold port 5037 | none |

The one thing it had going for it: it worked, and it proved the SPAKE2 exchange end to end against a
real device before any of this existed.


## Three tiers hide behind "remote control"

| Tier | What it is | Cost |
|---|---|---|
| I | Shell, files, install, logcat | low — pure adb services |
| II | Still-image mirror with `input tap/swipe/text/keyevent` | low-medium — **implemented**, `manager/MirrorSource.kt` |
| III | scrcpy-grade live video | high — roughly 70% of the total work |

Tier II is why a useful app is close: at ~4 fps it is a real remote control, and no video decoder is
needed on either platform. The lag it costs is paid for on the client: `ui/components/MirrorFeedback.kt`
draws the tap, hold, swipe or key locally the moment it happens, so the wait for the next frame
doesn't read as the input having missed. The Swing app's built-in mirror window is exactly this —
`LocalScreenSource` polls on a fixed delay and `ScreenStreamWebSocket` streams JPEG at a 250 ms
default with JSON control actions for tap, swipe, text, keyevent, quality and interval.

**How the frames arrive depends on the route, and `MirrorSource` takes whichever the device came in
on.** `DeviceTransport.screenStream` is the seam: a transport that can push frames returns a
`ScreenStream`, and one that can't returns null and gets polled.

| Route | Frames | Input |
|---|---|---|
| adb server | `screencap` asked for again each time the last one arrived — a command answering a PNG, with nothing to subscribe to | `input tap` in a shell, one command per gesture |
| direct to `adbd` | the same `screencap`, on a stream of the held-open connection rather than a socket of its own | the same shell command, on another stream |
| Device Manager server | pushed over `/ws/screen`, JPEG, at an interval the server keeps — `transport/RemoteScreenStream.kt` | a text frame up the same socket; no request of its own |

Three things about that websocket are worth knowing before touching it, and all three are in
`AndroidDeviceManager/docs/SERVER_API.md`:

- **The frame header carries the *device's* size, which is not always the image's.** Below the top
  quality the server scales the JPEG down and says so in the header, so a gesture is mapped against
  the header's dimensions — `ScreenFrame`, and `MirrorScreen`'s `toDevice`. Against the bitmap it
  would land in the wrong place on any scaled frame.
- **`intervalMs` in the URL is ignored** — the handshake reads only `serial` and `compress` — so the
  interval is asked for with a `setInterval` message once the socket is up.
- **Quality is asked for the same way, and asked for again after every reconnect.** `setQuality`
  takes `high`, `medium` or `low` — `transport/ScreenQuality`, the Swing combo box's own three: the
  capture is scaled to 1, ¾ or ½ and its JPEG re-encoded at 0.70, 0.50 or 0.30. The server starts
  every socket at high of its own accord, so `RemoteScreenStream` holds whichever level was picked
  and sends it again in the handshake — a mirror dropped on a link too slow for high would otherwise
  come back at the quality that dropped it. **Only a pushed stream has this**: `screencap` answers a
  full-size PNG and takes no argument about it, so a polled mirror is at high whatever is asked,
  which is why `MirrorSource.streamed` exists and why the mirror's menu draws the levels disabled
  when it is false.
- **A refusal arrives *after* the upgrade succeeds.** A bad token, an unknown serial and an unknown
  path all complete the handshake and then send an error and close, so a server too old to have
  `/ws/screen` looks exactly like a connection that dropped. `MirrorSource` tells them apart by
  trying twice: a stream that delivers nothing twice running is demoted to polling for the rest of
  that mirror, since the same server still answers `/api/screenshot` and a slow mirror beats a
  reconnect loop. A stream that has delivered frames is reconnected for as long as it is open.

Text is the one input that differs by more than syntax between the two: the shell needs its spaces
as `%s` and the server does that escaping itself. That is why `DeviceInput.Event` is a value and
`DeviceInput.shell` is only one of its two renderings.

### Tier I's one stream: logcat

Everything else in tier I is a command that finishes, so it is one call on either route. `logcat`
isn't, and it takes the same two shapes as a mirror — `DeviceTransport.logcat`, a `Flow<LogBatch>`
the log window can't tell apart:

| Route | Lines | Process names for the App column |
|---|---|---|
| adb server | `logcat -v threadtime` over `exec:`, socket drained and flushed every 100ms — `adb/AdbLogcat.kt` | the log window runs `ps` every 30s |
| direct to `adbd` | the same, on one stream of the held-open connection, flushed on the same 100ms | the same `ps` on a slow timer |
| Device Manager server | pushed over `/ws/logs` every 500ms as **gzipped JSON** — `transport/RemoteLogcat.kt` | the server sends pid -> name down the same socket every 5s, so no `ps` runs at all |

- **Every batch is gzipped, always.** The plain JSON `logs` message the server's own client can also
  parse is dead code on the server side, so only the binary path is implemented. `util/gunzip` is
  what reads it: `GZIPInputStream` on desktop and android, zlib on iOS.
- **No server-side `filter` is sent**, though the endpoint takes one. The log window filters the
  buffer it already holds, so a filter typed there re-reads thousands of held lines at once and
  clearing it brings them straight back; filtering at the far end would throw those lines away before
  they arrived. That is what the gzip pays for.
- **A line the server couldn't parse comes back as `null null null null null: <line>`** — its
  `LogEntry.toString` doesn't know its own fields are missing. Those are logcat's own
  `--------- beginning of main` separators, and `remoteLogcat` hands on just the line, which is where
  the adb route puts them too.
- **Neither route reconnects.** A stream that ends is a log that stopped, and the window says so —
  resuming by itself would leave a silent gap in the middle of a log somebody is reading.

Tier III means pushing `scrcpy-server.jar`, running it via `app_process`, opening two adb streams and
decoding H.264 through MediaCodec on Android and VideoToolbox on iOS. Deliberately not attempted.

## Risks and open questions

- **Wireless debugging is fragile as a product surface.** It switches off on reboot, drops when the
  device leaves the network, and `adb tcpip` needs a one-time USB bootstrap. Someone must also be
  physically holding the target to accept the auth dialog. A support problem, not a code problem.
- **iOS backgrounding kills sockets.** Reconnect logic isn't optional.
- **Local network permission.** iOS needs `NSLocalNetworkUsageDescription` and `NSBonjourServices` —
  both are in `iosApp/iosApp/Info.plist` already.
- **The TLS exporter, not SPAKE2, is what decides whether iOS ever pairs.** SPAKE2 ports cleanly;
  `SSL_export_keying_material` has no public equivalent on Apple's platforms or in the JDK. See
  *Pairing is still the gap* above for the two ways out and what each costs.
- **Don't regress the desktop.** 45 devices over hubs is the workload that matters; any transport
  rework should be measured against it before the mobile app gets attention.

## Sources

- [Kadb](https://github.com/flyfishxu/Kadb) · [klibs.io/Kadb](https://klibs.io/project/flyfishxu/Kadb)
- [scrcpy-mobile](https://github.com/wsvn53/scrcpy-mobile) · [scrcpy-ios](https://github.com/wsvn53/scrcpy-ios)
- [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)
- [OVERVIEW.TXT](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/OVERVIEW.TXT) — the adb protocol
- [SYNC.TXT](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/SYNC.TXT) — the sync sub-protocol
- [spake2-java](https://github.com/MuntashirAkon/spake2-java) — a pure-Java port of BoringSSL's SPAKE2
- `packages/modules/adb/pairing_connection/pairing_connection.cpp` and `tls/tls_connection.cpp` — the
  pairing wire protocol and the `"adb-label"` exporter
- `FileExplorerKMP/docs/ANDROID-DEVICES.md` — the protocol measurements this port reuses
