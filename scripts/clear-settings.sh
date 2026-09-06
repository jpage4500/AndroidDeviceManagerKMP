#!/bin/bash
#######################################################################################################################################
# Delete the desktop app's saved state: settings, logs and crash reports.
#
# Everything lives under ~/.android-device-manager (Branding.desktopHomeDir):
#   settings.properties   every AppPref the app has written
#   logs/                 device-manager.log and its rotated .old
#   crashes/              crash reports not yet uploaded
#
# Usage: clear-settings.sh [--logs-only] [--yes]
#
# The Swing app's equivalent cleared java.util.prefs; this app doesn't use it — see
# docs/APP-SERVICES.md.
#######################################################################################################################################

set -e

HOME_DIR="$HOME/.android-device-manager"

LOGS_ONLY=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
    case "$1" in
        --logs-only) LOGS_ONLY=1; shift ;;
        --yes|-y)    ASSUME_YES=1; shift ;;
        --help|-h)   sed -n '2,16p' "$0"; exit 0 ;;
        *)           echo "❌ Unknown option: $1 (try --help)"; exit 1 ;;
    esac
done

if [ ! -d "$HOME_DIR" ]; then
    echo "Nothing to clear — $HOME_DIR does not exist."
    exit 0
fi

if [ "$LOGS_ONLY" -eq 1 ]; then
    TARGETS=("$HOME_DIR/logs" "$HOME_DIR/crashes")
    WHAT="logs and crash reports"
else
    TARGETS=("$HOME_DIR")
    WHAT="ALL saved state (settings included)"
fi

echo "About to delete $WHAT:"
for T in "${TARGETS[@]}"; do
    [ -e "$T" ] && du -sh "$T" 2>/dev/null | sed 's/^/  /'
done

if [ "$ASSUME_YES" -eq 0 ]; then
    read -r -p "Continue? [y/N] " REPLY
    case "$REPLY" in
        y|Y|yes|Yes) ;;
        *) echo "Aborted."; exit 1 ;;
    esac
fi

for T in "${TARGETS[@]}"; do
    rm -rf "$T"
done

echo "✅ Cleared."
