#!/usr/bin/env bash
#
# Put an Android-native `adb` into androidApp/src/main/jniLibs/<abi>/libadb.so.
#
# The app runs its own adb server on loopback so wireless pairing works with no computer involved:
# pairing needs a TLS keying-material exporter that no Kotlin-reachable API exposes, and Google's
# binary has BoringSSL compiled into it. See docs/ADB-TRANSPORTS.md.
#
# Nothing is committed to the repo. Which binary ships in the app is a supply-chain decision, so it
# is made here, deliberately, by whoever builds it.
#
#   ./scripts/fetch-adb-android.sh --from-file <path> [--abi arm64-v8a]
#
# It wants a **statically linked** adb. Building one is the only route worth trusting, and adb's own
# build is Soong-only, so it wants an AOSP tree:
#
#   repo init -u https://android.googlesource.com/platform/manifest -b <tag> --depth 1
#   . build/envsetup.sh && lunch aosp_arm64-userdebug && m adb
#   ./scripts/fetch-adb-android.sh --from-file out/target/product/*/system/bin/adb
#
# Two routes that look easier and are not, both measured rather than assumed:
#
#   - Termux's `android-tools` is built from the same sources but links dynamically against Termux's
#     own C++ runtime. Bundling it means bundling its dependency closure: ~108 shared objects and
#     12 MB per ABI, and `libc++` is not even in that repository — so it does not link.
#   - A prebuilt from another app's repository runs with this app's permissions. If you take one,
#     take it knowingly and record where it came from.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI="$ROOT/androidApp/src/main/jniLibs"
WORK="${TMPDIR:-/tmp}/adm-adb"

usage() {
    sed -n '3,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//;s/^#$//'
    exit 1
}


# llvm-readelf ships with the NDK; GNU readelf works too. macOS has neither by default, hence the
# search rather than a plain command -v
find_readelf() {
    local candidate
    for candidate in llvm-readelf readelf greadelf; do
        if command -v "$candidate" >/dev/null 2>&1; then
            command -v "$candidate"
            return
        fi
    done
    # `find` over a directory that isn't there exits non-zero, which under `set -e` would kill the
    # script from inside a command substitution — hence the guards and the unconditional success
    local root
    for root in "${ANDROID_NDK_HOME:-}" "$HOME/Library/Android/sdk/ndk" "$HOME/Android/Sdk/ndk"; do
        [ -n "$root" ] && [ -d "$root" ] || continue
        candidate="$(find "$root" -name llvm-readelf -type f 2>/dev/null | head -1 || true)"
        if [ -n "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 0
}

install_as() {
    local abi="$1" src="$2" name="$3"
    mkdir -p "$JNI/$abi"
    cp "$src" "$JNI/$abi/$name"
    chmod 0755 "$JNI/$abi/$name"
    printf '  %-28s %s\n' "$abi/$name" "$(du -h "$JNI/$abi/$name" | cut -f1)"
}

from_file() {
    local path="$1" abi="$2"
    [ -f "$path" ] || { echo "no such file: $path"; exit 1; }
    # a quick sanity check on what was handed over, since the failure otherwise shows up as a
    # process that exits instantly with nothing useful in the log
    if command -v file >/dev/null 2>&1; then
        local kind; kind="$(file -b "$path")"
        echo "  $kind"
        case "$kind" in
            *ELF*) ;;
            *) echo "  warning: that does not look like an ELF executable" ;;
        esac
    fi

    # `file` calls every Android PIE binary "dynamically linked", so it says nothing useful — what
    # matters is whether anything beyond bionic is needed. libc/libm/libdl (and libz, which is in
    # the platform too) are always present on a device; anything else has to be shipped alongside
    local readelf
    readelf="$(find_readelf)"
    if [ -n "$readelf" ]; then
        local extra
        extra="$("$readelf" -d "$path" 2>/dev/null \
            | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' \
            | grep -vxE 'libc\.so|libm\.so|libdl\.so|libz\.so' || true)"
        if [ -n "$extra" ]; then
            echo "  warning: needs libraries beyond bionic, which must sit beside it:"
            echo "$extra" | sed 's/^/    /'
        else
            echo "  self-contained: needs only bionic"
        fi
    else
        echo "  (no readelf found — could not check its library dependencies)"
    fi
    install_as "$abi" "$path" "libadb.so"
}

ABI="arm64-v8a"
MODE=""
FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --from-file) MODE=file; FILE="${2:-}"; shift 2 ;;
        --abi) ABI="${2:-}"; shift 2 ;;
        *) usage ;;
    esac
done

case "$MODE" in
    file) from_file "$FILE" "$ABI" ;;
    *) usage ;;
esac

echo
echo "verify with:"
echo "  ./gradlew :androidApp:assembleDebug"
echo "  unzip -l androidApp/build/outputs/apk/debug/androidApp-debug.apk | grep 'lib.*\\.so'"
