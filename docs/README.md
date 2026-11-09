# docs

| File | What's in it |
|---|---|
| [ADB-TRANSPORTS.md](ADB-TRANSPORTS.md) | **Read this first.** The research this project was started from: the two adb protocols, why USB pins the desktop to the adb server, and the three routes a phone can take to a device |
| [PROJECT-SETUP.md](PROJECT-SETUP.md) | How the project is put together — modules, source sets, versions, and the build settings that aren't obvious |
| [APP-SERVICES.md](APP-SERVICES.md) | The layer under the app — settings, files, logs, crash reporting, http — and which parts of it are written once instead of three times |
| [PORT-PLAN.md](PORT-PLAN.md) | What has been ported from the Swing app so far, what's next, and what deliberately isn't coming across |
| [UI-PORT.md](UI-PORT.md) | How the Swing screens become Compose ones on the **desktop** — the device table, the windows beyond it, and the tray menu |
| [SHARE-SERVER.md](SHARE-SERVER.md) | Share Devices — the desktop serving its devices to the phone and to other desks over the Swing app's HTTP API, and why that server is hand-built on ktor's parts rather than ktor-server |
| [MOBILE-UI.md](MOBILE-UI.md) | The **phone** app — the device list, the detail sheet, the three ways to connect a device, and what happens to a connection when the app is backgrounded |
| [RELEASING.md](RELEASING.md) | How the desktop app ships — jDeploy, the public release repo, the CI workflows and the one secret they need |
| [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md) | What's still missing before the first public release — the decisions, the release repo, auto-update on desktop and Android — to delete once it's out |
| [WINDOWS.md](WINDOWS.md) | More than one window on the desktop, and what "native look and feel" can and can't mean on this stack |

These are working documents, not a manual. When something here stops describing the code, fix it or
delete it — a note that describes the code as it was is worse than no note.
