# Porting the UI

How the Swing app's screens become Compose ones, starting with the device list.
[PORT-PLAN.md](PORT-PLAN.md) covers what has already come across;
[ADB-TRANSPORTS.md](ADB-TRANSPORTS.md) covers how devices are reached.

**This is the desktop.** The phone is a different screen rather than the same one at a narrower
width — the desktop is a data table someone watches 45 rows in, the phone is a list someone taps one
row in — and it has [MOBILE-UI.md](MOBILE-UI.md) to itself. They share the model, the transports and
the actions, and almost none of the layout.

---

## The desktop device screen

What's on screen today, from `ui/DeviceScreen.java` (1235 lines) and `table/DeviceTableModel.java`:

```
┌──────────────────────────────────────────────────────────────────────────┐
│ [+ Connect] [Browse] [View Logs] [Input] │ [Mirror] [Screenshot]         │  toolbar
│ [Install] │ [Stats] [ADB] [Scripts]        …  [Refresh][Share][Logs][⚙]  │  (icon + label)
├──────────────────────────────────────────────────────────────────────────┤
│ Name ▲    │ Serial      │ Model   │ Battery │ OS │ Custom 1 │ Free │ PM  │  sortable header
├──────────────────────────────────────────────────────────────────────────┤
│ 🤖 bathroom 2   adb-2025…  T26-EEA   [▓▓▓]⚡  13   HD+        42.8G      │  alternating rows
│ 🤖 front door…  192.168…   Fire HD10 [▓▓▓]   7.1  HD+        21.7G      │
├──────────────────────────────────────────────────────────────────────────┤
│ ⟳ v1.0.0  🖫 17.2M              History                        total: 7  │  status bar
└──────────────────────────────────────────────────────────────────────────┘
```

Three regions, each its own composable:

