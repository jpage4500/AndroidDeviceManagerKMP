#!/bin/bash
#######################################################################################################################################
# Helpers shared by build-desktop.sh and build-android.sh. Sourced, never run.
#
# Expects the caller to have cd'd to the repo root first.
#######################################################################################################################################

# the PUBLIC repo releases are published to; this repo is private. must agree with
# package.json > jdeploy.publishTargets[].url and UpdateManager's LATEST_RELEASE_URL — see
# docs/RELEASING.md
RELEASE_REPO="jpage4500/AndroidDeviceManager2"

# value from the environment first, then ~/.gradle/gradle.properties — the same order
# composeApp/build.gradle.kts resolves them in, so a local build matches CI
function getProperty {
    local key=$1
    if [ -n "${!key:-}" ]; then
        echo "${!key}"
    else
        grep "^$key=" ~/.gradle/gradle.properties 2>/dev/null | cut -d'=' -f2-
    fi
}

# export each named key from getProperty, failing with every missing name listed rather than the first
function requireProperties {
    local missing=0 name
    for name in "$@"; do
        export "$name"="$(getProperty "$name")"
        if [ -z "${!name}" ]; then
            echo "❌ $name is missing or empty. Set it in the environment or ~/.gradle/gradle.properties."
            missing=1
        fi
    done
    if [ "$missing" -eq 1 ]; then
        echo "🚨 cannot proceed with missing values."
        exit 1
    fi
}

# JDK 21: Gradle 8.14 cannot run on Java 25, which is what JAVA_HOME points at on Joe's machine.
# CI's setup-java sets JAVA_HOME, so this only ever fires locally
function useJdk21 {
    if [ -z "${JAVA_HOME:-}" ] && [ -d /Library/Java/JavaVirtualMachines/jdk-21.jdk ]; then
        export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
        echo "JAVA_HOME not set — using $JAVA_HOME"
    fi
    chmod +x ./gradlew
}

# the version to release as. on a tag-triggered CI run that's the tag itself, so the release is named
# exactly what was pushed; otherwise it's Gradle's "2.0.<commit count>" — the same commit gives the
# same count, so the two only differ if someone tagged by hand
function resolveVersion {
    if [ "${GITHUB_REF_TYPE:-}" = "tag" ]; then
        echo "$GITHUB_REF_NAME"
    else
        ./gradlew -q printVersion | tail -n 1
    fi
}

# true when this CI run should publish: a version tag (what tag-release in build-desktop.yml pushes
# on a merge to main), or a manual run on main
function isReleaseRef {
    [ "${GITHUB_REF_TYPE:-}" = "tag" ] && return 0
    [ "${GITHUB_REF_NAME:-}" = "main" ] && return 0
    return 1
}

# write markdown release notes for the last merge to $1, as "- " bullets, never empty.
# $2 is an extended regex of line prefixes to drop (e.g. 'ios|desktop' drops "- iOS: …"), or "" for none
function writeReleaseNotes {
    local out=$1 exclude=${2:-}
    chmod +x scripts/gen_release_notes.sh
    ./scripts/gen_release_notes.sh "pr" "brief" > "$out.raw" || true

    # drop blank lines and empty-subject commits, then normalise to "- " markdown bullets
    grep -v '^[[:space:]]*$' "$out.raw" \
        | grep -viE '^[[:space:]]*-?[[:space:]]*no message[[:space:]]*$' \
        | sed -E 's/^[[:space:]]*-[[:space:]]*//; s/^/- /' > "$out" || true
    rm -f "$out.raw"

    if [ -n "$exclude" ]; then
        grep -viE "^- ($exclude):" "$out" > "$out.tmp" || true
        mv "$out.tmp" "$out"
    fi

    if [ ! -s "$out" ]; then
        echo "- no changes" > "$out"
    fi

    echo "📝 release notes:"
    cat "$out"
}
