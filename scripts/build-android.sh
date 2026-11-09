#!/bin/bash
#######################################################################################################################################
# Build the signed Android APK and publish it as a release on the public repo. No Play Store.
#
# Usage: build-android.sh [mode]
#
# Modes:
#   local    (default) build the signed release APK; publish nothing
#   install            build it, install it with adb and launch it (set ANDROID_SERIAL with more than
#                      one device attached)
#   publish            build it and publish the release, from any branch
#   ci                 build it; publish only on a version tag or main (see isReleaseRef in lib/common.sh)
#
# The same script runs locally and on CI — .github/workflows/build-android.yml is just a call to
# `build-android.sh ci` on the version tag build-desktop.yml pushes.
#
# A publish creates "<version>-android" on RELEASE_REPO, beside the desktop's "<version>", with
# --latest=false: UpdateManager reads releases/latest and must only ever see the desktop release
# there. It also replaces the one asset on the fixed "android" release, so
#   https://github.com/<RELEASE_REPO>/releases/download/android/AndroidDeviceManager.apk
# is always the newest APK — a link the README can hold that never changes.
#
# Both tags are created in RELEASE_REPO only, by gh. A "<version>-android" tag pushed to THIS repo
# would match build-desktop.yml's `tags: ['2.0.*']` and start a desktop build.
#
# Required env vars or ~/.gradle/gradle.properties entries (validated up front):
#   API keys:  LOGGING_URL, LOGGING_API
#   Signing:   KMP_KEYSTORE (base64-encoded keystore; androidApp/build.gradle.kts decodes it),
#              KMP_SIGNING_STORE_PASSWORD, KMP_SIGNING_KEY_ALIAS, KMP_SIGNING_KEY_PASSWORD
#              — the keystore shared by the KMP apps. every release must be signed with the same key or
#              an installed copy can't update in place
#   Publish:   GH_TOKEN — contents:write on the release repo (also reads PR titles for the notes)
#######################################################################################################################################

set -e

SECONDS=0
MODE="${1:-local}"

cd "$(dirname "$0")/.."
source scripts/lib/common.sh

APP_ID="com.jpage4500.devicemanager"
APK_SRC="androidApp/build/outputs/apk/release/androidApp-release.apk"
STABLE_TAG="android"
STABLE_APK_NAME="AndroidDeviceManager.apk"

case "$MODE" in
    local|install|publish|ci) ;;
    *)
        echo "❌ unknown mode: $MODE (expected: local | install | publish | ci)"
        exit 1
        ;;
esac

echo "Verifying environment variables..."
requireProperties LOGGING_URL LOGGING_API \
    KMP_KEYSTORE KMP_SIGNING_STORE_PASSWORD KMP_SIGNING_KEY_ALIAS KMP_SIGNING_KEY_PASSWORD
echo "✅ API keys + signing creds present (keystore: $(printf '%s' "$KMP_KEYSTORE" | wc -c | tr -d ' ') base64 chars)"

useJdk21

VERSION=$(resolveVersion)
TAG="${VERSION}-android"
APK_NAME="adm-${VERSION}.apk"
echo "▶ version=$VERSION mode=$MODE"

# ==========================================
# build
# ==========================================
function buildApk {
    echo "📦 Building signed release APK..."
    ./gradlew :androidApp:assembleRelease
    if [ ! -f "$APK_SRC" ]; then
        echo "❌ APK not found at expected path: $APK_SRC"
        exit 1
    fi
    echo "✅ APK built: $APK_SRC"
}

# ==========================================
# publish
# ==========================================
function publish {
    requireProperties GH_TOKEN

    # phone lines describe the mobile UI, which is this app; desktop- and iOS-only ones aren't
    writeReleaseNotes release-notes.txt 'desktop|ios|windows|mac|macos|linux'
    cp "$APK_SRC" "$APK_NAME"
    {
        echo "## Release Notes"
        echo ""
        cat release-notes.txt
        echo ""
        echo "## Download"
        echo ""
        echo "- 🤖 **Android** — [$APK_NAME](https://github.com/$RELEASE_REPO/releases/download/$TAG/$APK_NAME)"
    } > combined-notes.txt

    echo "🚀 Publishing $RELEASE_REPO release $TAG..."
    # a re-run replaces the release, the same as build-desktop.sh does.
    # --latest=false keeps releases/latest on the desktop release (see header)
    gh release delete "$TAG" --repo "$RELEASE_REPO" --yes --cleanup-tag 2>/dev/null || true
    gh release create "$TAG" "$APK_NAME" --repo "$RELEASE_REPO" \
        --title "Android $VERSION" \
        --notes-file combined-notes.txt \
        --latest=false

    # the fixed link: created once, then only its one asset is replaced
    if ! gh release view "$STABLE_TAG" --repo "$RELEASE_REPO" >/dev/null 2>&1; then
        gh release create "$STABLE_TAG" --repo "$RELEASE_REPO" \
            --title "Android (newest)" \
            --notes "The newest Android APK, at a link that never changes: https://github.com/$RELEASE_REPO/releases/download/$STABLE_TAG/$STABLE_APK_NAME" \
            --latest=false
    fi
    cp "$APK_SRC" "$STABLE_APK_NAME"
    gh release upload "$STABLE_TAG" "$STABLE_APK_NAME" --repo "$RELEASE_REPO" --clobber
    rm -f "$APK_NAME" "$STABLE_APK_NAME"

    echo "✅ https://github.com/$RELEASE_REPO/releases/tag/$TAG"
}

# ==========================================
# mode dispatch
# ==========================================
buildApk

case "$MODE" in
    install)
        if ! command -v adb &> /dev/null; then
            echo "❌ adb not found. Install Android SDK platform-tools."
            exit 1
        fi
        # fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE over a debug-signed install — uninstall it first
        echo "Installing APK via adb..."
        adb install -r "$APK_SRC"
        adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 > /dev/null
        ;;

    publish)
        publish
        ;;

    ci)
        if isReleaseRef; then
            publish
        else
            echo "ℹ️  ${GITHUB_REF_NAME:-local} is not a version tag or main — built the APK, published nothing"
        fi
        ;;
esac

echo "🎉 Done."
printf '⏱  Total time: %dm %02ds\n' $((SECONDS/60)) $((SECONDS%60))
