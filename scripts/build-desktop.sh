#!/bin/bash
#######################################################################################################################################
# Build the desktop app and publish it as a release on the public repo.
#
# Usage: build-desktop.sh [--dry-run]
#   --dry-run   build and package the installers locally, publish nothing
#
# The same script runs locally and on CI — .github/workflows/build-desktop.yml's build job is just a
# call to it on the version tag — so a local publish and a CI one can't drift apart.
#
# A publish:
#   1. builds the fat jar (:composeApp:buildExecutableJar, which also flips BuildConfig IS_RELEASE)
#   2. has jDeploy write the installers + package-info.json into jdeploy/github-release-files/
#   3. creates the release on RELEASE_REPO with those files and the jar, notes above jDeploy's links
#   4. updates package-info.json on the repo's 'jdeploy' release — what installed apps read to update
#
# jDeploy reads ./package.json and ./icon.png from the repo root (it offers no config key for
# either). package.json > jdeploy.publishTargets[].url must name RELEASE_REPO (scripts/lib/common.sh).
#
# Required env vars or ~/.gradle/gradle.properties entries (validated up front):
#   API keys:  LOGGING_URL, LOGGING_API
#   Publish:   GH_TOKEN — contents:write on this repo (also reads PR titles for the notes)
#######################################################################################################################################

set -e

SECONDS=0

cd "$(dirname "$0")/.."
source scripts/lib/common.sh

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

echo "Verifying environment variables..."
if [ "$DRY_RUN" -eq 1 ]; then
    requireProperties LOGGING_URL LOGGING_API
else
    requireProperties LOGGING_URL LOGGING_API GH_TOKEN
fi
echo "✅ All required environment variables are set."

useJdk21

echo "Building executable jar..."
./gradlew :composeApp:buildExecutableJar
JAR="composeApp/build/libs/adm-all.jar"

VERSION=$(resolveVersion)
echo "Version resolved: $VERSION"

echo "Syncing package.json version..."
# --no-git-tag-version so no local tag is created; --allow-same-version so a failed publish can be
# re-run, since the first attempt already wrote the version. don't commit the bump — see RELEASING.md
npm version "$VERSION" --no-git-tag-version --allow-same-version

if [ "$DRY_RUN" -eq 1 ]; then
    echo "Packaging locally (dry run — publishing nothing)..."
    npx --yes jdeploy@6 package
    echo "🎉 Packaged to ./jdeploy/ — nothing was published."
    printf '⏱  Total time: %dm %02ds\n' $((SECONDS/60)) $((SECONDS%60))
    exit 0
fi

# ==========================================
# publish
# ==========================================
# 'jdeploy publish' can't publish to GitHub from the command line: it never hands the driver a token
# ("GitHub token is required for publishing to GitHub"); only its GUI and GitHub Action can. so this
# does what the Action (shannah/jdeploy action.yml) does for a target repo other than the one running
# it: jDeploy builds the release files, and gh uploads them. (CastToTVKMP found this first.)
RELEASE_FILES="jdeploy/github-release-files"

echo "Preparing release files via jDeploy..."
rm -rf "$RELEASE_FILES"
# pin the major version: a bare 'npx jdeploy' runs a globally installed jdeploy when there is one, and
# an old one (4.x) ignores publishTargets and falls back to 'npm publish' (ENEEDAUTH).
# github-prepare-release reads the published package-info.json from the jdeploy tag and adds this version
GITHUB_REPOSITORY="$RELEASE_REPO" npx --yes jdeploy@6 github-prepare-release

writeReleaseNotes release-notes.txt 'android|phone|ios'
{
    echo "## Release Notes"
    echo ""
    cat release-notes.txt
    echo ""
    cat "$RELEASE_FILES/jdeploy-release-notes.md"
} > combined-notes.txt

echo "Publishing $RELEASE_REPO release $VERSION..."
# a re-run replaces the release, as the Action does.
# --latest is explicit: UpdateManager reads releases/latest, and left automatic GitHub could pick the
# "<version>-android" release build-android.sh publishes alongside this one.
# the jar rides along for running headless or on a platform with no installer
gh release delete "$VERSION" --repo "$RELEASE_REPO" --yes 2>/dev/null || true
gh release create "$VERSION" --repo "$RELEASE_REPO" --title "$VERSION" \
    --notes-file combined-notes.txt --latest \
    "$RELEASE_FILES"/* "$JAR"

# installed copies check the 'jdeploy' release's package-info.json for updates. uploaded after the
# version release, so it never lists a version that isn't there yet. package-info-2.json is jDeploy's
# backup copy
echo "Updating the jdeploy release's package-info.json..."
if ! gh release view jdeploy --repo "$RELEASE_REPO" > /dev/null 2>&1; then
    gh release create jdeploy --repo "$RELEASE_REPO" --title jdeploy --prerelease --latest=false \
        --notes "Release metadata for jDeploy releases"
fi
PACKAGE_INFO_DIR=$(mktemp -d)
cp "$RELEASE_FILES/package-info.json" "$PACKAGE_INFO_DIR/package-info.json"
cp "$RELEASE_FILES/package-info.json" "$PACKAGE_INFO_DIR/package-info-2.json"
gh release upload jdeploy --repo "$RELEASE_REPO" --clobber \
    "$PACKAGE_INFO_DIR/package-info.json" "$PACKAGE_INFO_DIR/package-info-2.json"
rm -rf "$PACKAGE_INFO_DIR"

echo "🎉 Build and deployment complete!"
echo "   https://github.com/$RELEASE_REPO/releases/tag/$VERSION"
printf '⏱  Total time: %dm %02ds\n' $((SECONDS/60)) $((SECONDS%60))
