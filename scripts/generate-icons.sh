#!/usr/bin/env bash
# Write the adaptive-icon foreground + monochrome VectorDrawables.
# Geometry is fitted to logo.png (two arched n-strokes + period, content
# ratio 0.44 of the 108dp canvas, matching the old density-PNG padding).
# Raster density copies are not generated: one vector replaces five PNGs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

RES_DIR="app/src/main/res"
DRAWABLE="$RES_DIR/drawable"

mkdir -p "$DRAWABLE"

write_vector() {
    local dest="$1"
    local stroke="$2"
    local fill="$3"
    cat > "$dest" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- Geometric "m." fitted to logo.png, scaled into the 108dp adaptive-icon
     canvas at content ratio 0.44 (same padding generate-icons.sh used for PNGs). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#00000000"
        android:pathData="M34.11,65.36 L34.11,42.18 A6.61,6.61 0 0 1 47.33,42.18 L47.33,65.36 M60.5,65.36 L60.5,42.18 A6.61,6.61 0 0 1 73.7,42.18 L73.7,65.36 M47.33,62.41 L60.5,62.41"
        android:strokeWidth="7.78"
        android:strokeColor="${stroke}"
        android:strokeLineCap="square"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="${fill}"
        android:pathData="M69.66,72.26 a4.04,4.04 0 1 1 8.08,0 a4.04,4.04 0 1 1 -8.08,0" />
</vector>
EOF
}

write_vector "$DRAWABLE/ic_launcher_foreground.xml" "#FF000000" "#FF000000"
write_vector "$DRAWABLE/ic_launcher_monochrome.xml" "#FFFFFFFF" "#FFFFFFFF"

# Drop leftover density rasters if a previous run produced them.
for density in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    rm -f "$RES_DIR/drawable-$density/ic_launcher_foreground.png"
    rm -f "$RES_DIR/drawable-$density/ic_launcher_monochrome.png"
    rmdir "$RES_DIR/drawable-$density" 2>/dev/null || true
done

echo "wrote $DRAWABLE/ic_launcher_foreground.xml and ic_launcher_monochrome.xml"
