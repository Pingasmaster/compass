#!/usr/bin/env bash
# Generate Android adaptive-icon foreground + monochrome PNGs from logo.png.
# Produces drawable-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png and _monochrome.png.
# Safe zone: content fits within 66/108 = 61% of canvas (we target 55% for padding).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

SRC="logo.png"
BG_COLOR="#F6F4F0"   # cream background sampled from logo.png
FUZZ="8%"            # tolerance when stripping background
CONTENT_RATIO="0.44" # content fraction of canvas (safe zone is 0.61) — extra padding so the logo sits comfortably inside every mask

# Density → foreground canvas size (px). Foreground is 108dp; 1dp = 1px at mdpi.
declare -A DENSITIES=(
    [mdpi]=108
    [hdpi]=162
    [xhdpi]=216
    [xxhdpi]=324
    [xxxhdpi]=432
)

RES_DIR="app/src/main/res"

# Build a "masked" version of the logo: cream → transparent, keep dark content.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

MASKED="$TMP/masked.png"
magick "$SRC" -alpha set -fuzz "$FUZZ" -transparent "$BG_COLOR" "$MASKED"

# Crop masked image to bounding box of non-transparent pixels, for clean scaling.
CROPPED="$TMP/cropped.png"
magick "$MASKED" -trim +repage "$CROPPED"

# Derive a pure-white silhouette for the monochrome layer.
# Any non-transparent pixel → opaque white.
MONOCROP="$TMP/mono_cropped.png"
magick "$CROPPED" \
    -alpha extract "$TMP/alpha.png"
magick "$TMP/alpha.png" \
    -background none \
    -alpha copy \
    -fill white -colorize 100 \
    "$MONOCROP"

for density in "${!DENSITIES[@]}"; do
    size="${DENSITIES[$density]}"
    content_size=$(awk -v s="$size" -v r="$CONTENT_RATIO" 'BEGIN{printf "%d", s*r}')
    out_dir="$RES_DIR/drawable-$density"
    mkdir -p "$out_dir"

    # Foreground (colored)
    magick "$CROPPED" \
        -resize "${content_size}x${content_size}" \
        -background none -gravity center -extent "${size}x${size}" \
        -define png:color-type=6 \
        "$out_dir/ic_launcher_foreground.png"

    # Monochrome (white silhouette)
    magick "$MONOCROP" \
        -resize "${content_size}x${content_size}" \
        -background none -gravity center -extent "${size}x${size}" \
        -define png:color-type=6 \
        "$out_dir/ic_launcher_monochrome.png"

    echo "generated $density (${size}px, content ${content_size}px)"
done

echo "done"
