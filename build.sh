#!/usr/bin/env bash
#
# Usage:
#   ./build.sh                 # incremental build, no version bump
#   ./build.sh --clean         # force a clean build
#   ./build.sh --bump          # after a successful build, bump version
#   ./build.sh --clean --bump  # both
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

DO_CLEAN=0
DO_BUMP=0
for arg in "$@"; do
    case "$arg" in
        --clean) DO_CLEAN=1 ;;
        --bump)  DO_BUMP=1 ;;
        *) echo "Unknown arg: $arg (accepted: --clean, --bump)" >&2; exit 2 ;;
    esac
done

LOCKFILE="$SCRIPT_DIR/.build.lock"
exec 9>"$LOCKFILE"
if ! flock -n 9; then
    echo "Another build is already running."
    exit 1
fi
trap 'rm -f "$LOCKFILE"' EXIT

GRADLE_APK="app/build/outputs/apk/release/app-release.apk"
ROOT_APK="app-release.apk"
BUILD_GRADLE="app/build.gradle.kts"

GRADLE_TASKS=(lint assembleDebug assembleRelease test)
if [[ "$DO_CLEAN" -eq 1 ]]; then
    GRADLE_TASKS=(clean "${GRADLE_TASKS[@]}")
fi

JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew "${GRADLE_TASKS[@]}"

rm -f "$ROOT_APK"
cp "$GRADLE_APK" "$ROOT_APK"
echo "Copied release APK to $ROOT_APK"

if [[ "$DO_BUMP" -eq 1 ]]; then
    CURRENT_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$BUILD_GRADLE")
    CURRENT_NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$BUILD_GRADLE")
    NEW_CODE=$((CURRENT_CODE + 1))
    NEW_NAME=$(echo "$CURRENT_NAME" | awk -F. -v OFS=. '{$NF=$NF+1; print}')
    sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" "$BUILD_GRADLE"
    sed -i "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" "$BUILD_GRADLE"
    echo "Bumped version: $CURRENT_NAME ($CURRENT_CODE) -> $NEW_NAME ($NEW_CODE)"
fi
