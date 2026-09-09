# The bundled `adb` binary

An Android-native `adb` executable, named **`libadb.so`**, one per ABI:

```
androidApp/src/main/jniLibs/arm64-v8a/libadb.so   # committed, pinned by checksum
androidApp/src/main/jniLibs/x86_64/libadb.so      # emulators only, gitignored
```

**arm64 is committed** — building it needs a ~100 GB AOSP tree, so it cannot be a CI step, and at
~10 MB for a file that changes about once a year, checking it in beats LFS or fetching a release
asset. x86_64 is `.gitignore`d: it only matters for emulators, and would put another ~10 MB in every
clone.

Because it is a binary in the repo, it is **pinned rather than trusted**: `checksums.txt` is checked
on every build, and `PROVENANCE.md` records where it came from. See those two files.

**The app degrades cleanly without any of it** — `AdbDaemon.isBundled` is false, the Connect panel
says the build has no adb in it, and every other route works exactly as before.

## Why the name

It is a plain PIE executable, not a library, and is never `dlopen`ed. Android mounts an app's data
directory no-exec (API 29+), so the only place left to run something from is `nativeLibraryDir` —
and the installer only puts files matching `lib*.so` there. Hence the name. `useLegacyPackaging` is
set in `androidApp/build.gradle.kts` so the file is actually extracted to disk; see
`manager/LocalAdbServer.android.kt`.

## What's here, and replacing it

The arm64 binary currently committed is **LADB's prebuilt**, verified as needing nothing outside
bionic. `PROVENANCE.md` has the full record — source URL, sha256, BuildID, `DT_NEEDED` — and is the
file to read before trusting it or to rewrite when replacing it.

Replacing it takes two commands:

```sh
./scripts/fetch-adb-android.sh --from-file <path> [--abi arm64-v8a]
./gradlew :androidApp:recordAdbChecksums
```

The script prints the ELF type and the `DT_NEEDED` list, so a binary needing more than bionic is
caught here rather than on a device.

**What "statically linked" means for this file.** `file` calls every Android PIE executable
"dynamically linked" and that tells you nothing — the question is whether `DT_NEEDED` holds anything
beyond `libc`/`libm`/`libdl`, which are always present. One shortcut fails exactly that test and is
worth not retrying: **Termux's `android-tools`** is the same upstream source but links against
Termux's own C++ runtime, so bundling it means bundling its closure — measured at **108 shared
objects and 12 MB per ABI** — and `libc++` isn't in that repository at all, so it doesn't even link.

`LD_LIBRARY_PATH` is set to the binary's own directory when it runs, so a dynamically linked adb
*would* work with its libraries placed here beside it. A fallback, not the plan.

## Checking it landed

```sh
./gradlew :androidApp:recordAdbChecksums   # after adding or replacing one
./gradlew :androidApp:assembleDebug
unzip -l androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep libadb
adb shell run-as com.jpage4500.devicemanager ls -l /data/app/*/lib/arm64
```
