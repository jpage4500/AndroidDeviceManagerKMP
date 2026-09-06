#!/bin/bash
#######################################################################################################################################
# Push every secret the GitHub Actions workflows read into this repo's Actions secrets.
#
# Values come from the environment first, then ~/.gradle/gradle.properties — the same resolution
# order composeApp/build.gradle.kts uses, so what CI builds with matches what a local build uses.
# The target repo is the 'origin' remote of this checkout.
#
# Usage: set-github-secrets.sh [--dry-run] [--yes]
#   --dry-run   show what would be set (names + value lengths only, never values)
#   --yes       skip the confirmation prompt
#
# Requires the 'gh' CLI, authenticated with admin rights on the repo ('gh auth login').
# Existing secret values are overwritten and cannot be read back, so re-running is safe only if
# your local values are the ones you want.
#######################################################################################################################################

set -e

cd "$(dirname "$0")/.."

# Function to fetch properties from the environment or gradle.properties
function getProperty {
    PROP_KEY=$1
    if [ -n "${!PROP_KEY:-}" ]; then
        echo "${!PROP_KEY}"
    else
        # Suppress grep error if file doesn't exist, returning empty rather than failing
        PROP_VALUE=$(grep "^$PROP_KEY=" ~/.gradle/gradle.properties 2>/dev/null | cut -d'=' -f2-)
        echo "${PROP_VALUE}"
    fi
}

# every secret the workflows reference, in workflow order.
#
# short list on purpose: nothing here signs an android release or uploads to TestFlight yet, so
# there are no keystore or App Store Connect secrets to set. DashboardWeatherKMP's copy of this
# script has that longer list when it's time — see docs/RELEASING.md.
SECRETS=(
    # app build config — where LogManager uploads a log to (composeApp/build.gradle.kts)
    LOGGING_URL
    LOGGING_API

    # github releases: a PAT with contents:write on THIS repo (to push the version tag) and on
    # jpage4500/AndroidDeviceManagerRelease (to create the release and sync the README)
    GH_TOKEN
)

# ==========================================
# Parse arguments
# ==========================================
DRY_RUN=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --yes|-y)  ASSUME_YES=1; shift ;;
        --help|-h) sed -n '2,16p' "$0"; exit 0 ;;
        *)         echo "❌ Unknown option: $1 (try --help)"; exit 1 ;;
    esac
done

# ==========================================
# Verify gh CLI + resolve the repo from the origin remote
# ==========================================
if ! command -v gh >/dev/null 2>&1; then
    echo "❌ The GitHub CLI ('gh') is required: brew install gh"
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "❌ Not logged in to GitHub. Run: gh auth login"
    exit 1
fi

ORIGIN=$(git remote get-url origin 2>/dev/null || echo "")
if [ -z "$ORIGIN" ]; then
    echo "❌ No 'origin' remote found in this checkout."
    exit 1
fi

# handles both git@github.com:owner/name.git and https://github.com/owner/name.git
REPO=$(echo "$ORIGIN" | sed -E 's#^.*github\.com[:/]##; s#\.git$##')
if [ -z "$REPO" ] || [[ "$REPO" != */* ]]; then
    echo "❌ Could not parse owner/name from the origin remote: $ORIGIN"
    exit 1
fi

echo "Target repo: $REPO"
echo "Reading values from the environment, then ~/.gradle/gradle.properties"
echo ""

# ==========================================
# Resolve every value up front so nothing is written until the whole list is checked
# ==========================================
NAMES=()
VALUES=()
SKIPPED=0

for NAME in "${SECRETS[@]}"; do
    VALUE=$(getProperty "$NAME")

    if [ -z "$VALUE" ]; then
        echo "⏭  $NAME not set, skipping"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    # never print the value itself — length is enough to spot a truncated paste
    printf '✅ %-32s (%s chars)\n' "$NAME" "$(printf '%s' "$VALUE" | wc -c | tr -d ' ')"
    NAMES+=("$NAME")
    VALUES+=("$VALUE")
done

echo ""

if [ "${#NAMES[@]}" -eq 0 ]; then
    echo "Nothing to set — check your environment or ~/.gradle/gradle.properties."
    exit 1
fi

# ==========================================
# Confirm, then write
# ==========================================
if [ "$DRY_RUN" -eq 1 ]; then
    echo "🔍 Dry run — ${#NAMES[@]} secret(s) would be set on $REPO, $SKIPPED skipped. Nothing was written."
    exit 0
fi

if [ "$ASSUME_YES" -eq 0 ]; then
    echo "About to overwrite ${#NAMES[@]} Actions secret(s) on $REPO."
    echo "Existing values can't be read back, so this is not reversible."
    read -r -p "Continue? [y/N] " REPLY
    case "$REPLY" in
        y|Y|yes|Yes) ;;
        *) echo "Aborted."; exit 1 ;;
    esac
    echo ""
fi

FAILED=0
for i in "${!NAMES[@]}"; do
    NAME="${NAMES[$i]}"
    if gh secret set "$NAME" --repo "$REPO" --body "${VALUES[$i]}" >/dev/null 2>&1; then
        echo "  set $NAME"
    else
        echo "  ❌ failed to set $NAME"
        FAILED=$((FAILED + 1))
    fi
done

echo ""
if [ "$FAILED" -gt 0 ]; then
    echo "🚨 $FAILED secret(s) failed — check that your account has admin access to $REPO."
    exit 1
fi

echo "🎉 Set ${#NAMES[@]} secret(s) on $REPO ($SKIPPED skipped)."
echo "   Review them at: https://github.com/$REPO/settings/secrets/actions"
