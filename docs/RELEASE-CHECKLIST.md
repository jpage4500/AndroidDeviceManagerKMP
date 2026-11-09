# Release checklist

What still has to happen before the first public release, in order. [RELEASING.md](RELEASING.md)
describes the pipeline that already exists; this is the list of what's missing around it. Tick items
off here and delete the file once the first release is out.

State as of 11/06: the desktop pipeline (`build-desktop.yml`, jDeploy, README sync, version tag) is
written but has **never run** — `GH_TOKEN` isn't set yet. The release repo,
[jpage4500/AndroidDeviceManager2](https://github.com/jpage4500/AndroidDeviceManager2), exists and
the version is `2.0.<commit count>`. Android and
iOS have no release path at all. Nothing is on `main` yet beyond the first 3 commits.

---

## 1. Decisions to make first

These change what the later steps look like, so settle them before creating anything.

### 1a. Does the KMP app replace the Swing app for existing users?

Both `package.json` files use the same npm name, `android-device-manager`, but publish to different
repos. A jDeploy install updates from the repo it was installed from, so:

- **Publish to the new `AndroidDeviceManager2` repo** (what the config says today) — the KMP
  app is a separate install. Swing users keep getting Swing updates and never see the new app unless
  they go and install it. Safe; no migration.
- **Publish to `jpage4500/AndroidDeviceManager`** (the Swing repo, public, latest `1.0.585`) — every
  Swing install auto-updates into the KMP app on its next launch. That's the "replace" the project is
  for, but it's one-way and hits everyone at once.

Recommendation: ship to the new repo first, run it alongside Swing for a few releases, then cut over
by publishing to the Swing repo (or by a final Swing release that tells users where to go).

### 1b. The version number — done: `2.0.<commit count>`

Swing is at **1.0.585**, so the major bump keeps this app above it whatever the commit count. Caveat
for the cutover: jDeploy's installer lets a user limit auto-update to minor or patch versions, and
those users won't jump from 1.x to 2.x by themselves.

Still to decide: **merge, don't squash** for `feature/* → main`. A squash drops the commit count, and
so the version, whenever history is squashed (`main` has 3 commits, this branch 23).

### 1c. Source private or public?

The plan of record is **private source, public release repo** with only the README synced across.
Making `AndroidDeviceManagerKMP` itself public would remove the second repo and the README sync,
though not the PAT — a tag pushed with `GITHUB_TOKEN` never triggers the build, whichever repo it's
in. The Swing app's source is public. Keep it private unless there's a reason to
open it — but if it goes public, add a `LICENSE` first (there is none; `package.json` says `ISC`).

### 1d. Desktop auto-update: jDeploy or EngageAdminKMP's updater

Recommendation: **jDeploy**. See [§4](#4-auto-update) for the comparison.

### 1e. Android distribution: GitHub sideload or Play Store

Recommendation: **GitHub sideload with an in-app updater first**; Play later if at all. Play forbids
an app updating itself from outside Play and restricts `REQUEST_INSTALL_PACKAGES`, so the two need
separate builds (a flavor) if both are ever wanted. See [§4](#android-port-engageadminkmps-apk-updater).

---

## 2. The public release repo

1. ~~Create it~~ — done: `jpage4500/AndroidDeviceManager2`, public, with a README on `main`. Every
   place that names it has been updated (see [RELEASING.md](RELEASING.md)).
2. Settings → General: turn off Wiki and Projects. Leave **Issues on** — it's the only public place
   for bug reports, since the source repo is private.

## 3. Secrets and tokens

1. Create a **fine-grained PAT** (`GH_TOKEN`): repository access = `AndroidDeviceManagerKMP` and
   `AndroidDeviceManager2`; permission **Contents: Read and write**. Give it an expiry you'll
   remember, and put the date in your calendar — when it lapses, releases fail silently at the tag
   step.
2. Put it, `LOGGING_URL` and `LOGGING_API` in `~/.gradle/gradle.properties`, then
   `./scripts/set-github-secrets.sh --dry-run` and `./scripts/set-github-secrets.sh`.
3. Check `LOGGING_API` is safe to ship. It's baked into a jar anyone can download and unzip, so it
   must be a key that can only **insert** log uploads (Supabase anon key + an insert-only RLS policy),
   never read them back.

## 4. Auto-update

### Desktop: jDeploy (recommended) vs EngageAdminKMP's updater

| | jDeploy (already wired) | EngageAdminKMP-style |
|---|---|---|
| How it updates | jDeploy's launcher checks the release repo's `jdeploy` tag on every start and installs the newer version before the app opens | `Launcher.kt` main class swaps staged jars in on start; the app downloads `payload.zip` in the background and asks to restart |
| Installers | jDeploy builds mac/win/linux installers from one fat jar on one Linux runner | `jpackage` on a 3-OS matrix (+ WiX on Windows) |
| macOS signing | jDeploy's installer is signed and notarized by jDeploy — no Apple Developer ID needed | unsigned DMG unless you pay for a Developer ID and add notarization; otherwise Gatekeeper blocks it |
| Code to write | none — `UpdateManager` already tells the user a release exists | port `update/`, `launcher/`, `assemblePayload`, the matrix workflow |
| Why EngageAdmin needed it | — | its releases are in a **private** repo, so every check needs GitHub sign-in (device flow, GitHub App). This app's releases are public — that whole half is unnecessary |
| Costs | each update re-downloads the ~85 MB jar; updates land only at the next launch; depends on jDeploy staying alive | full control: progress UI, "restart now", smaller per-OS payload |

jDeploy wins on the thing that's hardest to fix later — signed macOS installs — and it's the channel
Swing users already have, which is what makes the cutover in §1a possible at all. Revisit the
EngageAdmin route only if the launch-time update proves annoying with 45 devices attached (a user
leaves the app open for days and never restarts).

To do for jDeploy:

1. Change `UpdateManager`'s offer from "open the release page" to **"restart to update"** on desktop
   — opening the page invites a manual reinstall over the jDeploy install. Quitting is enough; the
   launcher does the rest next start.
2. On the first release, install from the release page on a **clean Mac and a Windows box**, then
   publish a second release and confirm both update on relaunch. Note how Gatekeeper and SmartScreen
   react — that's the one part of jDeploy's signing story to see rather than trust.
3. Decide what happens to `buildExecutableJar`'s 85 MB. Dropping the Linux arm64 and macOS x64 skiko
   natives (if nobody runs those) is the cheap win, since every update downloads the whole jar.

### Android: port EngageAdminKMP's APK updater

There is no jDeploy for Android, so this is code either way. EngageAdmin's
`PlatformUpdate.android.kt` is most of it, minus the GitHub sign-in (public repo — plain unauthenticated
API calls through the existing `NetworkManager`):

1. **A release keystore.** Generate it once, back it up somewhere that isn't this laptop — a lost key
   means every user uninstalls and reinstalls. Add a `signingConfigs.release` to
   `androidApp/build.gradle.kts` that reads `ADM_SIGNING_*` keys through the same
   env → `~/.gradle/gradle.properties` lookup `composeApp` uses, and leaves the build unsigned when
   they're missing.
2. **A release job** (`build-android.yml`, or a job in `build-desktop.yml` after the tag): build
   `:androidApp:assembleRelease`, then
   `gh release create <version>-android adm-<version>.apk --repo AndroidDeviceManager2 --latest=false`.
   `--latest=false` matters: desktop's `UpdateManager` reads `releases/latest`, and jDeploy's own
   release must stay the latest one. Create that tag **in the release repo only** — a
   `<version>-android` tag pushed to *this* repo matches `tags: ['2.0.*']` and starts a desktop build.
   Pass the keystore to CI as a base64 secret.
3. **The updater**: `REQUEST_INSTALL_PACKAGES` and a `FileProvider` (`res/xml/file_paths.xml` for the
   cache `updates/` dir) in `AndroidManifest.xml`; the android `actual` finds the newest
   `*-android` release, downloads the APK, checks GitHub's sha256 `digest` and the APK's package name
   and version, then hands it to the installer — sending the user to "Install unknown apps" the first
   time. The shared `UpdateManager`/`CheckUpdatesDialog` grow a Download → Install state.
4. Android version is `versionCode = commit count`, which must only ever go up. Same squash-merge
   warning as §1b, and it can never go backwards once a build is out.

## 5. iOS (not needed for the first release)

No auto-update work — the App Store and TestFlight do it. What's missing is the whole channel: an
Apple Developer account ($99/yr), a signing team in `iosApp.xcodeproj`, an App Store Connect API key
as CI secrets, and a `build-ios.yml` that archives and uploads to TestFlight (DashboardWeatherKMP's
`build-ios.yml` + `scripts/build-ios.sh` are the model). Expect App Review to ask what an "adb
client" on iPhone is for — have the remote-control use case written up.

## 6. Public-facing content

1. ~~Write a user README~~ — done: `README.md` is now the user page the sync publishes; the
   developer notes moved to `docs/README.md`.
2. ~~Screenshots~~ — six desktop ones in `resources/screenshots/`, all linked from the README.
   Every image the README links is published, so check one for real phone numbers, IMEIs and file
   names before it goes in.
3. A line on the Swing repo's README pointing to the new app, once §1a is decided.

## 7. Before tagging the first release

1. `REMAINING.md` is out of date — "Check for updates on launch" is done, among others. Bring it
   current so it can double as the public "what's missing vs. Swing" list.
2. Run one release end to end **off a branch first**: `./scripts/build-desktop.sh --dry-run`, then a
   branch push to get the `0.0.0-<branch>` prerelease, install that, and only then merge to `main`.
3. Merge `feature/09-07` to `main` (not squashed — §1b). That push tags and publishes.
4. Smoke-test the published installers on macOS, Windows and Linux: install, connect a USB device,
   mirror it, confirm the log upload works (it proves `LOGGING_*` reached CI).
