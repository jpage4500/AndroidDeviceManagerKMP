#!/usr/bin/env bash
# generate-icons.sh — regenerates every platform launcher icon from resources/images/logo.png.
# Idempotent; safe to re-run.
#
# SOURCE (do not delete):
#   resources/images/logo.png — the app's artwork, 1024x1024, pixel-for-pixel the icon the Swing
#   app has always shipped: a squircle tile with a green-to-blue gradient, the robot, the fan of
#   phones and the beams, with the four canvas corners transparent.
#
# WHY A PNG AND NOT AN SVG. The sister repo (DashboardWeatherKMP) drives the same pipeline from
# two SVGs, which is better in every way this artwork cannot support. It is a raster illustration
# built out of soft gradients, gloss highlights, translucent glass and blurred beams. Traced, it
# posterizes: measured on this file, even a 128-colour undithered quantisation — the ceiling a
# potrace layer-stack could reach — bands visibly across the background gradient and turns the
# beam glow into hard steps, and it would cost tens of thousands of path nodes to do it. Wrapping
# the PNG in an <image> element would be an SVG in name only. So the raster is the source, and the
# two things vector bought us are handled here instead: the corner fill below, and the fact that
# every output is a downscale of a 1024 master, never an upscale.
#
# THE SQUARED MASTER. iOS and adaptive-Android composite the icon onto their own background and
# then apply their own corner mask, so both need a source that runs edge to edge — feed either one
# a tile that is already rounded and you get corners rounded twice, with the wallpaper or a flat
# fill showing through the gap. logo.png's corners are transparent, so square_fill() below
# synthesises them. It does NOT invent a gradient: the fill is the artwork itself enlarged 1.3x
# and blurred, so the colour continues the real background including its slight edge lift, and the
# artwork is laid back over it through a squircle eroded 26px. The erosion is what matters — the
# tile carries a pale bevel rim, and without dropping it that rim reads as an arc stranded inside
# the square.
#
# ADAPTIVE BACKGROUND COLOR (kept in sync in two places — currently #73A184, the artwork's mean):
#   - androidApp/src/main/res/values/ic_launcher_background.xml
#   - BG_COLOR below, which also backs flatten_rgb for the iOS marketing icon.
# It is barely ever seen: the adaptive foreground is inset to exactly the 72dp viewport, so the
# background only appears in the moment a launcher parallaxes the layers apart.
#
# Required tools (Homebrew names in parens):
#   cwebp (webp), iconutil (built-in macOS), magick (imagemagick)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

# ─── usage ───────────────────────────────────────────────────────────────────
usage() {
  cat >&2 <<EOF
Usage: $0

Regenerates every platform launcher icon from resources/images/logo.png (1024x1024).
Replace that file with your own artwork and re-run. Idempotent.
EOF
  exit 1
}

