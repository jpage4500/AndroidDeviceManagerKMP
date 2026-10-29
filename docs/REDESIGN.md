# The desktop redesign

The desktop app is moving to one window: a sidebar on the left, and Devices, Files, Logs and Scripts
as pages inside it, instead of a toolbar over the device table and a separate window per device for
logs and files. Light theme first. This file is the design and the plan, and the tracker for both —
tick a task when it lands, and change a phase's status in the table.

The boards the design was settled on are on the
[Device Manager Redesign canvas](https://claude.ai/artifact/4P8RSqgjiRRpSu7gjUq5oH) (private to Joe).
Where this file and the canvas disagree, this file is the one that was decided.

**Out of scope for now:** Stats (deferred until everything else works), dark theme polish (its
palette keeps every key so it can follow), and the phone, which keeps its own design in
[MOBILE-UI.md](MOBILE-UI.md).

**Tables stay on [`DataTable`](UI-PORT.md#the-table-is-the-piece-with-no-shortcut).** It is the
FileExplorerKMP approach on `LazyColumn`, which only composes the rows on screen, so 50,000 log
lines cost what 50 do. No table library is added — none that runs on all three targets does what
`DataTable` already does, and a desktop-only `JTable` would mean a second log viewer.

## Status

| Phase | What ships | Status | Notes |
|---|---|---|---|
| 0 · Foundations | Light theme, one window with the sidebar, pages, shared tab strip | Done | |
| 1 · Devices | Filter chips, action bar, details panel, no-devices onboarding | In progress | Screen preview, select-all |
| 2 · Logs | Tabs per device, combined filters, right-click filters | In progress | All/Any editor left |
| 3 · Files | Tabs per device, path bar, places, transfers | Not started | |
| 4 · Scripts | Script list, per-device runs, results | Not started | New in KMP |
| Stats | — | Deferred | After 0–4 |

---

## The design

### The shell

```
┌────────────┬──────────────────────────────────────────────────────────────┐
│ ● ● ●      │  Devices   (All 12) (USB 5) (Wi-Fi 7)       [Search ⌘F] [⟳] [+ Connect] │
│ ▣ Device   │  ┌────────────────────────────────────────────────────────────┐ │
│   Manager  │  │ 2 selected   Mirror  Screenshot  Logs  Install …    Clear │ │
│            │  └────────────────────────────────────────────────────────────┘ │
│ ▣ Devices 12│  ┌────────────────────────────────────────────────────────────┐ │
│ ▢ Files    │  │ ☐ Name            Connection   Android  Battery  Free  …   │ │
│ ▢ Logs   ●3│  │ ☑ ● bathroom 1     ⌁ 2025…a58   13       ▓▓▓░ 64%  43.8 GB  │ │
│ ▢ Scripts  │  │ …                                                          │ │
│            │  └────────────────────────────────────────────────────────────┘ │
│ REMOTE     │  12 devices                                   Refreshed 12s ago │
│ ▢ Share  ● │                                                                 │
│ ▢ Other    │                                                                 │
│ ⚙ Settings «│                                                                │
└────────────┴──────────────────────────────────────────────────────────────┘
```

- **Sidebar**: Devices (device count), Files, Logs (how many devices are capturing, green while
  live), Scripts; a Remote group (Share devices, Other machines); Settings and the collapse chevron
  at the bottom, with the version under them.
- **Looks like EngageAdminKMP's**: wide, a light list with the open page as a raised white card, its
  icon in the brand green, and counts in pills; collapsed, a 72 dp **dark** rail of 20 dp icons with
  their labels under them.
- **Resizable**: drag its edge between 140 and 320 dp. Under 106 — halfway between the rail and the
  minimum — it snaps to the rail, and the same drag, without letting go, brings it back out. The
  app's own icon sits at the top, and on the rail it is the expand button. Double-clicking the edge, the button by the name, or
  the logo on the rail toggles between the two. The width is remembered.
- **One window holds the pages.** Mirror, the message viewer, device details and Connect stay
  windows of their own; Logs and Files don't.

### Colors

Light is the default. The design's values, which `LightPalette` carries:

| Token | Light | Used for |
|---|---|---|
| page | `#FFFFFF` | page and table ground |
| sidebar | `#F4F5F7` | sidebar ground |
| sidebar border / border | `#E3E6EA` | sidebar edge, panel and table borders |
| nav selected | `#E4E7EB` | the sidebar item for the open page |
| tab idle | `#E2E5E9` | a tab that isn't open — darker than the board's `#EDEFF2`, since the open tab takes the toolbar grey under it |
| accent green | `#1F9D55` | the open tab's cap, checkboxes, switches |
| selected row | `#EAF6EE` | a selected table row (Devices, Files) |
| pill / pill border | `#E8F1FD` / `#B8D0F5` | a saved filter that's on |
| primary button | `#3DDC84` on `#0B0D11` | Connect, Run, Done |

Muted text is `#5B6470`, faint text `#646D79` — both pass 4.5:1 on white and on the sidebar.

### Tabs (Logs and Files)

One tab per device. The open tab is white, taller, bold, and capped with a 3 dp green bar; the rest
sit back in grey. The open tab joins the panel under it. Each tab has a status dot (live, paused,
offline) and ×; + at the end adds a device. Logs tabs also show a red count of errors that arrived
while you weren't looking. A tab keeps capturing, and keeps its folder, while another one is open.

### Devices

- Header: title, filter chips with counts (All, USB, Wi-Fi, Low battery, Needs attention — a chip
  only appears when it has a count), search (⌘F), refresh, Connect.
- Selection bar: a hint with nothing selected; with a selection, its count and Mirror, Screenshot,
  Record, Logs, Install, Type text, Terminal, Run script, Clear. Logs and Run script take the
  selection to their page.
- Name column carries a status dot: online, offline, unauthorized ("accept the prompt on the
  device" in place of the model).
- Clicking a device's name opens a 300 dp details panel: screen preview (click to mirror), six quick
  actions, facts, storage, Restart and Disconnect.
- Comfortable (two-line) or compact (one-line, 36 dp) rows — compact is for the 45-device rig.
- **No devices connected** shows three cards — USB steps, Wi-Fi pairing with the QR, another
  computer by address and token — over the adb server's status, Restart adb, and a help link. One
  device looks exactly like many.

### Logs

- Filter bar, row 1: search this tab (`text`, `tag:`, `app:`), minimum level V D I W E, auto-scroll;
  **Save as filter** appears once something is typed.
- Row 2: the filters that are on, as pills joined by AND; + Add filter; Clear filters.
- Add filter: saved filters as a checklist with their rules under each, edit, New filter. Ticking
  several combines them — a line has to pass every one.
- Filter editor: a name, "match **All / Any** of these rules", rule rows of column · condition ·
  value (is, is not, contains, does not contain, starts with, ends with), and a live count — on its
  own, and combined with the filters already on.
- Right-click a cell: Show only / Hide that value for the clicked column, New filter from this…,
  Copy value, Copy line (Time offers only the copies). Show only and Hide add a **dashed** pill that
  applies to this tab and isn't saved; clicking it saves it.
- Search, level, which filters are on and the quick filters are per tab; saved filters are shared.

### Files

- Header: Hidden files and Root switches, refresh, New folder, Upload….
- Under the tabs: Up, a clickable path, copy path, filter this folder; then the selection bar —
  Download, Copy path, Delete always, Install for an APK, Rename for exactly one item — or, with
  nothing selected, the hint that files dragged in upload into this folder.
- Places: Internal storage, Download, Camera, Screenshots, Pictures, App data, `/data/local/tmp`,
  device root, and the open device's free space.
- Table: checkbox, Name (folder or file-type icon), Size, Modified, and Permissions in root mode.
- Dropping files shows the target (folder and device); a Transfers panel lists each upload and
  download with progress, speed or state, and cancel or show-in-Finder.

### Scripts

- Scripts are the `.sh` and `.bat` files in `~/.device_manager`, the same folder the Swing app uses,
  named from the file (underscores to spaces), each with its last run's result.
- A script runs **once per device, with that device's serial as `$1`** — not once with every serial,
  as the Swing app does. That is what gives each device its own result.
- Runs on: **All N | Choose…**. All is the default — every online device, with a note counting the
  offline and unauthorized ones it skips. Choose… shows device chips, starting from the Devices
  selection; removing the last chip goes back to All.
- Read-only listing with line numbers; Open in editor, Show folder, New script.
- Results: a row per device — running, done or failed, exit code, time, last line — and that device's
  full output under the table. Run again on failed.

---

## The plan

### Phase 0 — Foundations

- [x] **Light palette from the design** — the shell's tokens in `LightPalette`, matching keys in
  `DarkPalette`.
- [x] **Light by default** — `AppPref.THEME` (light / dark / system), default light; today the theme
  followed the OS.
- [x] **App shell** — `ui/shell/AppShell.kt`: sidebar and page. The desktop branch of `App.kt` calls
  it; the phone path is untouched.
- [x] **Sidebar** — Devices, Files, Logs with their counts; Settings at the bottom. Scripts and the
  Remote group join when their pages exist.
- [x] **Resizable sidebar** — 140–320 dp, snaps to a 72 dp dark rail under 106 and back out in the same
  drag; double-click, the collapse button or the rail's logo toggles; width in `AppPref.SIDEBAR_WIDTH`.
- [x] **Pages** — `manager/PageManager.kt`: a `Page` enum, a `ShellState` per window, and
  `PageManager` routing "logs for this device" to the window it belongs in.
- [x] **Windows become pages** — `WindowManager.openLogs` / `openFiles` open a tab in the shell;
  the Logs and Files windows are gone.
- [x] **Device tab strip** — `ui/components/DeviceTabStrip.kt`, used by Logs and Files. Tabs keep
  their stream and their folder in the background.
- [x] **Selection bar, chips, primary and outlined buttons** — with Phase 1, their first user
  (`ui/screen/DeviceHeader.kt`). `FilterPill` and `SegmentedToggle` wait for Logs and Scripts.
- [x] **Docs and strings** — this file, [UI-PORT.md](UI-PORT.md)'s shell section, `strings.xml`.

### Phase 1 — Devices

- [x] Header with filter chips, search, refresh, Connect.
- [x] Selection bar — Mirror, Screenshot, Logs, Files, Install, Type text, Commands, Details; Logs
  opens a tab per selected device. (Record waits for a screen-record action; Run script for Phase 4.)
- [x] Status dot in the Name column, model under the name; unauthorized says what to do.
- [x] Selected rows are a green tint under their own text (every `DataTable`).
- [x] Details panel — opened from the selection bar's Details; follows the lead selection while open.
  Six quick actions, facts, storage bar, Restart, Disconnect (wireless), All details… for the window.
- [ ] The panel's screen preview (a still from the mirror) — needs a one-shot screencap that isn't
  the mirror's poll.
- [x] Comfortable / compact rows — `AppPref.DEVICES_COMPACT`, in Settings › Device list.
- [x] No-devices onboarding — USB steps, Wi-Fi and another computer (both open Connect), adb server.
  The pairing QR stays in the Connect window rather than being drawn here as well.
- [ ] Status bar: count, shown, last refreshed — today's bar kept for now (version, memory, history).
- [x] A tick box in each device's Name cell — adds or removes the row from the selection, like ⌘-click.
- [ ] Select-all in the header — needs a header slot in `DataTable`; ⌘A does it today.
- [x] The table looks like EngageAdminKMP's — pale 38dp header, 12sp semibold titles, chevron sort,
  13sp rows, full-width stripes and row rules, no tiled background (every `DataTable`).
- [x] Remove the toolbar — every action has a new home: Connect and refresh in the header, Settings
  in the sidebar, the rest in the selection bar and the row's menu.

### Phase 2 — Logs

- [x] Per-tab state: search, level, filters on, quick filters, scroll, auto-scroll (`LogsView`).
- [x] Error badge on background tabs (`LogsSource.errors`).
- [x] Filter bar — filter box, pills joined by AND, + Add filter, Clear filters — replacing the
  filter list down the left; the level buttons are in the toolbar.
- [x] Add filter checklist; New filter… opens today's editor.
- [ ] The editor's All / Any and its live count.
- [ ] `matchAny: Boolean = false` on `LogFilter` — `terms.any` when set; saved `&&` expressions read
  as they are.
- [x] Level buttons replace the built-in "Log Level …+" filters. (Nothing to migrate: which filters
  were on was never saved.)
- [x] Right-click Show only / Hide / New filter from this… on tag and app; level floor on level.
  Quick filters are dashed pills; clicking one saves it.
- [ ] Measure heap with 5 and 10 tabs at `LOGS_MAX_LINES`; cap background tabs if it needs it.
- [ ] Profile `DataTable`'s per-batch key rebuild (`rows.map(rowKey)`) on a busy device; move the
  key list into `LogsSource` only if it shows up.

### Phase 3 — Files

- [ ] Path bar, places, free space.
- [ ] Table with file-type icons; Permissions in root mode.
- [ ] Selection bar; New folder and Rename (`mkdir`, `mv`) behind `DeviceTransport`.
- [ ] Hidden files and Root switches; root through `su -c`, disabled where it doesn't work.
- [ ] Streaming `pull` / `push` with progress — `AdbSync` already chunks; today's `pullFile` /
  `pushFile` hold the whole file in a `ByteArray`.
- [ ] Drag-and-drop upload into the open folder (`FileDropTarget`), Upload… picker.
- [ ] Transfers panel.

### Phase 4 — Scripts

- [ ] `expect` `ScriptRunner` — host process, streamed output, exit code; desktop `actual`, the page
  hidden on Android and iOS.
- [ ] The app's adb on the script's `PATH`.
- [ ] Script list, read-only view, Open in editor, Show folder, New script (a starter template).
- [ ] Runs on: All N | Choose…; per-device runs in parallel, capped (8 to start, tuned on the rig).
- [ ] Results table, per-device output, Run again on failed; last run kept per script in
  `StorageManager`.
- [ ] Run script from the Devices selection bar.

---

## Open decisions

- [ ] Do Logs or Files ever open as a window again (a second monitor)? The plan says no; a "pop out"
  could come later.
- [ ] Does double-clicking a device open a full device page, or is the details panel enough?
- [ ] How many log tabs at once? Decide after the Phase 2 heap measurement.
- [ ] How many scripts run at once? 8 to start.

## Risks

- **Memory with many log tabs** — each can hold 50,000 lines plus the hold budget. Phase 2 measures it.
- **Toolbar actions going missing** — the toolbar only goes once each action has a new home.
- **Scripts written for the Swing app** expect every serial at once. The Scripts page says so once,
  and New script offers a template that loops over `$@` for anyone who wants the old behaviour.
- **Root varies by device** — detected once per device; the switch is disabled where it fails.

## Done means

1. It compiles on every target it touches — desktop, Android and iOS for anything in `commonMain`.
2. `CHANGES.txt` has its line.
3. The `docs/` file for the area says what is true now.
4. It comes with a what-to-test list, and says which parts weren't verified.
