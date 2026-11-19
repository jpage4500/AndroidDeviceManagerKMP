#!/bin/bash
#######################################################################################################################################
# Clear macOS's icon caches and rebuild the LaunchServices database, so the Dock and ⌘-Tab redraw the
# app's icon from its bundle instead of a cached copy.
#
# Needed after an icon change, and on macOS 26 whenever the app's icon shows up shrunk onto a grey
# plate: the OS caches that plated composite and keeps drawing it after the icon itself is fixed.
#
# Usage: scripts/reset-icon.sh [path/to/App.app]
#   default app: ~/Applications/Android Device Manager.app (where jDeploy installs it), if present
#
# Asks for sudo (the system-wide icon store), and restarts the Dock and Finder. If the icon is still
# stale afterwards, log out and back in.
#######################################################################################################################################

APP="${1:-$HOME/Applications/Android Device Manager.app}"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister

if [ -n "$1" ] && [ ! -d "$APP" ]; then
    echo "not an app bundle: $APP"
    exit 1
fi
[ -d "$APP" ] || APP=""

CACHE_DIR="$(getconf DARWIN_USER_CACHE_DIR)"
CACHE_DIR="${CACHE_DIR%/}"
if [ -z "$CACHE_DIR" ]; then
    echo "can't resolve DARWIN_USER_CACHE_DIR"
    exit 1
fi

echo "this clears every app's cached icon and restarts the Dock and Finder"
[ -n "$APP" ] && echo "and re-registers: $APP"
read -r -p "continue? [y/N] " answer
case "$answer" in
    [yY] | [yY][eE][sS]) ;;
    *)
        echo "aborted"
        exit 1
        ;;
esac

# stop the icon daemons first - they hold the store open and write stale entries back
sudo killall iconservicesd iconservicesagent 2>/dev/null

sudo rm -rf /Library/Caches/com.apple.iconservices.store
rm -rf "$CACHE_DIR/com.apple.iconservices.store"
rm -rf "$CACHE_DIR/com.apple.iconservicesagent"
rm -f "$CACHE_DIR/com.apple.dock.iconcache"

# Dock/task-switcher icons come from the LaunchServices db, not just the pixel cache
"$LSREGISTER" -kill -r -domain local -domain system -domain user
if [ -n "$APP" ]; then
    touch "$APP"
    "$LSREGISTER" -f "$APP"
fi

killall Dock
killall Finder
echo "done - if the icon is still stale, log out and back in"
