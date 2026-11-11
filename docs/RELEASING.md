# Releasing

The desktop app ships through **jDeploy**: native installers for macOS, Windows and Linux, published
as a GitHub release. The Android app ships as a signed APK on the same repo's releases. There is no
store involved for either.

This repo is **public**, and releases are published to it — there is no separate release repo. The
name is set in four places and they must agree: `RELEASE_REPO` in `scripts/lib/common.sh` (both
build scripts), `jdeploy.publishTargets[].url` and `homepage` in `package.json`, and
`LATEST_RELEASE_URL` in `manager/UpdateManager.kt`.

The version is `2.0.<git commit count>`, not the Swing app's `1.0.<count>`: the major is bumped so
this app always sorts above Swing's releases (1.0.585 and counting), which is what lets a Swing
install ever be updated into it.

## The normal path: merge to main

The workflows are thin: each build job is one call to a script in `scripts/`, the same one you run
by hand. Anything a release does lives in the script, so a local publish and a CI one can't drift.

`.github/workflows/build-desktop.yml` runs two jobs:

1. **tag-release** — computes `2.0.<git commit count>` and pushes it as a tag. The same scheme
   `composeApp/build.gradle.kts` uses, so the tag and `APP_VERSION` can't disagree.
2. **build-and-deploy** — triggered *by that tag*: `scripts/build-desktop.sh`.

`.github/workflows/build-android.yml` is triggered by the **same tag**: `scripts/build-android.sh ci`.
Both platforms release the same version off the same commit, and there's no second tagging job.

Things that exist because they were paid for once already:

- **`tags: ['2.0.*']`, never `['*']`.** jDeploy keeps its update metadata on a release literally
  named `jdeploy`. A `*` filter matching a tag of that name would publish a release at version
  "jdeploy". There's a second guard on the job itself (`github.ref_name != 'jdeploy'`).
- **`'!2.0.*-android'` in both workflows' tag filters.** `build-android.sh` creates
  `<version>-android` here with the PAT, and a PAT-made tag starts workflows: without the exclusion
  it starts a second desktop build, and starts `build-android.yml` again — which deletes and
  recreates the same tag, forever. Each job also checks `!endsWith(github.ref_name, '-android')`.
- **The checkout in `tag-release` uses the PAT, not `GITHUB_TOKEN`.** A push made with the default
  token does not trigger workflows, so neither build would ever run.
- **No `shannah/jdeploy` action, and no `jdeploy publish`.** The CLI can't publish to GitHub at all
  ("GitHub token is required for publishing to GitHub") — only the GUI and the Action can. So
  `build-desktop.sh` does what the Action does: `jdeploy@6
  github-prepare-release` writes the installers and `package-info.json`, and `gh` creates the
  release and refreshes the `jdeploy` release's `package-info.json`. Found first in CastToTVKMP.
  It also sidesteps the Action's habit of leaving tag releases as hidden drafts.
- **`jdeploy@6`, pinned.** A bare `npx jdeploy` runs a globally installed one if there is one, and
  4.x ignores `publishTargets` and falls back to `npm publish`.
- **Desktop `--latest`, Android `--latest=false`.** `UpdateManager` reads `releases/latest` on every
  platform; it must always land on the desktop release, never `<version>-android`.

## Publishing by hand

For publishing off a branch, or for finding out why the CI job failed without pushing five times:

```sh
./scripts/build-desktop.sh --dry-run   # build + package locally, publish nothing
./scripts/build-desktop.sh             # build + publish the desktop release

./scripts/build-android.sh             # build the signed APK, publish nothing
./scripts/build-android.sh install     # …and adb install + launch it (ANDROID_SERIAL picks a device)
./scripts/build-android.sh publish     # build + publish the android release
```

