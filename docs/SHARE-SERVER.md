# Share Devices

The desktop serving its devices to other copies of the app — a phone on the same Wi-Fi, a laptop on
the VPN, a Swing app at another desk. The KMP port of the Swing app's `RemoteServerManager` /
`RemoteHttpServer` and its Share Devices dialog. **Desktop only**: the phone is this server's
client ([`RemoteHttpTransport`](../composeApp/src/commonMain/kotlin/com/jpage4500/devicemanager/transport/RemoteHttpTransport.kt)),
not a host.

## The protocol is the Swing app's

Endpoint for endpoint, message for message:
`AndroidDeviceManager/docs/SERVER_API.md` is the reference, and anything that reads a Swing server
reads this one — this app's phone, a Swing app's Connect Remote Server, `curl`. The default port is
**8765**, the token is 16 letters and digits, and a client finds it through the same
`adm://<base64 of {"host","port","token","name"}>` string, as text or as a QR code.

| | |
|---|---|
| `/api/info` `/api/devices` | name and count; every device this machine reaches itself |
| `/api/properties` `/api/setproperty` | `getprop`, and the app's own custom properties |
| `/api/execute` `/api/install` | a shell command as `{isSuccess, resultList}`; one APK on several devices |
| `/api/files/list` `/download` `/upload` | the file explorer; uploads raw or `multipart/form-data` |
| `/api/screenshot` | one PNG, after waking the screen |
| `/ws/logs` | logcat, gzipped JSON batches every 500ms, plus the pid → name map |
| `/ws/screen` | stills (PNG, or JPEG with `compress=true`) with input back up the same socket |

Where `SERVER_API.md` lists a Swing quirk under "Gotchas", this follows the documented contract
instead: `/api/install`'s ✅/❌ are per device, and **every** device-scoped endpoint refuses a serial
this machine reached through another server.

`/api/files/list` answers in both shapes at once — the Swing `DeviceFile` fields (`dateMs`,
`permissions`, `isDirectory`) and the `path`/`date`/`mode` this app's client reads. Gson ignores
what it doesn't know, so one list serves both. The client now also reads the Swing names, so this
app's phone lists folders correctly on a **Swing** server too, which it didn't before.

## Where the code is

| File | Source set | What |
|---|---|---|
| `manager/ShareServer.kt` | commonMain | State, settings, the token check, which devices are shared, the client list |
| `util/ShareHost.kt` | commonMain `expect` | start / stop / networks / host name; `supported` is false off the desktop |
| `ui/screen/ShareDialog.kt` | commonMain | The dialog and its QR window; the sidebar's **Share** row opens it |
| `server/ShareHttp.kt` | desktopMain | HTTP/1.1 + websocket over ktor-network |
| `server/ShareApi.kt` | desktopMain | The endpoints, answered from `DeviceManager` |
| `server/ShareLogStream.kt` | desktopMain | `/ws/logs` |
| `server/ShareScreenStream.kt` | desktopMain | `/ws/screen`, frame encoding (ImageIO), and the stay-awake hold |
| `util/ShareHost.desktop.kt` | desktopMain | The listener's lifecycle, and the network list with its Wi-Fi/VPN labels |

Everything answers from **`DeviceManager`**: the device list the table shows, `exec`, `logcat`,
`screenshot`, `listFiles`. So nothing about a shared device is fetched twice, and a remote client's
`/api/devices` is the desk's table, battery and all, without one extra shell command.

## Why not ktor-server

It was the obvious choice and it measured **4.5 MB of new jar** on the desktop: ktor-server-core
(0.95 MB), Typesafe Config (0.3 MB), CIO and websockets (0.17 MB) — and **kotlin-reflect (3.1 MB)**,
which nothing else in the app pulls in. That is more than the adb libraries this project turned
down for their weight.

Everything a server needs is already shipped for the client: **ktor-network**'s sockets,
**ktor-http-cio**'s `parseRequest` and `parseHttpBody`, and **ktor-websockets**' `RawWebSocket`
frame codec. `ShareHttp.kt` is the glue — accept, parse, answer, upgrade — at no jar cost, and it
is coroutines throughout, so 45 open log sockets are 45 suspended coroutines rather than 45 threads.

Two things it does itself that a framework would have:

- **Ping, pong and close.** `RawWebSocket` hands every frame up; `DefaultWebSocketSession` would
  answer the control ones, but starting it is `@InternalAPI`. So `ShareHttp` answers pings, sends
  its own every 30s, and does the close handshake — and handlers see only text messages.
- **Multipart.** Just enough to take the `file` part out of a form, which is how the Swing client
  uploads.

NanoHTTPD, which the Swing app uses, was the other candidate: small, but thread-per-connection,
Java, and unmaintained since 2016.

## Behaviours worth knowing

- **Only this machine's devices are shared.** Devices this desktop reads through another server are
  left out, so two desks sharing with each other don't advertise each other's devices back.
- **It comes back at launch.** Started once, it starts again next time with the same port and token
  (`SHARE_ENABLED`, `SHARE_PORT`, `SHARE_TOKEN`), so a phone that scanned the code keeps working.
  Stop Server is what turns that off.
- **The address is picked in the dialog**, not in a prompt per click as in Swing. Addresses are
  listed LAN-first, and the first LAN one is chosen — the only kind a phone on the same Wi-Fi can
  reach. VPN, Wi-Fi and Ethernet labels come from the interface name, and on a Mac from
  `networksetup -listallhardwareports`.
- **The server's name** is what was typed, or else the computer's own: `scutil --get ComputerName`
  on a Mac, `COMPUTERNAME` on Windows, the host name elsewhere.
- **A screen stream wakes the device and keeps it awake** (`stay_on_while_plugged_in` = 7), and
  puts the old value back when the last stream of that device closes — counted, since two clients
  can watch one device.
- **A refused websocket opens first, then closes.** A bad token or unknown device completes the
  upgrade, sends `{"type":"error"}` and closes with 1008 — the Swing behaviour its clients expect.
- **A log stream ends when the device's logcat does**, with an `error` message, rather than sitting
  open and silent. The pid → name map is re-read every 10s and sent only when it changed.
- **No TLS.** As with the Swing server, the token is the only protection: a LAN/VPN service.