if [[ $# -gt 0 ]]; then
  echo "ERROR: too many arguments (this script takes none)" >&2
  usage
fi

SRC="resources/images/logo.png"
ADAPTIVE_INSET=667          # per-mille of the 108dp adaptive canvas the foreground is drawn at.
                            # 72/108 = 0.667, so the artwork lands exactly on the viewport the
                            # launcher masks: every mask shape is inscribed in that 72dp square,
                            # so the artwork covers all of them and the background never shows
                            # except under parallax. A circular mask still trims the corners —
                            # unavoidable for full-bleed artwork, and the phones at the bottom
                            # corners are what it takes off first.
BG_COLOR="#73A184"          # see header note

echo "▶ Generating icons (source: $SRC)"

# ─── tool validation ────────────────────────────────────────────────────────
missing=()
for cmd in cwebp iconutil magick; do
  command -v "$cmd" >/dev/null 2>&1 || missing+=("$cmd")
done
if (( ${#missing[@]} > 0 )); then
  echo "ERROR: missing required tools: ${missing[*]}" >&2
  echo "Install with: brew install webp imagemagick" >&2
  echo "(iconutil ships with macOS; if missing, install Xcode Command Line Tools.)" >&2
  exit 1
fi

[[ -f "$SRC" ]] || { echo "ERROR: source artwork not found: $SRC" >&2; exit 1; }
SRC_W="$(magick identify -format '%w' "$SRC")"
(( SRC_W >= 1024 )) || {
  echo "ERROR: $SRC is ${SRC_W}px wide; 1024 is the largest icon generated and nothing is upscaled" >&2
  exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ─── helpers ────────────────────────────────────────────────────────────────
# magick stamps a creation date into every PNG it writes, which makes otherwise identical re-runs
# produce different bytes and show up as churn in git. Every magick PNG write below passes
# NO_DATE, and -strip, so this script's output is reproducible.
NO_DATE=(-strip -define png:exclude-chunks=date,time)

# fill the source's transparent corners so the artwork runs edge to edge — see header note
square_fill() {  # square_fill <in.png> <out.png>
  local size
  size="$(magick identify -format '%w' "$1")"
  magick "$1" -resize 130% -gravity center -extent "${size}x${size}" +repage \
    -blur "0x$(( size * 20 / 1024 ))" "${NO_DATE[@]}" "$TMP/sf-base.png"
  magick -size "${size}x${size}" xc:black -fill white \
    -draw "roundrectangle $(( size * 26 / 1024 )),$(( size * 26 / 1024 )) \
           $(( size - 1 - size * 26 / 1024 )),$(( size - 1 - size * 26 / 1024 )) \
           $(( size * 203 / 1024 )),$(( size * 203 / 1024 ))" \
    -blur "0x$(( size * 9 / 1024 ))" "${NO_DATE[@]}" "$TMP/sf-mask.png"
  magick "$1" "$TMP/sf-mask.png" -alpha off -compose CopyOpacity -composite "${NO_DATE[@]}" "$TMP/sf-core.png"
  magick "$TMP/sf-base.png" "$TMP/sf-core.png" -gravity center -compose over -composite \
    -alpha remove -alpha off +repage "${NO_DATE[@]}" "$2"
}

resize() {  # resize <src.png> <size> <out.png>
  magick "$1" -filter Lanczos -resize "${2}x${2}" "${NO_DATE[@]}" "$3"
}

png_to_webp() {  # png_to_webp <in.png> <out.webp>
  cwebp -q 90 -mt -quiet "$1" -o "$2"
}

# circular alpha mask: fully opaque inside the inscribed circle, transparent outside.
mask_circle() {  # mask_circle <in.png> <out.png>
  magick "$1" \
    \( +clone -alpha extract -threshold -1 \
       -fill black -draw "color 0,0 reset" \
       -fill white -draw "circle %[fx:w/2-0.5],%[fx:h/2-0.5] %[fx:w/2-0.5],0" \) \
    -alpha off -compose CopyOpacity -composite "${NO_DATE[@]}" "$2"
}

# squircle (rounded-rect) alpha mask: opaque inside an Apple-style rounded rectangle, transparent
# outside. Radius is 22.37% of the canvas (the conventional roundrectangle approximation of
# Apple's superellipse) — the same radius the source artwork's own tile uses.
mask_squircle() {  # mask_squircle <in.png> <out.png>
  magick "$1" \
    \( +clone -alpha extract -threshold -1 \
       -fill black -draw "color 0,0 reset" \
       -fill white -draw "roundrectangle 0,0 %[fx:w-1],%[fx:h-1] %[fx:w*0.2237],%[fx:h*0.2237]" \) \
    -alpha off -compose CopyOpacity -composite "${NO_DATE[@]}" "$2"
}

# squircle mask WITH the macOS "icon grid" inset: the rounded body fills only ~80% of the canvas
# (824 of 1024) centred on transparent margin, so the tile matches the size of system icons in the
# Dock / ⌘-Tab switcher. macOS (and most Linux desktops) do NOT inset app icons themselves —
# unlike iOS/adaptive-Android, which is why only the desktop icons need the margin baked in.
# Corner radius stays 22.37% of the *body* (Apple-correct), not of the full canvas.
mask_squircle_inset() {  # mask_squircle_inset <in.png> <out.png>
  local size body
  size="$(magick identify -format '%w' "$1")"
  body=$(( size * 824 / 1024 ))
  resize "$1" "$body" "$TMP/inset-src.png"
  mask_squircle "$TMP/inset-src.png" "$TMP/inset-body.png"
  magick -size "${size}x${size}" xc:none "$TMP/inset-body.png" -gravity center -composite \
    "${NO_DATE[@]}" "$2"
}

flatten_rgb() {  # flatten_rgb <in.png> <out.png>  — strips alpha onto BG_COLOR
  magick "$1" -background "$BG_COLOR" -alpha remove -alpha off "${NO_DATE[@]}" "$2"
}

# ─── the squared master every output is cut from ────────────────────────────
echo "▶ Squared full-bleed master (corner fill)"
MASTER="$TMP/master.png"
square_fill "$SRC" "$MASTER"

# ─── Android: legacy square ic_launcher.webp ────────────────────────────────
# referenced from AndroidManifest.xml as @mipmap/ic_launcher — pre-Oreo launchers, and the
# fallback when the adaptive icon can't render. Cut from the SOURCE, not the master: a legacy
# launcher draws the bitmap as-is with no mask of its own, so the artwork's own squircle tile is
# the shape that belongs here.
echo "▶ Android ic_launcher.webp (legacy square)"
for entry in "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192"; do
  density="${entry%%:*}"; px="${entry##*:}"
  mkdir -p "androidApp/src/main/res/mipmap-${density}"
  resize "$SRC" "$px" "$TMP/sq-$density.png"
  png_to_webp "$TMP/sq-$density.png" "androidApp/src/main/res/mipmap-${density}/ic_launcher.webp"
done

# ─── Android: legacy round ic_launcher_round.webp ───────────────────────────
# referenced from AndroidManifest.xml as @mipmap/ic_launcher_round.
echo "▶ Android ic_launcher_round.webp (legacy round)"
for entry in "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192"; do
  density="${entry%%:*}"; px="${entry##*:}"
  resize "$MASTER" "$px" "$TMP/round-src-$density.png"
  mask_circle "$TMP/round-src-$density.png" "$TMP/round-$density.png"
  png_to_webp "$TMP/round-$density.png" "androidApp/src/main/res/mipmap-${density}/ic_launcher_round.webp"
done

# ─── Android: adaptive foreground ic_launcher_foreground.webp ───────────────
# consumed by mipmap-anydpi-v26/ic_launcher.xml and ic_launcher_round.xml as the <foreground>
# layer, over @color/ic_launcher_background. Sized to 108dp at each density (Android's
# adaptive-icon canvas), with the artwork drawn at ADAPTIVE_INSET of it — see that constant.
echo "▶ Android ic_launcher_foreground.webp (adaptive, inset to ${ADAPTIVE_INSET} per-mille)"
for entry in "mdpi:108" "hdpi:162" "xhdpi:216" "xxhdpi:324" "xxxhdpi:432"; do
  density="${entry%%:*}"; px="${entry##*:}"
  inner=$(( px * ADAPTIVE_INSET / 1000 ))
  resize "$MASTER" "$inner" "$TMP/fg-inner-$density.png"
  magick -size "${px}x${px}" xc:none \
    "$TMP/fg-inner-$density.png" -gravity center -composite "${NO_DATE[@]}" "$TMP/fg-$density.png"
  png_to_webp "$TMP/fg-$density.png" "androidApp/src/main/res/mipmap-${density}/ic_launcher_foreground.webp"
done

# ─── iOS: AppIcon set ───────────────────────────────────────────────────────
# filename → pixel-size table derived from iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json
# (which is authoritative — Xcode regenerates it. If sizes drift, update this table.) The set an
# iOS build uses is the ASSETCATALOG_COMPILER_APPICON_NAME build setting, already AppIcon in
# iosApp.xcodeproj. iOS masks the icon itself, so these are the squared master, and the marketing
# 1024 is flattened because the App Store rejects alpha.
echo "▶ iOS AppIcon.appiconset"
IOS_DIR="iosApp/Assets.xcassets/AppIcon.appiconset"
[[ -f "$IOS_DIR/Contents.json" ]] || {
  echo "ERROR: $IOS_DIR/Contents.json is missing — it is committed, not generated" >&2; exit 1; }
declare -a IOS_ICONS=(
  "AppIcon-20@2x.png:40"
  "AppIcon-20@2x~ipad.png:40"
  "AppIcon-20@3x.png:60"
  "AppIcon-20~ipad.png:20"
  "AppIcon-29.png:29"
  "AppIcon-29@2x.png:58"
  "AppIcon-29@2x~ipad.png:58"
  "AppIcon-29@3x.png:87"
  "AppIcon-29~ipad.png:29"
  "AppIcon-40@2x.png:80"
  "AppIcon-40@2x~ipad.png:80"
  "AppIcon-40@3x.png:120"
  "AppIcon-40~ipad.png:40"
  "AppIcon-83.5@2x~ipad.png:167"
  "AppIcon@2x.png:120"
  "AppIcon@2x~ipad.png:152"
  "AppIcon@3x.png:180"
)
for entry in "${IOS_ICONS[@]}"; do
  name="${entry%%:*}"; px="${entry##*:}"
  resize "$MASTER" "$px" "$IOS_DIR/$name"
done
flatten_rgb "$MASTER" "$IOS_DIR/AppIcon~ios-marketing.png"

# ─── Desktop macOS .icns ────────────────────────────────────────────────────
# consumed by composeApp/build.gradle.kts > nativeDistributions > macOS > iconFile. iconutil
# expects a directory named *.iconset/ with this exact filename scheme. Ten sizes on purpose: a
# one-slice icns gets drawn shrunk onto a grey plate by the Tahoe Dock however good its geometry
# is — measured in the Swing repo's scripts/generate-icon.sh, which carries the details.
echo "▶ Desktop macOS icon.icns"
ICONSET="$TMP/Icon.iconset"
mkdir -p "$ICONSET"
declare -a MAC_SIZES=(
  "icon_16x16.png:16"
  "icon_16x16@2x.png:32"
  "icon_32x32.png:32"
  "icon_32x32@2x.png:64"
  "icon_128x128.png:128"
  "icon_128x128@2x.png:256"
  "icon_256x256.png:256"
  "icon_256x256@2x.png:512"
  "icon_512x512.png:512"
  "icon_512x512@2x.png:1024"
)
for entry in "${MAC_SIZES[@]}"; do
  name="${entry%%:*}"; px="${entry##*:}"
  resize "$MASTER" "$px" "$TMP/mac-src-$name"
  mask_squircle_inset "$TMP/mac-src-$name" "$ICONSET/$name"
done
iconutil --convert icns --output "composeApp/src/desktopMain/resources/icon.icns" "$ICONSET"

# ─── Desktop Windows .ico ───────────────────────────────────────────────────
# consumed by composeApp/build.gradle.kts > nativeDistributions > windows > iconFile.
echo "▶ Desktop Windows icon.ico"
for px in 16 32 48 64 128 256; do
  resize "$SRC" "$px" "$TMP/win-$px.png"
done
magick \
  "$TMP/win-16.png" "$TMP/win-32.png" "$TMP/win-48.png" \
  "$TMP/win-64.png" "$TMP/win-128.png" "$TMP/win-256.png" \
  "${NO_DATE[@]}" "composeApp/src/desktopMain/resources/icon.ico"

# ─── Desktop Linux icon.png ─────────────────────────────────────────────────
# consumed by composeApp/build.gradle.kts > nativeDistributions > linux > iconFile. The gradle
# reference is lowercase "icon.png" — on macOS's case-insensitive FS a capital-I file would work
# here and break on Linux/CI, so keep it lowercase.
echo "▶ Desktop Linux icon.png"
resize "$MASTER" 512 "$TMP/linux.png"
mask_squircle_inset "$TMP/linux.png" "composeApp/src/desktopMain/resources/icon.png"

# ─── Repo-root icon.png (jDeploy installer) ─────────────────────────────────
# jDeploy has no config key for the icon path — it always reads ./icon.png next to package.json
# (see docs/RELEASING.md). Full-bleed squircle rather than the inset one the .icns gets: the
# jDeploy installer regenerates the macOS icns from this file on the user's machine, and an icon
# that is already inset would come out inset twice. Cut from the master and re-masked rather than
# copied from the source, so the corner geometry is this script's and not the artwork's.
echo "▶ Repo-root icon.png (jDeploy installer icon)"
mask_squircle "$MASTER" "icon.png"
# jdeploy-bundle/ is gitignored (jDeploy regenerates it), so it's absent in a fresh checkout —
# create it or this cp aborts the whole script under `set -e`.
mkdir -p jdeploy-bundle
cp "icon.png" "jdeploy-bundle/icon.png"

# The mark at the top of the desktop sidebar (ui/shell/Sidebar.kt). The source as it is — its own
# squircle and transparent corners — at 256px, which keeps the rail's 34dp logo sharp at 3x without
# shipping the 1MB master inside the app.
echo "▶ Sidebar logo (composeResources/drawable/app_logo.png)"
resize "$SRC" 256 "composeApp/src/commonMain/composeResources/drawable/app_logo.png"

# ─── done ───────────────────────────────────────────────────────────────────
echo ""
echo "Done. Review with:  git status && git diff --stat"
