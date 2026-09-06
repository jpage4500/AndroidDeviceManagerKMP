# Releasing

The desktop app ships through **jDeploy**: native installers for macOS, Windows and Linux, published
as a GitHub release. There is no store involved and nothing to sign.

This repo is **private**. Releases and the public README go to a separate **public** repo:

```
jpage4500/AndroidDeviceManagerRelease
```

Nothing but the README and the release assets lives there — no source. The name is set in three
places and they must agree: `TARGET_REPO` in `.github/workflows/build-desktop.yml`,
`jdeploy.publishTargets[].url` in `package.json`, and `homepage` in `package.json`.

## The normal path: merge to main

`.github/workflows/build-desktop.yml` does the whole thing in three jobs:

1. **sync-readme** — copies `README.md` and every local file it links to into the public repo.
   Deliberately not a dependency of the others: a docs sync failing shouldn't hold up a release.
2. **tag-release** — computes `1.0.<git commit count>` and pushes it as a tag. The same scheme
   `composeApp/build.gradle.kts` uses, so the tag and `APP_VERSION` can't disagree.
3. **build-and-deploy** — triggered *by that tag*. Builds the fat jar, hands it to jDeploy, attaches
   the jar to the release, and prepends generated release notes to the body.

The tag is what makes jDeploy cut a versioned release. On a branch push it publishes a rolling
prerelease at version `0.0.0-<branch>` instead.

Three things in that workflow exist because they were paid for once already:

- **`tags: ['1.0.*']`, never `['*']`.** jDeploy creates a tag literally named `jdeploy` to hold
  `package-info.json` — the file installed apps read to auto-update. A `*` filter re-triggers the
  workflow on it and publishes a release at version "jdeploy", pointing `dist-tags.latest` at
  nothing. There's a second guard on the job itself (`github.ref_name != 'jdeploy'`).
- **The checkout in `tag-release` uses the PAT, not `GITHUB_TOKEN`.** A push made with the default
  token does not trigger workflows, so `build-and-deploy` would never run.
- **`gh release edit --draft=false`.** jDeploy uploads tag releases through
  `xresloader/upload-to-github-release`, which defaults `draft=true` and jDeploy never overrides it.
  Without this the release is created hidden and its assets 404 for the launcher.

## Publishing by hand

For publishing off a branch, or for finding out why the CI job failed without pushing five times:

```sh
./scripts/build-desktop.sh --dry-run   # build + package locally, publish nothing
./scripts/build-desktop.sh             # build + publish to the release repo
```

It validates `LOGGING_URL`, `LOGGING_API` and `GH_TOKEN` up front, builds
`composeApp/build/libs/adm-all.jar`, syncs `package.json`'s version to what Gradle reports, then runs
`jdeploy publish`.

`npm version` rewrites `package.json` — don't commit that bump. The committed version stays `1.0.0`;
CI and the script both set the real one at publish time.

## The one secret

`GH_TOKEN` — a PAT with `contents: write` on **both** repos: this one, to push the version tag, and
the release repo, to create the release and push the README.

```sh
./scripts/set-github-secrets.sh --dry-run   # names and value lengths only, never values
./scripts/set-github-secrets.sh
```

It reads from the environment first, then `~/.gradle/gradle.properties` — the same order
`composeApp/build.gradle.kts` resolves keys in, so what CI builds with matches a local build.
`LOGGING_URL` and `LOGGING_API` go up the same way; without them the app still builds and runs, and
only the log upload refuses (see [APP-SERVICES.md](APP-SERVICES.md)).

On CI the `.github/actions/write-secrets` composite action appends *every* Actions secret to
`~/.gradle/gradle.properties`, so a new key needs naming only where the build actually uses it — not
in each workflow's `env:` block as well.

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

`icon.png` came across from the Swing app, same icon for the same app. The Compose desktop
distribution (`Dmg`/`Msi`/`Deb` via `nativeDistributions`) still has no icon wired up — that path
isn't what ships, jDeploy is.

## CI

`.github/workflows/build.yml` runs on every push to main and every PR, and publishes nothing:

- **jvm** (ubuntu) — compiles desktop, builds the debug APK, uploads it as an artifact. The APK is
  what proves ktor still dexes on minSdk 24 (see [PROJECT-SETUP.md](PROJECT-SETUP.md)).
- **ios** (macos-14) — *links* the framework rather than just compiling it, because linking is what
  catches an unresolved symbol an `actual` forgot.

There's no test suite, so compiling on all three is the check. `commonMain` is most of the app and
desktop-only verification is how iOS breaks.

## Not set up

- **Android release.** No keystore, no signing config, no Play Store account wired up.
  DashboardWeatherKMP's `build-android.yml` + `scripts/build-android.sh` are the model — they sign,
  publish to a Play track and cut a GitHub release, and want five more secrets.
- **iOS release.** No signing team, no App Store Connect key, no TestFlight upload.
  DashboardWeatherKMP's `build-ios.yml` + `scripts/build-ios.sh` are the model.
- **Icons** beyond the jDeploy `icon.png` — nothing for Android, iOS or the Compose desktop
  distribution.
