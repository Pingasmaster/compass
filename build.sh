#!/usr/bin/env bash
#
# Usage:
#   ./build.sh                    # clean + ktlintCheck + detekt + lintRelease + test + assemble + bump version
#                                 # then one-shot NetBird APK HTTP serve at :8765/app-release.apk
#   ./build.sh --clean            # gradle clean + remove APK + exit
#   ./build.sh --format           # ktlintFormat + exit (no build)
#   ./build.sh --build-health     # full build + dependency-analysis buildHealth report
#
# After a successful full build, scripts/apk_http_serve.sh publishes
# http://<netbird-fqdn>:8765/app-release.apk until the first complete download,
# 10 minutes, or the next ./build.sh invocation - whichever happens first.
#
# IMPORTANT: Do NOT manually remove .build.lock unless you have user approval
# and have confirmed no process is currently using it (check with `fuser
# .build.lock` or `lsof .build.lock`). The lock ensures only one build or
# clean runs at a time. Removing it without checking can corrupt builds.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Prefer a real JDK 25, then wrap it so Gradle Worker Daemons (ktlint) also
# get the JEP 498 / JEP 472 opt-in flags. Workers use the daemon's java
# executable by absolute path and ignore JAVA_TOOL_OPTIONS / org.gradle.jvmargs.
if [[ -z "${JAVA_HOME:-}" || "${JAVA_HOME}" == "${SCRIPT_DIR}/.jdk25-home" ]]; then
    unset JAVA_HOME
    for candidate in \
        /usr/lib/jvm/java-25-openjdk-amd64 \
        /usr/lib/jvm/java-25-openjdk \
        "$HOME/.jdks/jdk-25"; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
# shellcheck source=scripts/ensure-jdk25-home.sh
source "$SCRIPT_DIR/scripts/ensure-jdk25-home.sh"

# Also export for any forked JVM that does honor JAVA_TOOL_OPTIONS (client
# launcher, Kotlin compile daemon children outside the worker pool).
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED"

# Any build.sh invocation stops a leftover one-shot APK HTTP serve from a
# previous successful build (also stopped by first download or 10 min timeout).
./scripts/apk_http_serve.sh stop || true

# Argument parsing
DO_CLEAN_ONLY=0
DO_FORMAT=0
DO_BUILD_HEALTH=0

ROOT_APK="app-release.apk"
for arg in "$@"; do
    case "$arg" in
        --clean)        DO_CLEAN_ONLY=1 ;;
        --format)       DO_FORMAT=1 ;;
        --build-health) DO_BUILD_HEALTH=1 ;;
        *) echo "Unknown arg: $arg (accepted: --clean, --format, --build-health)" >&2; exit 2 ;;
    esac
done

# Lock helper: acquire or exit
acquire_lock() {
    LOCKFILE="$SCRIPT_DIR/.build.lock"
    exec 9>"$LOCKFILE"
    if ! flock -n 9; then
        echo "Another build or clean is already running. Exiting."
        exit 1
    fi
    trap 'rm -f "$LOCKFILE"' EXIT
}

# --clean: just run gradle clean and exit, do nothing else
if [[ "$DO_CLEAN_ONLY" -eq 1 ]]; then
    acquire_lock
    ./gradlew clean
    rm -f "$ROOT_APK"
    echo "Clean complete."
    exit 0
fi

# --format: ktlintFormat only, no build, no version bump
if [[ "$DO_FORMAT" -eq 1 ]]; then
    acquire_lock
    ./gradlew ktlintFormat
    echo "ktlintFormat complete. Re-run ./build.sh without --format to verify."
    exit 0
fi

# Default: full build (always starts with clean, includes test if available)
acquire_lock

GRADLE_APK="app/build/outputs/apk/release/app-release.apk"
BUILD_GRADLE="app/build.gradle.kts"

# Build tasks: lint stages first (cheap, fail fast), then assemble.
GRADLE_TASKS=(clean ktlintCheck detekt lintRelease assembleDebug assembleRelease)

# Add test task if project has test sources
if [[ -d "app/src/test" ]]; then
    GRADLE_TASKS+=(testDebugUnitTest)
fi

./gradlew "${GRADLE_TASKS[@]}"

# Optional: dependency-analysis report (informational; not a build gate).
if [[ "$DO_BUILD_HEALTH" -eq 1 ]]; then
    ./gradlew buildHealth || true
    REPORT="build/reports/dependency-analysis/build-health-report.txt"
    [[ -f "$REPORT" ]] && echo "Dependency-analysis report: $REPORT"
fi

# Replace root APK with fresh build
rm -f "$ROOT_APK"
cp "$GRADLE_APK" "$ROOT_APK"
echo "Copied release APK to $ROOT_APK"

# One-shot NetBird sideload URL (first download / 10 min / next build.sh).
./scripts/apk_http_serve.sh start "$ROOT_APK"

# Always bump version
CURRENT_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$BUILD_GRADLE")
CURRENT_NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$BUILD_GRADLE")

NEW_CODE=$((CURRENT_CODE + 1))
NEW_NAME=$(echo "$CURRENT_NAME" | awk -F. -v OFS=. '{$NF=$NF+1; print}')

sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" "$BUILD_GRADLE"
sed -i "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" "$BUILD_GRADLE"

echo "Bumped version: $CURRENT_NAME ($CURRENT_CODE) -> $NEW_NAME ($NEW_CODE)"
