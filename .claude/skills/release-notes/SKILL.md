---
name: release-notes
description: Turn a list of changes (usually pasted from CHANGES.txt) into store release notes under 500 characters, used for both Google Play and the App Store. Use when the user asks to summarize changes for release notes, "what's new", store notes, or a changelog for the stores.
---

# Store release notes

The user pastes a list of change lines (normally the `CHANGES.txt` entries since the last release)
and wants **one** release-notes block that is used for **both** Google Play and the App Store.

## Output

- **Only one version.** Don't write a separate longer App Store version.
- **Under 500 characters total**, counting bullets, spaces and newlines (Play's limit). Count it
  before replying. If it's over, merge or drop lines until it fits.
- Plain text in a fenced code block so it can be pasted straight in. No headers like "What's New" or
  "Fixes". Use `•` bullets, one per line.
- After the block, add a short note on what you left out, if anything, so the user can add it back.

## What to keep and how to write it

- **Lead with features, then fixes.** Fixes start with "Fixed …".
- **Keep what users notice**: new tiles or screens, visible behaviour changes, fixes for bugs users
  would have seen.
- **Drop internal changes**: logging, diagnostics, analytics, memory/telemetry notes, rate-limit or
  retry tuning, refactors. A privacy change, such as tokens no longer being logged, can usually be
  dropped too. Mention it in the left-out note.
- **Merge related lines.** Several lines about one feature become one bullet. Several small fixes
  become one catch-all bullet, e.g. "Fixes for text sizing, pollen data, HTML tiles and image
  loading".
- **Keep platform-specific items** (Android Auto, iOS-only, desktop-only), since the same notes ship
  everywhere. Keep them short, and drop them first if space is tight.
- Plain, user-facing words. No file names, class names or technical detail. Leave out the
  `Area:` prefixes used in `CHANGES.txt` unless the bullet needs the area name to make sense.

## Example

Input (excerpt):
```
- Safety Monitor: the tile shows an active intrusion, smoke, water or rule alert
- Safety Monitor: fixed Armed Night showing as Disarmed
- Pollen Count: works in Europe, and is added by searching for a city or postal code
- Pollen Count: choose the location and pollen source from the tile's edit dialog
- Logs: hub access tokens are no longer written to the log file
- Cloud: fixed a hub outage triggering a burst of cloud requests that got rate limited
```

Output:
```
• Safety Monitor shows active intrusion, smoke, water and rule alerts
• Pollen Count works in Europe; pick location and source in the tile's edit dialog
• Fixed Armed Night showing as Disarmed
```
Left out: the log-token change and the cloud rate-limit fix (internal).
