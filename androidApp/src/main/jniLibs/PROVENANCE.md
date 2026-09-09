# Where the bundled `adb` came from

The binary in `arm64-v8a/libadb.so` runs inside the app with the app's own permissions, and it is
committed to this repo — so where it came from is part of the source, not a detail.

`checksums.txt` is checked on **every** build by `:androidApp:verifyAdbBinary`. A release fails on a
binary with no recorded checksum; a debug build only warns, so a local emulator binary isn't a
nuisance. Either way a binary that doesn't match what was recorded fails the build naming both
hashes — because the alternative is a process that exits instantly with nothing useful in the log.

A checksum pins **which** file ships. It says nothing about whether it should. That is what the rest
of this file is for.

## What is here now

Both were taken from [LADB](https://github.com/tytydraco/LADB), which has carried prebuilt adb
binaries for its own use since 2021 and keeps an Apache 2.0 licence beside them
(`app/src/main/jniLibs/LICENSE` — adb's own licence, and the one that governs redistributing it).

| | arm64-v8a | x86_64 |
|---|---|---|
| Source | `raw.githubusercontent.com/tytydraco/LADB/master/app/src/main/jniLibs/arm64-v8a/libadb.so` | same path, `x86_64/` |
| Retrieved | 2026-09-09 | 2026-09-09 |
| Size | 5,142,600 bytes | 5,417,704 bytes |
| sha256 | `47ea035fa5ed57f6149a2b025bbbd4b21584c355c05d0400416804715e4c12de` | `62cc0f7707c83c98aa4de699492c61091eda9d68b3db9ea2a185b179da505bfa` |
| ELF | 64-bit LSB **PIE executable**, AArch64, `interpreter /system/bin/linker64`, stripped | same, x86-64 |
| BuildID | `9ace1dfd7f63db3e8bd9ccacd15259a84023e34c` | `28d0b9d193b9c1e4e5e33beb12e2e20b4a4d1885` |
| `DT_NEEDED` | `libm.so`, `libdl.so`, `libc.so` — **bionic only** | same |
| Committed | yes | no — `.gitignore`d, see below |

**`DT_NEEDED` is the number that matters.** `file` calls every Android PIE binary "dynamically
linked", which says nothing; what counts is that nothing outside bionic is required, so BoringSSL,
protobuf, abseil and libbase are all linked in. That is the whole difference from Termux's
`android-tools`, which needs 108 shared objects beside it (see README.md).

Two observations worth keeping, neither of them a problem:

- The arm64 binary carries `RUNPATH = /data/data/com.termux/files/usr/lib`, so it was built inside
  Termux. Nothing resolves through that path, because there is nothing left to resolve — it is a
  fossil of the build environment, not a dependency.
- Both are stripped, so the BuildID above is the only build identity they carry.

**This is a third-party prebuilt, and that is the weak link.** It is a real build of upstream adb by
a well-known project, verified as above, and it is the reason the QR pairing route works at all — but
nobody in this repo compiled it. Replacing it with a build of your own is strictly better and costs
one command; see below.

## Building your own instead

`adb`'s build is Soong-only, so it wants an AOSP tree:

```sh
repo init -u https://android.googlesource.com/platform/manifest -b <tag> --depth 1
. build/envsetup.sh && lunch aosp_arm64-userdebug && m adb
./scripts/fetch-adb-android.sh --from-file out/target/product/*/system/bin/adb
./gradlew :androidApp:recordAdbChecksums
```

Then rewrite the table above. The script prints the ELF type and the `DT_NEEDED` list, so a binary
needing more than bionic is caught before it is committed rather than on a device.

## x86_64

Not committed — `.gitignore`d, because it only matters for emulators and would put another ~5 MB in
every clone. Its checksum *is* recorded, so a clone without the file simply skips the check, and a
machine that has one gets it verified. Add one with
`./scripts/fetch-adb-android.sh --from-file <path> --abi x86_64`.

## If you update it

Replacing this binary is the same kind of change as bumping a dependency, and rather more sensitive.
Record the new provenance, re-run `recordAdbChecksums`, and put the reason in the commit message.
Git keeps every version forever with no delta compression on binaries, so each replacement adds
another full copy to history — a reason to update deliberately, not a reason to avoid updating.
