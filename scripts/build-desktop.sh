#!/bin/bash
#######################################################################################################################################
# Build the desktop app and publish the installers via jDeploy.
#
# Usage: build-desktop.sh [--dry-run]
#   --dry-run   build and package locally, publish nothing
#
# jDeploy reads ./package.json and ./icon.png from the repo root (it offers no config key for
# either) and publishes to the repo named in package.json > jdeploy.publishTargets[].url —
# jpage4500/AndroidDeviceManagerRelease.
#
# Normally you don't run this: merging to main tags the commit and
# .github/workflows/build-desktop.yml does the same thing on CI. This is for publishing off a
# branch, or for finding out why the CI job failed without pushing five times.
#
# Required env vars or ~/.gradle/gradle.properties entries (validated up front):
#   API keys:  LOGGING_URL, LOGGING_API
#   Publish:   GH_TOKEN — needs contents:write on the release repo
#######################################################################################################################################

set -e

SECONDS=0

cd "$(dirname "$0")/.."

DRY_RUN=0
[ "${1:-}" = "--dry-run" ] && DRY_RUN=1

# value from the environment first, then ~/.gradle/gradle.properties — the same order
# composeApp/build.gradle.kts resolves them in, so a local build matches CI
function getProperty {
    PROP_KEY=$1
    if [ -n "${!PROP_KEY:-}" ]; then
        echo "${!PROP_KEY}"
    else
        PROP_VALUE=$(grep "^$PROP_KEY=" ~/.gradle/gradle.properties 2>/dev/null | cut -d'=' -f2-)
        echo "${PROP_VALUE}"
    fi
}

echo "Loading environment variables..."
export LOGGING_URL=$(getProperty "LOGGING_URL")
export LOGGING_API=$(getProperty "LOGGING_API")

# jDeploy authenticates the release upload with GITHUB_TOKEN
export GITHUB_TOKEN=$(getProperty "GH_TOKEN")

# ==========================================
# Validate the environment
# ==========================================
echo "Verifying environment variables..."
MISSING_VARS=0

REQUIRED="LOGGING_URL LOGGING_API"
[ "$DRY_RUN" -eq 0 ] && REQUIRED="$REQUIRED GITHUB_TOKEN"

for VAR_NAME in $REQUIRED; do
    if [ -z "${!VAR_NAME}" ]; then
        echo "❌ Error: $VAR_NAME is missing or empty. Check your environment or ~/.gradle/gradle.properties."
        MISSING_VARS=1
    fi
done

if [ "$MISSING_VARS" -eq 1 ]; then
    echo "🚨 Exiting: cannot proceed with missing environment variables."
    exit 1
fi

echo "✅ All required environment variables are set."

# JDK 21: Gradle 8.14 cannot run on Java 25, which is what JAVA_HOME points at on this machine
if [ -z "${JAVA_HOME:-}" ] && [ -d /Library/Java/JavaVirtualMachines/jdk-21.jdk ]; then
    export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
    echo "JAVA_HOME not set — using $JAVA_HOME"
fi

echo "Preparing Gradle..."
chmod +x ./gradlew

# the task name is also what flips BuildConfig IS_RELEASE to true — see composeApp/build.gradle.kts
echo "Building executable jar..."
./gradlew :composeApp:buildExecutableJar

echo "Fetching version from Gradle..."
VERSION=$(./gradlew -q printVersion | tail -n 1)
echo "Version resolved: $VERSION"

echo "Syncing package.json version..."
# --no-git-tag-version so no local tag is created by the bump
npm version "$VERSION" --no-git-tag-version

if [ "$DRY_RUN" -eq 1 ]; then
    echo "Packaging locally (dry run — publishing nothing)..."
    npx --yes jdeploy package
    echo "🎉 Packaged to ./jdeploy/ — nothing was published."
else
    echo "Deploying via jDeploy..."
    npx --yes jdeploy publish
    echo "🎉 Build and deployment complete!"
    echo "   https://github.com/jpage4500/AndroidDeviceManagerRelease/releases"
fi

printf '⏱  Total time: %dm %02ds\n' $((SECONDS/60)) $((SECONDS%60))