**Toolbar** — icon-above-label buttons, grouped by separators, with a right-aligned trailing group.
Which buttons show is user-configurable (`ToolbarButton.hideByDefault()`, a right-click "Manage
Toolbar"). Most act on the *selected* device, so they're disabled with none selected.

**Table** — the hard part, see below.

**Status bar** — refresh state and version on the left, memory used, a "History" affordance in the
centre, device count on the right.

### The table is the piece with no shortcut

Compose has no data table, and neither does any library worth adding. **FileExplorerKMP builds its
file list by hand on `LazyColumn` + `LazyListState`**, and that is what `ui/components/DataTable.kt`
is ported from — it is the same problem, and every awkward detail there was already paid for once.

The details that matter, each of which is a bug if you get it wrong:

| Detail | Why |
|---|---|
| **`onKeyEvent` before `focusable()`** | key events dispatch to the focused node and bubble **up** through its parents. A handler placed after `focusable()` sits *below* the focus target and never sees a single press |
| **Hand-rolled press handling, not `combinedClickable`** | `combinedClickable` defers the single click by the double-tap timeout. That delay is exactly what makes a list feel laggy to select in |
| **One drag gesture for the whole header, on a node that never moves** | see below |
| **A press on an already-selected row doesn't collapse until release** | otherwise dragging a group of five rows would carry only the one under the cursor. Every native file manager does this |
| **Stripes painted by the container, not per row** | `Modifier.drawBehind` anchored to `firstVisibleItemIndex`, so they carry on past the last row to the bottom of the viewport and don't walk as the list scrolls |
| **`isRealPress`** | Compose invents a `Press` for a pointer that entered the window already held down (`SyntheticEventSender.sendMissingPresses`). Dragging a mirror window over the table would otherwise select whatever it passed over. Only the platform can tell the two apart, so it's an `expect val` |
| **Selection as an immutable `Set` + anchor + lead** | `key in selection` is O(1), asked once per visible row per frame. The *anchor* is where a shift-range starts; the *lead* is the keyboard cursor |
| **Reveal-on-move scrolls only when the row is off-screen** | scrolling a row that is already visible makes arrow keys feel like they jump. And it is a `requestScrollToItem`, not a `scrollToItem`: the suspending one sets `isScrollInProgress`, which reads as a **gesture** to anything watching for one — the log viewer decides Auto Scroll that way, and moving the selection with the keyboard is not a gesture |

What the table does today:

- **Keyboard:** ↑/↓, Shift+↑/↓ to extend, **⌘↑/⌘↓** to the ends, **⌥↑/⌥↓** by a page, Home/End,
  PageUp/PageDown, ⌘/Ctrl+A, Escape, Enter to activate, and type-to-select with a 900 ms buffer
  (typing `gal` jumps to the Galaxy). The modified arrows are matched **before** the plain ones in
  `handleKey`, or ⌘↓ moves the selection one row and the jump never happens. All of it is the table's
  own, in one place: the log viewer had its own ⌘↑/⌘↓ that scrolled the *view* instead, so the same
  keys did two different things in two windows.
- **Mouse:** click replaces, ⌘/Ctrl-click toggles, Shift-click extends from the anchor, double-click
  activates, right-click opens a menu at the pointer and keeps an existing multi-selection.
- **Columns:** click to sort with a ▲/▼ indicator, drag the trailing edge to resize (with an E-resize
  cursor on desktop), drag a header to reorder, right-click a header to hide/show/reset. Order,
  widths, hidden set and sort all persist through `SettingsManager` — the **width** write through a
  `Debouncer`, because `draggable` reports every delta of a resize and a settings write rewrites the
  whole properties file on the calling thread. The in-memory width still changes per pixel; only the
  file waits for the drag to stop.
- **Tooltips:** rest the pointer on a cell and it answers with the value in full — but only when the
  value didn't fit, which is what makes them worth leaving on. Off with **Show Tooltips** in
  Settings.
- **Chrome:** alternating stripes, a selection that greys out when the window loses focus, and a real
  desktop `VerticalScrollbar`/`HorizontalScrollbar` behind an `expect` (a no-op on mobile, where the
  platform draws its own).

**Columns reorder by dragging a header, and the whole column comes with it.** Header *and* cells
travel with the pointer and draw over their neighbours, which is what a JTable does
(`BasicTableUI.paintDraggedArea`) and what makes it obvious which column is moving — see
**Dragging a column** below.

### Context menus

`ui/components/AppPopupMenu.kt`, ported from FileExplorerKMP's `AppMenu`. **Not Material3's
`DropdownMenu`**, for two reasons that can't be worked around from outside it: it forces a 48dp
minimum item height through an internal `sizeIn` applied *after* the caller's modifier — so it can't
be shrunk to the ~24dp a desktop menu uses — and it highlights with a ripple rather than the inset
rounded pill the platform draws.

**A menu has to be composed inside the thing that was clicked.** `Popup` positions against its
anchor, which is whatever layout node it sits in — so a menu placed in the table's container and
offset by a position measured within a *row* opens at the top of the table, which is exactly the bug
this replaced. `DataTable` therefore takes the menu as a **slot** (`rowMenu`, `headerMenu`) and
composes it inside the row or header cell that raised it.

Two other details worth keeping:

- **Off-screen handling.** Past the right edge the menu is pulled back flush; past the bottom it
  flips to sit *above* the pointer, rather than letting its last item fall off.
- **Focus comes back.** A context menu is a focusable `Popup`, so opening one takes the keyboard away
  from the table and closing it does not hand it back — the arrow keys would silently stop working
  until the next click. Every menu re-requests the table's focus on dismiss, through a
  `LocalTableFocus`.

**Rubber-band selection** works over empty space. A press on a row consumes its own gesture, so only
a drag that started on blank space bands — and it uses `awaitRealPress`, or dragging another window
across this one would select every row it passed.

### A menu can be about the column it was opened on

`rowMenu` is handed the column the right-click landed on, worked out from the press x and the widths
by the same `columnAt` the header uses — they have to agree, or a menu would name a different column
than the one under the pointer. That is what "Copy Field" means by *field* (the Swing menu copies the
cell you clicked, not the row's name), and it is why the log viewer's **Filter…** item appears once,
on the tag column or the level column, rather than twice on every cell.

### Dragging a column

**A JTable moves the whole column**, not its header: the header and every one of its cells travel
with the pointer and draw over their neighbours, while the columns being passed shift underneath.
`DataTable` does the same, through a `ColumnDrag` holder the header writes and every row reads.

Three details make that cheap enough to do sixty times a second on a 45-row table:

| Detail | Why |
|---|---|
| **The dragged column is tracked by *identity*, not index** | the order changes mid-drag, and an index would point at whichever column had just moved into the slot |
| **The travel is only ever read inside a placement lambda** (`Modifier.offset { … }`) | it changes every frame. Read while composing, one drag would recompose every visible cell as fast as the mouse reports — in a placement lambda only the dragged cells are re-*placed*, and the ones standing still don't even read it, because `column == drag.column` short-circuits first |
| **`zIndex` and the background are read while composing** | they change twice a drag — when it starts and when it ends — so the cost lands where it belongs |

A dragged cell paints its own row's colour under itself (`bandColor`, or the selection) and rules its
two edges, because it is passing *over* cells that would otherwise show through it. The stripes are
painted by the container, so a moving cell has to repaint the band it is in. The header goes darker
(`headerDragged`) rather than picking up a drop shadow — a JTable's is pressed, and a shadow here
falls on the first row.

**The swap happens when the dragged column's centre passes the neighbour's centre**, not when it
enters the neighbour's slot. That distinction is the whole trick: entering-the-slot oscillates,
because after the swap the pointer is immediately back inside the slot it came from. Comparing
centres leaves a gap of the dragged column's own width behind it, which is hysteresis for free. The
comparison is looped, so one fast flick walks a column several places instead of lagging a step
behind the cursor.

The first version of this was broken two ways, and both are worth writing down because they are easy
to walk back into:

1. **A per-cell `pointerInput` keyed on `columns`.** A reorder changes that list, which restarts the
   modifier and cancels the gesture — so the drag died after exactly one swap.
2. **Measuring the drag against a node that `Modifier.offset` was moving.** `positionChange()` is in
   the node's own coordinates, so moving the header under the pointer counted as pointer movement:
   a feedback loop, and the column ran away from the cursor.

Both go away with **one gesture on the header row**, which never moves, keyed on `Unit` and reading
the column list through `rememberUpdatedState`. The gesture also keeps its **own copy of the order**:
`onReorder` writes a `StateFlow` and the composable reading it has not recomposed yet, so doing the
next frame's arithmetic against `columns` would use the pre-swap list and make the header fight the
cursor. Sort, reorder and the context menu are all that one gesture, because `clickable` +
`detectDragGestures` fight over the same press — the click fires on release whether or not the column
moved, so every reorder also re-sorted the table.

### Tooltips

A cell answers when the pointer rests on it for 600ms — Swing's `ToolTipManager` waits 750, and a
table is scanned rather than read, so the pause *is* the feature: without it every cell between here
and where you were going pops a bubble.

`DataTable` takes one `tooltip(row, column, clipped)` lambda and wires it to every cell. **Null turns
the whole thing off and costs nothing per cell** — no wrapper, no gesture node — which is what the
**Show Tooltips** setting sets it to.

The `clipped` argument is the interesting half, and it is the Swing table's `getTextIfTruncated`
question: *only tell me the values that didn't fit*. `TableText` already knows — `onTextLayout`
reports `hasVisualOverflow` — so it writes the fact into a `LocalCellClipped` that `CellTooltip`
reads once the pause elapses. Nothing observes that state while composing, so the write during
layout recomposes nothing. A column whose cell **can't** show its value at any width ignores
`clipped` and answers regardless: Battery is a bar with no number on it, and Booted shows when a
device came up rather than how long it has been up.

The bubble is placed where the pointer was when the pause elapsed and then stays put — a tooltip that
follows the cursor is a tooltip you can't read — and its `Popup` is **not** focusable, or it would
take the keyboard and stop the table's arrow keys dead. The log viewer keeps its own pinned panel
instead (`MessagePanel`): its messages are too long for a bubble, and one covering the rows you are
comparing against is worse than none.

### Colours

The palette is FileExplorerKMP's (`ui/theme/Theme.kt`) with the **table** greys taken from the Swing
app instead, so the two look like the same product side by side: alternate row `#F6F6F6`, and a
header at `#C5C5C5` — deliberately much darker than a Material one, which is what makes the column
titles read as a *bar* rather than as another row. The device-state colours are the Swing app's too
(`#188600` online, `#808080` offline, `#FB6D08` busy, `#FBF708` not-ready).

That last one is unreadable as a thin bar on white, so the battery gauge's amber band uses `busy`
rather than `unauthorized`.

Text is **16sp** in both the header and the cells, and rows are 28dp. 16 is the Swing table's own
size: `MainApplication.initializeUI` hands FlatLaf `Arial PLAIN 16` as the app's `defaultFont` and
the table inherits it, header included — measured off a screenshot of it, the glyph advances come
back at 16px to within a rounding error. `Fonts.table` names the face as the platform's sans rather
than leaving it to Material, which is free to be the system *UI* font (SF on macOS, wider at the same
size); the platform sans is Arial on Windows, Helvetica on macOS and Liberation Sans on Linux, and
all three are Arial's metrics.

The size is set through `tableTextStyle`, not by overriding `fontSize` on a Material style, because
that keeps the style's **line height** — `bodySmall` is 16sp — and a 16sp glyph in a line box exactly
its own size loses its descenders. Unspecified hands the line height back to the font.

Two more things about the colours are worth keeping rather than re-deriving:

- **`rowStripe` is a few percent off `listBg` — darker on a light ground and *lighter* on a dark
  one.** It can't be produced by inverting; the gap that reads right on white is invisible on black.
- **The scrollbar thumbs flip ground**, black at low alpha on light and white at low alpha on dark,
  and at a *lower* alpha there — a light thumb reads heavier than a dark one at the same opacity.

Stripes are drawn as **inset rounded rects** (6dp in, 4dp radius) rather than full-bleed bands, which
is what makes them read as a list rather than as table rules.

The Swing app's `background.png` is tiled behind the window and behind the table at 10% alpha
(`Modifier.watermark()`) — **tiled at its own size, never stretched**, because it is a repeating
pattern and scaling it to the window would make it a different size in every window. That faint green
wash under the rows is where the table's colour actually comes from; the row greys themselves are
neutral.

It is a table's own choice: `DataTable(showBackground = …)`, on everywhere but the **log table**. At
30,000 dense lines the wash is one more thing between the eye and the text, and the pattern shows
through every un-banded row. The log window has no window-level watermark to fall back to, so
turning it off there leaves the table flat.

The device-state colours (online / offline / unauthorized / error) stay fixed values: they mean
something regardless of appearance, and both read on either ground. Everything else comes from
`LocalPalette`, so it follows the OS.

### The window has no title bar

On macOS the device-list window sets `apple.awt.fullWindowContent` and `apple.awt.transparentTitleBar`,
so the content starts at the top of the frame and the toolbar sits beside the traffic lights — the
Finder layout. Three consequences, all handled:

- The toolbar's leading edge is padded by `windowChromeInsetStart()` (78dp on macOS, 0 elsewhere) so
  the first button clears the traffic lights.
- The window title is set to `""`, or it would draw over the toolbar. The name in the menu bar and
  the Dock comes from `apple.awt.application.name` instead.
- **The toolbar has to move the window**, since there is no title bar left to drag. `windowDragHandle()`
  tracks the pointer in *screen* coordinates rather than accumulating the drag delta — moving the
  window moves the toolbar under the cursor too, and a delta-based version fights itself and creeps
  away. Double-click zooms, as a title bar does.

**Mirror windows keep their title bar.** They have nothing at the top but the device's screen, the
traffic lights would sit on top of it, and the title is the device's name — which is worth reading.
What they do have at the *foot* is the same bar the phone's mirror has: Back, Home and a keyboard key
as icons, More holding Recents, Power and the three quality levels, and the measured frame rate at
the right. It is one composable for both, described in [MOBILE-UI.md](MOBILE-UI.md) — including what
the keyboard key does on a desktop, which is to point the physical one at the device, and how the
wheel zooms and pans a frame that has no pinch to give it.
Windows and Linux keep an ordinary title bar everywhere for now; going without one there means
drawing your own close/minimise/maximise buttons.

### Device details

Everything in the table past the model comes from `data/DeviceDetails.kt`, ported from the Swing
app's `fetchDeviceDetails`: nickname, phone, IMEI, free space, battery level and charging source.
The parsers are pure functions; `DeviceManager` runs the commands through the connection each row
came in on, so both the adb and HTTP routes get them — and a table holding two desks' devices asks
each device's own desk.

| Value | Command |
|---|---|
| nickname | `settings get global device_name` |
| phone | `service call iphonesubinfo 15 …`, falling back to `12` |
| IMEI | `service call iphonesubinfo 1 …` |
| free space | `df /data` |
| battery + power source | `dumpsys battery` |
| custom labels | `cat /sdcard/android_device_manager.properties` |
| boot time | `cat /proc/uptime` |

**Everything is batched into one round trip per phase, and devices run in parallel.** Every `exec:`
opens a socket to the adb server, so six commands across 45 devices was 270 of them, run one after
another — the table took many seconds to fill in and said nothing about why. Batched with an `echo`
marker between the commands, and with 12 devices in flight at once, a fleet is two round trips deep.
`fetchDetails` logs its device count and elapsed time, and each device logs its own.

Three more things worth keeping from the original:

- **`service call` returns a Parcel dump, not a string.** The value is UTF-16 in the quoted column,
  so every other character is a NUL printing as `.`; taking the digits out of each quoted run and
  joining them is what reassembles it. A tablet or a TV has no `iphonesubinfo` service at all, which
  is not a failure.
- **The fetch is split in two.** The fixed half (properties, nickname, phone, IMEI) runs once per
  device and never again while it stays plugged in; only battery and free space are re-read on the
  `DETAILS_REFRESH_SECS` timer. Re-reading a phone number every minute across 45 devices is 45
  pointless round trips.
- **A failed read keeps the last good value** rather than blanking the cell — the number was true
  when it was read, and an empty cell reads as "unknown" instead of "stale".

The labels a person types — device name, phone, Custom 1/2 — are written **to the device**, not to
this app's settings: `settings put global device_name` for the name, and the same
`/sdcard/android_device_manager.properties` file the Swing app uses for the rest. A label belongs to
the phone, not to whichever desk it is plugged into, and the two apps read each other's.

`PowerStatus` now names the *source* (`AC`/`USB`/`WIRELESS`/`DOCK`/`NONE`) rather than
charging/discharging, which is what the Swing app tracks: a phone on a dock and a phone on USB behave
differently on a bench.

### Dialogs

**There are two families, and new work belongs in the second one.**

`BaseDialog` (`ui/dialog/`) is the one ported from DashboardWeatherKMP. Around it sit the generic
dialogs in `ui/dialog/common/` — `ConfirmationDialog`, `TextInputDialog`, `OptionsDialog` (radio
buttons), `MultiSelectOptionsDialog` (checkboxes), `SliderDialog`, `StepperDialog` — and the rows in
`ui/setting/` that open them: `SettingItem` and its slider, stepper and switch variants, under a
`SectionHeader` or a `CollapsibleSection` when a form has enough of them to want folding. **A new dialog goes here.** `ConfirmDialog` / `InputDialog` are
thin request-shaped wrappers over the first two, so every existing `ConfirmRequest` and
`InputRequest` call site draws the new one without knowing.

`AppDialog` (`ui/components/`) is the older desktop-only panel and is what Command, Logs and Send
Input still use. It is a plain panel with the buttons bottom-right where a desktop puts them, inside
a real OS window.

**Nothing in either family uses Material3's `AlertDialog`.** It draws a 28dp-radius floating card
with 24dp padding and pill buttons — a phone dialog on a desktop — and it doesn't answer Enter or
Escape. `AppTextField` replaces `OutlinedTextField` for the same reason (floating label, 56dp
minimum height, Material's focus animation), and `AppCheckbox` replaces `Checkbox`, which is 48dp of
touch target.

**The same `BaseDialog` is two different things on the two platforms.** Everything about that comes
from one `expect val` — `dialogsAreWindows` in `DialogContainer.kt` — and it is the difference that
matters most, because a dialog is now the app's main way of showing a form:

| | desktop | phone |
|---|---|---|
| what it is | a real OS window | an overlay over the app |
| heading | the window's own title bar | a coloured title band with a close X |
| subtitle | — (one line is all a title bar has) | a dimmer second line inside the same band |
| shape | flat, square, a fixed `dialogWindowWidth` | rounded card, fills the width |
| buttons | bottom-right, natural width | split half and half |
| moving it | drag it anywhere, off the app if you like | nowhere to move to |
| a second dialog | its own window; the first stays up | replaces the first, which comes back after |
| Escape / Enter | the window handles them | the card handles them |

Three things make that work:

- **`isTopDialog()`** (`ui/dialog/DialogStack.kt`) — only consulted where dialogs are *not* windows.
  On a phone a dialog opened from another is a step deeper into one flow, not a second thing on
  screen, so only the innermost draws; the levels behind stay composed and keep their scroll
  position. On the desktop every level gets a window and they all stay up, which is what a window
  manager is for. Both paths call it, because the registration is what keeps the stack honest.
- **The card takes focus, but yields it.** On the overlay path `BaseDialog` requests focus on its own
  card so Enter and Escape work in a dialog with nothing focusable in it. That request is declared
  *before* the content, so a field inside that asks for focus runs later and wins — get the order
  wrong and the soft keyboard never comes up on a phone. The window path doesn't need it: the
  desktop container answers both keys itself.
- **The overlay measures inside `safeDrawingPadding()`.** That is the union of the system bars, the
  cutout *and* the keyboard, so it is both what keeps a tall dialog's title out from under the clock
  and what keeps a `TextInputDialog`'s field above the soft keyboard — the dialog's measured height
  shrinks when the keyboard opens rather than the field sliding under it.

**Settings is a drill-down, not a page.** `SettingsDialog` is five rows — Connection, Device list,
Logs, App, About — each opening a dialog of its own, and a row in *those* opens the control that
fits what it holds. It was one long scroll of every pref (the Swing app's shape, and this app's until
now); it broke on the phone, where the same list is a page and a half of scrolling with no room for
the section headings that made it readable. There is no `SettingsScreen` and no `WindowKind.SETTINGS`
any more: on the desktop the dialog *is* the window.

**Settings rows are sized twice.** `SettingItem`, `SettingSwitchItem`, `SettingSliderItem` and
`SettingStepperItem` read `LocalCompactUi` (provided by `App(compact)`) through `Dimens`. The
weather app's row is a 26dp icon and a 20sp label read at arm's length; the same row in a 560dp
desktop form window is cartoonish, and the desktop is the workload that matters. The value on the
right is red on purpose and is the one place in the app that uses red for something that isn't
wrong — in a list of thirty rows it is the only part anyone scans.

**`DialogContainer` is the `expect` under both families.** The desktop actual is a `DialogWindow`
sized to its content (`DpSize.Unspecified`), which is what the Swing app's `JDialog`s were; a phone
gets an overlay, because a phone has no windows. `AppDialog` draws its own heading only when the
container has none (`LocalDialogTitled`). Escape and Enter are handled by the *window* as well as by
the panel, so the default button answers Enter in a dialog with no fields in it — anything focused
inside sees the key first and can consume it.

This has one consequence worth knowing: a `Popup` is drawn inside its own window's layer, so a menu
opened in a dialog is clipped to that dialog, which is only as tall as its form. `AppPopupMenu`
therefore caps itself to `LocalWindowInfo.containerSize` and scrolls — an eight-item picker in a
190dp dialog shows seven of them and takes the wheel.

**Add Filter is a structured editor**, not a text box: a name, then one row per term of
[field ▾] [operator ▾] [value] with a trash icon, and ⊕ to add another. The eight operators are the
Swing app's own wording (`LogFilterExpression`: is / contains / starts with / ends with and their
NOT forms). Rows are `LogTermSpec`s and are joined with `&&` into the same expression string the
sidebar, the status bar and the saved-filter file already speak, so nothing downstream knows the
dialog exists — and `LogTermSpec.parse` reads one back, which is what makes Edit work on a filter
typed by hand or written by the Swing app. Quotes go outside both the `!` and the stars
(`msg:"!*hello you*"`), because that is the order `LogTerm.parse` strips them in.

### Making it fast enough for logs

The log viewer is the reason this component is generic. `LazyColumn` only composes visible rows, so
row count itself is cheap — what isn't:

- **`rows.map(rowKey)` runs on every change to the row list.** Fine for 45 devices; at 100,000 log
  lines appending continuously it is O(n) per append. When the log viewer lands, either key on index
  or keep the key list incrementally.
- **`rangeTo` uses `indexOf`**, which is O(n) per shift-click. Cheap now; an index map is the fix.
- **Nothing per-row may collect a flow.** One `SettingsManager` read per row would mean 40 collectors
  on screen. Read once above the list and pass the value down — the same rule FileExplorerKMP follows
  for its icon setting.
- **Selection changes must not recompose every row.** They don't: `selected` is a `Boolean` derived
  from an immutable `Set`, so only the rows whose value actually changed recompose.

### The menu-bar / tray icon

A second, smaller device list: the icon shows the device count, and the menu lists every device with
four inline actions — Mirror, Browse, Logs, Screenshot — plus Open and Quit.

The Swing app gets this from **dorkbox SystemTray**, and had to force a Swing-drawn tray on macOS to
get icons at all, then hand-draw each row (`TrayUiFactory`) because a native menu can't hold buttons.
Compose Desktop has `Tray` in `androidx.compose.ui.window`, but it takes an ordinary `Menu` — text
items and separators, no per-row buttons.

So the honest options were: a submenu per device (native, plain, one extra click per action), or the
whole thing drawn in an undecorated always-on-top `Window` positioned under the icon (matches the
screenshot, costs the platform's own menu behaviour).

**Built as a submenu** (`AppTray`), for the reason above — the row-of-buttons version can replace it
later without anything above it changing. Two things it can't do that the Swing app does: no device
count drawn onto the icon (Compose's `Tray` takes a `Painter`, not a bitmap to badge), and no icon
per row. The count is in the tooltip instead.

The icon only appears while **Stay in the menu bar when closed** is on — a tray icon that can't be
dismissed is one someone has to look at forever. With it on, closing the last window leaves the app
running and the icon is how it comes back.

---

## Screens beyond the device list

`WindowManager` grew three more window kinds, all opened through `openOnce` so a second one on the
same device raises the first instead — two logcats on one device double its load for nothing, and two
mirrors halve each other's frame rate.

**Every kind of window remembers its own size**, keyed on a `WindowKind` — the content without the
device it is for, because a person sizes a log window once rather than once per serial, and a pref
holding a rect per device grows for as long as the app is used. Before this a new window inherited the
bounds of whatever opened it, so View Logs came up at the device list's size; now the size is always
its kind's (last-left-at, or the kind's default from `Dimens`) and only the *position* cascades — off
the last window of the same kind if there is one, so a second one doesn't land exactly on the first,
and otherwise off whatever opened it.

The **position is not persisted past the run**. A saved position is only meaningful on the screens
that were attached when it was saved, and one restored onto a monitor since unplugged is a window
nobody can reach. Sizes go to `AppPref.WINDOW_SIZES` as `{"LOGS":"1200x700"}` — readable in a
properties file someone opens — written through a `Debouncer`, since a resize reports every frame of
the drag. The size is recorded even while the window's position is still `Aligned`: a centred window's
position never becomes absolute unless someone drags it, and gating the report on absolute meant a
window nobody moved never remembered how big it was.

**Connect** (`ConnectScreen`) — the wireless devices this app has reached before, each with its live
state, plus a field for one it hasn't. The address field opens on the *last* one connected to with
its final octet selected, so reaching the phone next to the last one is four keystrokes. Devices are
remembered automatically: anything with a `host:port` serial that shows up in the list is added.

**Logs** (`LogsScreen`) — laid out like the Swing `ViewLogsScreen`: filters down the left with their
own search box and Add Filter pinned to the bottom, the table on the right, and Tooltip / Auto Scroll
in the bottom-right corner.

Three things carry most of its usefulness:

- **The whole row takes the level's colour** (the Swing app's exact values: blue verbose, green info,
  orange warn, red error), not just the level cell. At 30,000 lines that cell is four pixels of a
  screen you are scanning; colouring the message is what makes an error findable.
- **Auto Scroll is a mode.** It follows the tail until something the user did says stop — scrolling
  up, or picking a line. A log that yanks you back to the tail the moment you look at something is
  unusable; one that quietly stops following because a frame landed badly is worse (see below).
- **Filters are a selection, not checkboxes.** That is what lets "All Messages" be a member of the
  same list rather than a special control, and ⌘-click combines several. The five built-ins are the
  Swing app's, in its own syntax — `level:D+` as well as the `level:D*` its `LOGS.md` documents.
- **The two typed boxes are extra terms, not replacements.** "Filter…" beside the list and "Search…"
  in the toolbar both AND with whatever is selected, which is how the Swing app wires its sidebar
  field — and what makes "everything at warn and above mentioning gralloc" one step. Getting this
  backwards (narrowing the *filter list* instead of the log) was the first version's mistake.
- **The right-click menu offers one filter, and only where filtering means something.** On the tag
  column it offers that tag, on the level column that level and above, and on a message or a date it
  offers neither — which is the Swing menu's own rule (`APP`/`TID`/`LEVEL`/`TAG` and nothing else).
  Offering both at once asked which of two filters you meant, on a cell that was usually neither.
- **The message panel is fixed, not a floating tooltip.** The Swing app pins it to the top or bottom
  edge of the table at the table's own width (`MessageTooltipPanel`, light yellow on a darker
  hairline, monospaced, 100dp cap) — a bubble that follows the cursor is either too narrow to help or
  covers the rows being compared against. It appears **only for a message that is actually clipped**,
  which `TableText` reports through `onTextLayout`.
- **The App column resolves the pid** through `ps -A -o PID,NAME`, re-read on a slow timer. logcat
  only carries the pid; resolving at *display* time rather than at parse means a name that arrives
  late appears on lines already on screen. A device reached through a Device Manager server sends the
  map down the log socket itself every few seconds (`LogBatch.processes`), so `ps` doesn't run there
  at all — asking would be a shell command on someone else's machine for an answer already on its
  way.
- **⌘+ / ⌘- / ⌘0** scale the row text, and the row height follows it — a fixed row clips bigger text.
  Handled with `onPreviewKeyEvent` on the window, since the table owns the keyboard once focused.
  **⌘Enter** toggles Auto Scroll and **⌘G** walks the search hits, both from the same handler.
- **Search highlights, it doesn't filter** — yellow for every hit, orange for the one ⌘G is on. A hit
  is only useful with the lines around it, and filtering down to the hits throws those away. Only the
  sidebar box filters.
- **Typing anywhere searches.** Any printable key with no ⌘ appends to Search and moves focus there,
  and Escape clears it — scanning a log is a two-hand job and reaching for the box first is a step
  nobody should have to take. Both typed boxes report focus back up so this doesn't fire while one of
  them already has it.

### Bugs worth remembering

**An animation clock has to start on a frame, not in a draw.** The mirror's tap and swipe marks
(`MirrorFeedback`) time themselves from `withFrameMillis`, and the first version let whichever came
first — the frame loop or a draw — start the clock. A draw usually came first, and the value it
seeded from was the state's initial `0` where a real frame time is milliseconds since boot: every
animation was born a few hundred thousand seconds old, drew one invisible frame and was swept up as
finished. Nothing appeared at all, which reads as "the overlay isn't wired up" rather than as a
clock bug. Seeding happens in the frame callback now, and an unstarted mark draws nothing.

**`Modifier.pointerInput(frame)` is a gesture detector that restarts four times a second.** The
mirror decodes a new `ImageBitmap` every 250ms, so keying the handler on it tore the handler down
mid-press: a long press never survived to its threshold, and a tap or a drag that spanned a frame
was cancelled before its up ever arrived. Key it on the *dimensions* the maths needs — `Size` and
`IntSize` compare equal frame to frame — and not on the frame itself.

**Following the tail must not be a scroll.** `scrollToItem` runs one: it takes the list's scroll
mutex and sets `isScrollInProgress`, ten times a second on a busy device. That is a scroll the user
has to win a mutex against to get anywhere with the wheel — the log sat pinned to the bottom with no
way to scroll off it — and a signal indistinguishable from one of their own. It is a
**`requestScrollToItem`** now, applied during the next measure pass, in the same pass as the rows it
is following: no mutex, no `isScrollInProgress`, and never a frame where the list is behind the tail.
Collected off the page flow rather than keyed on a value in composition, so ticking the checkbox
replays and lands on the tail without the click handler scrolling for itself.

**Auto Scroll is decided by where a scroll landed, not by which way it went.** The first version read
the sign of the delta out of a `NestedScrollConnection`, which has two ways to be wrong and neither is
visible from the call site: a platform is free to sign its wheel either way, and a delta a scrollable
dispatches raw never reaches a parent connection at all. It now asks the **layout** — is the last row
still on screen — which is the Swing wheel listener's own rule, off on the way up and back on once
the last row returns. The gate is `isScrollInProgress`, and it is the whole thing: ungated, this is
the derived version that turned itself off a moment after being clicked, because a batch grows the
list and for one frame the last row is out of view. Only a scroll of the user's own sets that flag.

**A row must never change background colour.** The banding came from a row's *index*, and an index is
not a property of a row — a log dropping its oldest line, or scrolling while following the tail,
shifts every index by one and the whole table's stripes invert. `DataTable` takes a **`stripeOffset`**:
how many rows have already fallen off the front of the list, added back to the index, so the band
rises by exactly what the index fell by and stays with its row. The log passes `LogPage.dropped`,
which is why that count travels in the same value as the entries rather than in a flow of its own —
published apart, a frame could paint the new list against the old count and invert every stripe.

The entry id was the first attempt at this, and it is **wrong**: with a filter on, the ids reaching
the table are whichever lines matched, so they skip, and parity taken from a number that skips is
banding at random — plainly visible the moment a level filter is on. The bands are painted from
`visibleItemsInfo` offsets rather than from `firstVisibleItemIndex` plus a row height, and each in the
row's **own** pixel height: the log's row height follows its font size, and a fractional Dp against a
whole number of pixels leaves a hairline of the wrong colour between one band and the next.

**Auto Scroll stops following when the buffer fills**, if the effect that follows the tail is keyed
on something that stops changing. `shown.size` is exactly that: at the cap a line arrives and the
oldest goes, so the count is constant and a `LaunchedEffect(shown.size)` never runs again — Auto
Scroll quietly stopped at the point the log got long enough to need it. Collecting the page flow has
no key to get wrong.

**A trim must not move what you are reading.** `LazyColumn` re-finds its first visible row by key when
rows come and go above it, but only within about 130 rows either side (`NearestItemsExtraItemCount`),
and a busy device sheds hundreds of lines per 100ms batch — past that window the lookup misses, the
index is kept as it was, and the log jerks forward by however many lines it dropped. `LogsScreen` puts
the row that was at the top back at the top after every batch: the row comes from `layoutInfo`, which
only changes when the table lays out and so still names the row that was on screen however many
batches have queued behind a slow frame, and it is found again by a binary search on the id — landing
on the oldest surviving line when the anchor itself has been trimmed away. `requestScrollToItem`
rather than `scrollToItem`, because it needs no coroutine and it tells the list to forget its
remembered key, so this and `LazyColumn`'s own anchoring can't both move the same row. Skipped while a
drag or a fling is running: the user's own scroll outranks it.

The other half is not trimming at all while someone is reading. `LogsSource.setHold` is on for as
long as Auto Scroll is off, and while it is on the buffer may grow `LOGS_HOLD_LINES` past
`LOGS_MAX_LINES` (25,000 past 50,000 by default) rather than dropping its oldest line — so the lines
being read stay where they are and the history under them doesn't dissolve. It is a **budget, not a
promise**, and that is the point: a device printing thousands of lines a second would empty the heap
into a window nobody is scrolling, which is the reason the buffer is a ring in the first place. Two
consequences are visible and both are said out loud in the status bar — the line count reads *above*
the configured buffer size while lines are held ("holding 24,113 older lines"), and when the budget
runs out the oldest lines start moving again under a reader who is still there ("hold full — oldest
lines dropping"). Releasing the hold gives back a whole budget in one drop, which is why the only
thing that releases it is following the tail again: the jump lands where the user is already looking.

**⌘↑ / ⌘↓** reach the oldest line held and the newest — but through `DataTable`, which *selects* the
first or last row rather than scrolling the view, so the keys mean the same thing here as in the
device list and the file explorer. Selecting a line turns Auto Scroll off, so ⌘↓ lands on the newest
line and stays there instead of resuming the chase; ⌘Enter re-arms following, and so does scrolling
back to the bottom. The log viewer's `onPreviewKeyEvent` deliberately does not claim these two, and
its ⌘G jump is a `requestScrollToItem` for the same reason the table's reveal is.

It reads `logcat -v threadtime` over `exec:` through `adbLogcat` — or, for a device on a Device
Manager server, takes the batches that server pushes over `/ws/logs` through `remoteLogcat`; either
way what arrives is a `LogBatch`, and the window can't tell which. Both **batch lines rather than
emitting one at a time**: a busy device prints thousands a second, and an emission
per line would recompose the table thousands of times a second. The buffer is a ring of
`LOGS_MAX_LINES` (stretching by `LOGS_HOLD_LINES` while a window reads history — see above), not a
growing list: without a cap the app runs itself out of heap in minutes.

Filtering happens on the way to the table, not in it, so turning a filter off shows what it was
hiding without re-reading the device. The expression language is the Swing app's, from its `LOGS.md`:

```
level:D          debug only          tag:HD_*      starts with
level:D*         debug and above     tag:*HD_      ends with
app:com.test     exact               tag:!HD_*     does NOT start with
msg:"hello you"  contains            *:*hello*     any field contains
```

joined with `&&`. Anything that isn't a `field:pattern` term is treated as a bare `*:*text*`, so
typing a word into the box does the obvious thing without learning the syntax. `level:D*` is the one
special case — it isn't a prefix test but "D and everything more serious".

**Files** (`ExploreScreen`) — the device's filesystem on the same `DataTable`, which is the whole
reason that component is generic. Directories always sort before files whichever column is sorted;
no file manager does it any other way. Downloads land in the app's own `downloads` folder rather than
behind a native save dialog — a file chooser is an `expect`/`actual` per platform, and every download
here is one someone is about to look at anyway. `pullFile` is new on `DeviceTransport`; it returns
the whole file rather than a channel, which is enough until something needs to pull a video.

**Message Viewer** (`MessageScreen`) — one log line in a window of its own, opened by double-click.
The Swing toolbar: **Restore** (undo the formatting), **Wrap ON/OFF**, **Auto Format ON/OFF** (pretty-
print the JSON on open) and **Edit** (hand the text to whatever opens `.txt`). Both toggles are
remembered. `TextUtils.formatJson` is a character walk rather than parse-and-re-serialise, because
the input is a log *sentence* with a payload in the middle and the prose either side has to survive.

**Device Details** (`DeviceInfoScreen`) — the properties list plus storage and battery gauges, under
section headers rather than in cards, so the window reads like the settings forms beside it. Both
sections fold away and are remembered folded in `AppPref.INFO_COLLAPSED`, which the phone's sheet
shares — it adds a third, Commands. The rows themselves are `DeviceInfoContent`, which that sheet
drops into its own scroll; the screen is the scrolling wrapper around them, and owns the empty state
for a device that is gone — nothing can fill an unbounded height, so that check has to sit outside
the scroll. Every property row copies itself on click, with a ripple, a copy hint in the gutter and a
toast naming what it took.

**Settings** (`SettingsDialog`) — and one section with no Swing equivalent: **which transport the
device list reads from**. That app is always a client of a local adb server; this one can also point
at a server across the LAN, or at another instance's HTTP API. A remote URL wins when one is set,
because it is a deliberate choice where the adb host always has a default.
`DeviceManager.transportFromSettings()` is read once at startup and again whenever Settings changes
it — and it describes **one** of the connections the app holds, so `applySettings()` replaces that
one and leaves any Device Manager servers connected from the device list where they are.

### Installing, and files dragged in

`InstallManager` puts a local file onto several devices at once — an APK installs, anything else is
copied to `/sdcard/Download`. **`pm install` can only read a path on the device**, so an APK is
pushed to `/data/local/tmp` first and removed afterwards; `adb install` does the same thing and just
hides it. `-r -d` so a reinstall keeps its data and a downgrade is allowed, which is what makes it
useful for iterating on a build.

Three new platform seams came with it, all no-ops on mobile:

| | Desktop | Why |
|---|---|---|
| `pickLocalFiles` | `java.awt.FileDialog` | maps to a real `NSOpenPanel` on macOS; `JFileChooser` draws its own and looks like nothing else on the machine. Its extension filter is advisory on some platforms, so the result is filtered again |
| `readLocalFile` | `java.io` (`jvmCommonMain`) | `StorageManager` is rooted at the app's own folder; an APK is anywhere |
| `Modifier.fileDropTarget` | `dragAndDropTarget` + AWT `javaFileListFlavor` | `shouldStartDragAndDrop` checks for files, so text dragged from another app falls through rather than being swallowed |

`pushFile` is new on `DeviceTransport`, over `AdbSync.push`.

## Phases

Each phase compiles on all three targets and leaves the app usable.

1. ~~**The device screen.**~~ **Done.** `ui/components/DataTable.kt` (generic; keyboard, multi-select, sortable, resizable),
   `data/DeviceColumn.kt`, `manager/DeviceTableState.kt` (order/hidden/widths/sort, all persisted),
   `ui/screen/DeviceScreen.kt`. The 91 icons came across from the Swing app as
   `composeResources/drawable`. Mirror, screenshot and refresh are wired; every other toolbar button
   is drawn disabled, so the toolbar is the real shape rather than a stub. The phone's
   `DeviceListScreen.kt` came across in the same phase — see [MOBILE-UI.md](MOBILE-UI.md).
2. **Device actions.** Right-click / long-press menu: details, copy field, rename, custom fields,
   reconnect, disconnect. All are `exec` calls that already work.
3. **Connect.** The transport picker and the wireless-connect sheet. The phone's half is built —
   [MOBILE-UI.md](MOBILE-UI.md) — and `AdbServerTransport` now implements `connect`/`disconnect`, so
   the desktop's `ConnectScreen` works too; what's missing on the desktop is a way to pick a
   transport at all.
4. **File explorer.** `AdbSync` already lists, stats, pulls and pushes — this is a `DataTable` and a
   path bar on top of it.
5. **Log viewer.** `logcat` over `exec:`, plus the Swing app's filter syntax.
6. **The tray menu**, submenu form.
7. **Everything else** — stats, install, input, scripts, share server.

## Deliberately later

- **scrcpy and screen recording.** Both shell out to external binaries in the Swing app; see
  PORT-PLAN.md for why that route isn't coming across.
- **Tier III live video.** ~70% of the total work, per ADB-TRANSPORTS.md.
- **Logs-only mode.** A whole second app shape; not worth carrying until the log viewer exists.
