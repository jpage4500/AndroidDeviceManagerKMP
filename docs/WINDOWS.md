# Windows, and what "native" can mean here

## More than one window

`manager/WindowManager.kt` holds the list; `desktopMain/main.kt` draws one real OS window per entry.
Opening a window is a state change and nothing more, so a menu item, a shortcut or a double-clicked
device row can all call `openWindow` without knowing what a window is made of. Ported from
FileExplorerKMP, where the same design carries tab tear-off.

Two things follow from that shape and are easy to lose:

- **Closing the last window quits.** There is nothing left to draw either way, and a macOS app that
  stays running with no windows needs a dock menu to get back — which this doesn't have yet.
- **A new window cascades off the one it came from.** Stacked exactly on top it looks like nothing
  happened. `WindowManager.recordBounds` is fed from a `snapshotFlow` on every frame of a drag and
  writes nothing to disk; persistence needs a settings store first.

`openMirror` raises an already-open mirror rather than opening a second window on the same device.
Any future window kind should do the same — a command that silently does nothing because the window
is behind another one reads as a dead shortcut.

## What isn't built yet

- **Window bounds aren't persisted** between runs. Needs the settings store from
  [PORT-PLAN.md](PORT-PLAN.md).
- **No drag and drop.** Compose Desktop's `dragAndDropSource` / `dragAndDropTarget` are backed by AWT
  DnD, so drags to and from Finder work for real — an APK dropped on a device row should install, and
  anything else should copy to `/sdcard/Download`. The Swing app already accepts apk/xapk dropped on
  the app icon; that route is `Desktop.setOpenFileHandler`.
- **No dialogs.** FileExplorerKMP's `DetachedWindow` is the pattern worth copying if they should be
  movable, real top-level windows rather than in-window overlays.
- **The menu bar is three menus.** File ▸ New Window ⌘N / Mirror ⌘M / Save Log… ⌘S / Close ⌘W,
  View ▸ Devices ⌘1 / Files ⌘2 / Logs ⌘3 (the page as it was left), and Device ▸ View Logs /
  Browse Files / Take Screenshot ⌘S / Type Text… ⌘I (the Input page). ⌘F is not a menu item: each
  page with a search box (Devices, Files, Logs) takes it to focus that box. The Device items act on the device list's selection — the ready
  devices, as the selection bar does — or, in a mirror window, on that window's device; they work
  from any window and any page. ⌘W on Logs or Files closes the open tab first, and the window only
  once that page has no tabs left (or on any other page).
- **⌘S follows the page.** On Devices (and in a mirror window) it is Take Screenshot; on Logs it is
  Save Log…, which opens the open tab's save dialog; anywhere else it does nothing. A menu item that
  holds a shortcut takes it on every page, so each holds it only while its page is up —
  `PageManager.activePage`, the page of the window last in front.

## Native look and feel — what's actually available

Compose draws its own pixels with Skia on every target. A button is a Skia-drawn rectangle, never an
`NSButton`, and no build setting changes that. Compose Multiplatform's *native* macOS target swaps
the JVM for a Kotlin/Native binary; it does not swap Skia for AppKit, so it would cost the whole
platform layer and the app would look identical. Don't pursue it. (Assessed at length in
`FileExplorerKMP/docs/MACOS-NATIVE.md` against Compose Multiplatform 1.11.x.)

What genuinely moves the needle on this stack, in order of native-feel per unit of effort:

1. **Native file dialogs** — `java.awt.FileDialog` maps to a real `NSOpenPanel` / `NSSavePanel`.
   Nearly free.
2. **`apple.awt.application.appearance = "system"`** so the traffic lights, the screen menu bar and
   the AWT dialogs follow the OS. Already set in `main.kt`.
3. **OS drag and drop**, as above — the AWT backing is what makes a drag to Finder real.
4. **Follow the system accent colour** — `defaults read -g AppleHighlightColor`. A hardcoded accent
   is the clearest tell that a window is a repaint rather than a native app.
5. **Vibrancy behind a sidebar** — needs JNI down to `NSVisualEffectView`. Highest effort here, and
   the single change that most alters the impression.

Not fixable at any effort on this stack: VoiceOver support, text rasterisation (Skia, not CoreText),
rubber-band overscroll, and traffic-light alignment. If native fidelity ever outranks cross-platform
reach, the architecture that delivers it is KMP for shared logic plus SwiftUI for the macOS UI — and
this codebase is already shaped for that split, since `data/`, `adb/`, `transport/` and `manager/`
are pure logic and only `ui/` would be rewritten.
