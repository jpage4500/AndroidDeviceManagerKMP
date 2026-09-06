#!/bin/bash
################################################################################
# Sync README to the public release repo
#
# The source lives in a private repo (jpage4500/AndroidDeviceManagerKMP); releases are
# published from a separate PUBLIC one (jpage4500/AndroidDeviceManagerRelease — see
# .github/workflows/build-desktop.yml). This copies README.md and every LOCAL file the
# README references into that repo, then commits and pushes — but only if something
# actually changed.
#
# Only files the README links to are copied, deliberately: a blanket copy of a
# directory would sooner or later publish something out of a private repo that
# was never meant to be public. scripts/public/ is the one exception, and it is
# copied whole precisely because that is what the folder is for.
#
# NOTE: the public README is overwritten on every run — edit this repo's copy,
#       never the public one.
# NOTE: an image dropped from the README is not deleted from the public repo.
#
# usage: sync_public_readme.sh <owner/repo>
#        sync_public_readme.sh <url-or-path>    # for testing against a local repo
#
# requires: GH_TOKEN with "Contents: read and write" on <owner/repo>
################################################################################

set -euo pipefail

TARGET=${1:-}

if [[ -z "$TARGET" ]]; then
    echo >&2 "usage: $0 <owner/repo>"
    exit 1
fi

# change from /scripts to the repo root
cd "$(dirname "$0")"
cd ..

################################################################################
# collect README.md + every local file it references
################################################################################

# matches both markdown "](path)" and html 'src="path"', then drops anything
# remote (shields.io badges, release links) and in-page anchors
ASSETS=$(grep -oE '(src="|\]\()[^"()[:space:]]+' README.md |
    sed -E 's/^(src="|\]\()//' |
    grep -vE '^(https?:|#|mailto:)' |
    sort -u || true)

# NOTE: there is no always-synced file list here. The weather app's copy of this script force-syncs
# PRIVACY.md, because both its store listings point a reviewer at that URL in the public repo and
# review rejects an app whose privacy policy 404s. This app is in no store yet. The day it is
# submitted anywhere, add the file here rather than leaving it to the link parser below — an
# unrelated README edit could otherwise silently un-publish it.

# Everything under scripts/public/ and docs/public/ goes over too, linked or not -- those
# folders exist to hold what a user reads or runs straight off the public repo, so the rule
# for what belongs in them is simply "is this meant to be public". Don't put anything else
# there. scripts/public/ is for scripts, docs/public/ for pages the README sends people to.
#
# It can't be left to the link parser: a README reaches a script through a
# raw.githubusercontent.com URL, the whole point of a paste-into-a-shell command, and the
# filter above drops every remote URL. A missing file there is a landing page telling
# people to run a command that 404s. A doc page is usually linked and so would be caught
# anyway, but only the README is parsed -- an image that page embeds would not be.
#
# An empty or absent folder is not an error: this app has no install scripts yet, and the
# README links people at the release page instead. Add one and it starts syncing with no
# change to this script.
PUBLIC_DIRS=("scripts/public" "docs/public")
PUBLIC_FILES=""
PUBLIC_NOTE=""
for DIR in "${PUBLIC_DIRS[@]}"; do
    FOUND=$(find "$DIR" -type f ! -name '.*' 2>/dev/null | sort || true)
    if [[ -z "$FOUND" ]]; then
        echo "note: $DIR is missing or empty — nothing to sync from it"
        continue
    fi
    PUBLIC_FILES=$(printf '%s\n%s\n' "$PUBLIC_FILES" "$FOUND")
    PUBLIC_NOTE="${PUBLIC_NOTE} and all of $DIR/"
done

ASSETS=$(printf '%s\n%s\n' "$ASSETS" "$PUBLIC_FILES" | grep -v '^$' | sort -u || true)

echo "copying README.md, the files it links to${PUBLIC_NOTE}:"
if [[ -n "$ASSETS" ]]; then
    echo "$ASSETS" | sed 's/^/  /'
else
    echo "  (none)"
fi

# a broken image in the public README is worse than a failed job — stop here
MISSING=""
for FILE in $ASSETS; do
    if [[ ! -f "$FILE" ]]; then
        MISSING="${MISSING}  ${FILE}"$'\n'
    fi
done
if [[ -n "$MISSING" ]]; then
    echo >&2 "ERROR: files to sync don't exist (are they committed?):"
    echo >&2 "$MISSING"
    exit 1
fi

################################################################################
# clone the target, copy the files in, push if anything changed
################################################################################

# an owner/repo pair becomes an authenticated github URL; anything that already
# looks like a URL or a path is used as-is, so this can be run against a local repo
if [[ "$TARGET" == *"://"* || "$TARGET" == /* ]]; then
    REMOTE="$TARGET"
else
    if [[ -z "${GH_TOKEN:-}" ]]; then
        echo >&2 "ERROR: GH_TOKEN not set"
        exit 1
    fi
    REMOTE="https://x-access-token:${GH_TOKEN}@github.com/${TARGET}.git"
fi

SOURCE_SHA=$(git rev-parse --short HEAD)
SOURCE_REPO=${GITHUB_REPOSITORY:-$(basename "$PWD")}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# no --depth: the repo may well be empty on the first run, and it's tiny either way
git clone --quiet "$REMOTE" "$WORK/target"

# on a fresh clone this is the target's default branch, even when it has no commits yet
BRANCH=$(git -C "$WORK/target" branch --show-current)
BRANCH=${BRANCH:-main}

cp README.md "$WORK/target/README.md"
for FILE in $ASSETS; do
    mkdir -p "$WORK/target/$(dirname "$FILE")"
    cp "$FILE" "$WORK/target/$FILE"
    # a curl | bash script is useless if the mode bit doesn't survive the copy.
    # spelled as an if rather than [[ ]] && ...: a false test on the last file would be
    # the loop's exit status, and set -e would take that as the script failing
    if [[ -x "$FILE" ]]; then
        chmod +x "$WORK/target/$FILE"
    fi
done

cd "$WORK/target"
git config user.name "GitHub Actions Bot"
git config user.email "<>"
git add -A

if git diff --cached --quiet; then
    echo "✅ README, images and scripts already up to date — nothing to push"
    exit 0
fi

echo "▶ pushing to ${TARGET} (${BRANCH}):"
git diff --cached --name-status | sed 's/^/  /'

git commit --quiet -m "docs: sync README from ${SOURCE_REPO}@${SOURCE_SHA}"
git push --quiet origin "HEAD:${BRANCH}"
echo "✅ pushed"