Both validate their keys up front and run on JDK 21. Off a tag, the version is Gradle's
`2.0.<commit count>`. `scripts/lib/common.sh` holds what the two share: the key lookup, the
version, and the release notes — `gen_release_notes.sh`'s notes for the last merge, less the lines
prefixed for the other platform (`- Android:`/`- phone:`/`- iOS:` off the desktop's, `- Desktop:`
/`- iOS:` off Android's).

`npm version` rewrites `package.json` — don't commit that bump. The committed version stays `2.0.0`;
the script sets the real one at publish time.

### What each release holds

| Release | Assets | Notes |
|---|---|---|
| `2.0.N` | jDeploy's installers + `adm-all.jar` | marked Latest |
| `2.0.N-android` | `adm-2.0.N.apk` | never Latest |
| `android` | `AndroidDeviceManager.apk`, replaced every release | a fixed link for the README: `…/releases/download/android/AndroidDeviceManager.apk` |
| `jdeploy` | `package-info.json` | what installed desktop apps read to update; a prerelease |

The `-android` and `android` tags are created by `gh` at publish time, the first on the commit
that was built. `android` matches no tag filter; `2.0.N-android` is excluded from both.

## Android signing

Release builds sign with the keystore shared across the KMP apps — `KMP_KEYSTORE` (base64),
`KMP_SIGNING_STORE_PASSWORD`, `KMP_SIGNING_KEY_ALIAS`, `KMP_SIGNING_KEY_PASSWORD`, read from the
environment then `~/.gradle/gradle.properties`. `androidApp/build.gradle.kts` decodes the keystore
under `build/tmp/`. Without them `assembleRelease` still builds, unsigned; the script refuses.

Every release must be signed with the same key, or an installed copy can't update in place — so
lose that keystore and every user uninstalls and reinstalls.

## Secrets

`GH_TOKEN` — a PAT with `contents: write` on this repo, to push the version tag and create the
releases. It has to be a PAT: a tag pushed with the default `GITHUB_TOKEN` starts no workflows. Plus
the four `KMP_*` signing keys above, for Android.

```sh
./scripts/set-github-secrets.sh --dry-run   # names and value lengths only, never values
./scripts/set-github-secrets.sh
```

It reads from the environment first, then `~/.gradle/gradle.properties` — the same order
`composeApp/build.gradle.kts` resolves keys in, so what CI builds with matches a local build.
`LOGGING_URL` and `LOGGING_API` go up the same way; without them the app still builds and runs, and
only the log upload refuses (see [APP-SERVICES.md](APP-SERVICES.md)).

On the two release workflows the `.github/actions/write-secrets` composite action appends *every*
Actions secret to `~/.gradle/gradle.properties`, so a new key needs naming only where the build
actually uses it — not in each workflow's `env:` block as well. `build.yml` doesn't run it: compiling
needs no secrets, and a PR from a fork gets none anyway.

## jDeploy configuration

`package.json` at the repo root, under `jdeploy`. jDeploy also reads `./icon.png` from the root and
offers no config key for either path.

| Key | Why it's that |
|---|---|
| `jar` | `composeApp/build/libs/adm-all.jar` — what `:composeApp:buildExecutableJar` produces |
| `javaVersion: "21"` / `jdk: false` | No JDK is bundled; the launcher fetches a JRE. 21 because Gradle 8.14 needs it |
| `bin: { adm: ... }` | The command name, carried over from the Swing app |
| `urlSchemes: ["adm"]` | `adm://` links open the app — also from the Swing app |
| `documentTypes` | `.apk` and `.xapk` open with it |
| `javafx: false` | Compose desktop draws through Skiko; there's no JavaFX here |

The jar is ~85 MB because it carries **every** platform's skiko natives, so one artifact runs
everywhere — see `buildExecutableJar` in `composeApp/build.gradle.kts`.

`icon.png` is generated, not hand-placed — see [Icons](#icons) below.

## Icons

`scripts/generate-icons.sh` writes every launcher icon in the repo from one source,
`resources/images/logo.png` — the 1024² artwork the Swing app has always shipped, pixel for pixel.
Nothing under an icon path is hand-edited: change that file, re-run the script, commit what it
wrote. It is idempotent, so a re-run with no artwork change produces byte-identical files and no
diff.

Needs `cwebp`, `magick` and `iconutil` — `brew install webp imagemagick`, and `iconutil` ships
with the Xcode command line tools.

| Output | Consumed by |
|---|---|
| `androidApp/…/mipmap-*/ic_launcher.webp` | `android:icon` in `AndroidManifest.xml` — pre-Oreo and the fallback |
| `androidApp/…/mipmap-*/ic_launcher_round.webp` | `android:roundIcon`, circle-masked |
| `androidApp/…/mipmap-*/ic_launcher_foreground.webp` | the `<foreground>` in `mipmap-anydpi-v26/ic_launcher{,_round}.xml`, over `@color/ic_launcher_background` |
| `iosApp/Assets.xcassets/AppIcon.appiconset/*.png` | `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`, already set in `iosApp.xcodeproj` |
| `composeApp/…/desktopMain/resources/icon.{icns,ico,png}` | `nativeDistributions`' `macOS`/`windows`/`linux` `iconFile` |
| `icon.png` (repo root) | jDeploy, which always reads `./icon.png` and offers no config key |

Two files hold values that have to stay in step with the script, and each says so in a comment:
`ic_launcher_background.xml` (the adaptive background color) and `AppIcon.appiconset/Contents.json`
(the iOS slot table — the script errors if a slot it can't fill appears there). `gradle` guards
each desktop `iconFile` behind an `exists()` check, so a checkout that hasn't run the script still
packages.

### Why a PNG source and not an SVG

DashboardWeatherKMP runs the same pipeline off two SVGs, which is better in every way this artwork
can't support. It's a raster illustration — soft gradients, gloss, translucent glass, blurred
beams. Traced it posterizes: even a 128-colour undithered quantisation, the ceiling a potrace
layer-stack could reach, bands across the background gradient and steps the beam glow, and costs
tens of thousands of path nodes to do it. Wrapping the PNG in an `<image>` element would be an SVG
in name only. So the raster is the source, and the two things vector bought are handled in the
script: every output is a downscale of a 1024 master and never an upscale, and `square_fill()`
synthesises the corners.

That corner fill is the one non-obvious step. iOS and adaptive-Android composite the icon onto
their own background and then apply their own corner mask, so both need a source that runs edge to
edge — give either a tile that's already rounded and the corners get rounded twice, with a gap
showing through. `logo.png`'s corners are transparent, so the script fills them from the artwork
itself, enlarged and blurred, rather than from an invented gradient — that keeps the real
background including its slight edge lift. It then lays the artwork back over that through a
squircle eroded 26px, which is what drops the tile's pale bevel rim; skip the erosion and the rim
reads as an arc stranded inside the square.

The Android adaptive foreground is drawn at 667‰ of the 108dp canvas — exactly the 72dp viewport,
so it covers every mask shape and `@color/ic_launcher_background` shows only while a launcher
parallaxes the layers apart. A circular mask still trims the corners, which is unavoidable for
full-bleed artwork; the phones at the bottom corners are what goes first.

There's no macOS Tahoe `Icon.icon` bundle. It wants a background-free foreground to sit over its
procedural gradient, and this artwork has its background baked in, so there's nothing honest to
put in it. Nothing bundles it today either — Compose Desktop packages `icon.icns`.

## CI

`.github/workflows/build.yml` runs on every push to main and every PR, and publishes nothing:

- **jvm** (ubuntu) — compiles desktop, builds the debug APK, uploads it as an artifact. The APK is
  what proves ktor still dexes on minSdk 24 (see [PROJECT-SETUP.md](PROJECT-SETUP.md)).
- **ios** (macos-15) — *links* the framework rather than just compiling it, because linking is what
  catches an unresolved symbol an `actual` forgot.

There's no test suite, so compiling on all three is the check. `commonMain` is most of the app and
desktop-only verification is how iOS breaks.

## Not set up

[RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md) has the full list of what's left before the first
release, in order. In short:

- **`GH_TOKEN`.** Not set yet, so the workflow above has never run.
- **Android in-app updater.** The APK is released, but the app only reports a newer version as a
  link to the release page — nothing downloads or installs it. See
  [RELEASE-CHECKLIST.md](RELEASE-CHECKLIST.md). No Play Store.
- **iOS release.** No signing team, no App Store Connect key, no TestFlight upload.
  DashboardWeatherKMP's `build-ios.yml` + `scripts/build-ios.sh` are the model.
