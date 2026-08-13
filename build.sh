#!/usr/bin/env bash
#
# Usage:
#   ./build.sh                    # RELEASE path: regen baseline+startup profiles (GMD),
#                                 # bump version, clean + ASCII + ktlint + detekt + lint
#                                 # + tests + assemble harnesses + assemble APKs,
#                                 # then one-shot NetBird APK HTTP serve for both APKs
#   ./build.sh --debug            # DEV path: same gates/assemble, but skip release-only
#                                 # steps (baseline regen + version bump), then copy the
#                                 # debug APKs to the repo root and serve those
#   ./build.sh --clean            # gradle clean + remove APKs + exit
#   ./build.sh --format           # ktlintFormat + exit (no build)
#   ./build.sh --build-health     # full build + dependency-analysis buildHealth report
#   ./build.sh --smoke            # GMD Pixel 7a API 37 @SmokeTest (future)
#   ./build.sh --e2e              # GMD hermetic androidTest (no LiveNetwork filter)
#   ./build.sh --smoke-shipped    # :shippedsmoke release lane (future)
#   ./build.sh --macrobenchmark   # advisory emulator macrobenchmarks (future)
#   ./build.sh --publish          # serve existing root APKs over NetBird HTTP + exit
#   ./build.sh --publish-debug    # serve the last built debug APKs over NetBird HTTP + exit
#   ./build.sh --block-on-outdated
#                                 # refuse to build when any catalog pin is behind
#                                 # (default is to auto-bump pins, then continue)
#
# Every build mode first runs scripts/check_latest_deps.py --apply, which bumps
# any version in gradle/libs.versions.toml that is behind the newest release
# published to Google Maven / Maven Central / the Gradle Plugin Portal, prints
# what changed, then continues. Pre-releases count: alphas, betas and RCs are
# all valid "latest" targets. Pass --block-on-outdated to keep the old
# refuse-to-build behavior instead of rewriting the catalog.
#
# After a successful full build, scripts/apk_http_serve.sh publishes all four
# root APKs (compat/future x release/debug) until they are downloaded once,
# 10 minutes, or the next ./build.sh invocation.
#
# User-facing speed: default builds ALWAYS regenerate baseline-prof.txt +
# startup-prof.txt (needs KVM) so release APKs ship fresh AOT hints. R8
# minify + resource shrink already run on assemble*Release. Macrobenchmark
# only measures - it does not speed up users, so it stays opt-in.
#
# IMPORTANT: Do NOT manually remove the global Android-apps build lock unless
# you have user approval and have confirmed no process is using it (check with
# `fuser ~/.cache/android-apps/build.lock` or `lsof` on that path). The lock is
# shared across dustvalve_next, calc, compass, STT_premium, and Token Maxer
# so only one of those builds/cleans runs at a time. Deleting the file while
# a holder is alive can break flock (new openers get a new inode).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

./scripts/apk_http_serve.sh stop || true

if [[ -z "${JAVA_HOME:-}" || "${JAVA_HOME}" == "${SCRIPT_DIR}/.jdk25-home" ]]; then
    unset JAVA_HOME
    for candidate in \
        "${HOME}/.jdks/jdk-25" \
        /usr/lib/jvm/java-25-openjdk-amd64 \
        /usr/lib/jvm/java-25-openjdk \
        /usr/lib/jvm/temurin-25-jdk-amd64; do
        if [[ -x "${candidate}/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
# shellcheck source=scripts/ensure-jdk25-home.sh
source "$SCRIPT_DIR/scripts/ensure-jdk25-home.sh"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED"

DO_CLEAN_ONLY=0
DO_FORMAT=0
DO_BUILD_HEALTH=0
DO_SMOKE=0
DO_E2E=0
DO_SMOKE_SHIPPED=0
DO_MACROBENCHMARK=0
DO_PUBLISH=0
DO_PUBLISH_DEBUG=0
DO_DEBUG=0
BLOCK_ON_OUTDATED=0

ROOT_APK="app-release.apk"
ROOT_MAPPING="app-release-mapping.txt"
ROOT_APK_FUTURE="app-release-future.apk"
ROOT_MAPPING_FUTURE="app-release-future-mapping.txt"

# --publish-debug serves these straight out of the Gradle output tree, so it
# hands out whatever the last assembleCompatDebug / assembleFutureDebug
# produced - including one built by Android Studio rather than by this script.
DEBUG_APK_COMPAT="app/build/outputs/apk/compat/debug/app-compat-debug.apk"
DEBUG_APK_FUTURE="app/build/outputs/apk/future/debug/app-future-debug.apk"

# A --debug run copies its APKs to the repo root under their own names, the way
# a release run does. They are deliberately NOT the release names: a dev build
# must never clobber the artifacts a release build published there.
ROOT_APK_DEBUG_COMPAT="app-debug.apk"
ROOT_APK_DEBUG_FUTURE="app-debug-future.apk"

for arg in "$@"; do
    case "$arg" in
        --clean)             DO_CLEAN_ONLY=1 ;;
        --format)            DO_FORMAT=1 ;;
        --build-health)      DO_BUILD_HEALTH=1 ;;
        --smoke)             DO_SMOKE=1 ;;
        --e2e)               DO_E2E=1 ;;
        --smoke-shipped)     DO_SMOKE_SHIPPED=1 ;;
        --macrobenchmark)    DO_MACROBENCHMARK=1 ;;
        --publish)           DO_PUBLISH=1 ;;
        --publish-debug)     DO_PUBLISH_DEBUG=1 ;;
        --debug)             DO_DEBUG=1 ;;
        --block-on-outdated) BLOCK_ON_OUTDATED=1 ;;
        *)
            echo "Unknown arg: $arg (accepted: --clean, --format, --build-health," \
                "--smoke, --e2e, --smoke-shipped, --macrobenchmark," \
                "--publish, --publish-debug, --debug, --block-on-outdated)" >&2
            exit 2
            ;;
    esac
done

# Keep dependencies on the newest published release. Default: auto-bump every
# referenced key in gradle/libs.versions.toml (Google Maven / Maven Central /
# Gradle Plugin Portal; alphas/betas/RCs count), print what changed, continue.
# --block-on-outdated restores the old refuse-to-build gate. Held pins still
# use a "# hold: <reason>" comment on the catalog line.
check_dependency_freshness() {
    if [[ "$BLOCK_ON_OUTDATED" -eq 1 ]]; then
        if ! python3 ./scripts/check_latest_deps.py; then
            echo "ERROR: dependencies are not on their latest versions (see above)." >&2
            echo "Re-run without --block-on-outdated to auto-update, or add a '# hold:'." >&2
            exit 1
        fi
        return 0
    fi
    if ! python3 ./scripts/check_latest_deps.py --apply; then
        echo "ERROR: dependency freshness check failed (see above)." >&2
        exit 1
    fi
}

# Mandatory on every mode except --publish / --publish-debug (serve-only; must
# not rewrite the catalog while handing out already-built APKs).
if [[ "$DO_PUBLISH" -eq 0 && "$DO_PUBLISH_DEBUG" -eq 0 ]]; then
    check_dependency_freshness
fi

acquire_lock() {
    local lock_dir="${XDG_CACHE_HOME:-$HOME/.cache}/android-apps"
    mkdir -p "$lock_dir"
    LOCKFILE="$lock_dir/build.lock"
    exec 9>"$LOCKFILE"
    if ! flock -n 9; then
        echo "Another Android app build/clean is already running" \
            "(dustvalve_next/calc/compass/STT_premium/Token Maxer share $LOCKFILE). Exiting."
        exit 1
    fi
}

GMD_GPU=(-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect)
SMOKE_ANNOTATION="com.compass.app.testing.SmokeTest"

# Production release path requires the real keystore. --debug omits this so
# local assemble without release-keystore.jks / .password-signing-keys still
# works (AGP debug signing fallback in app/build.gradle.kts). Standalone
# --smoke / --e2e use debug androidTest APKs and also omit it. --smoke-shipped
# omits it so a missing keystore can still drive a debug-signed release
# variant (same as STT); default ./build.sh still fails assembleRelease.
REQUIRE_RELEASE_SIGNING_ARGS=()
if [[ "$DO_DEBUG" -eq 0 && "$DO_SMOKE" -eq 0 && "$DO_E2E" -eq 0 && "$DO_SMOKE_SHIPPED" -eq 0 ]]; then
    REQUIRE_RELEASE_SIGNING_ARGS=(-Pcompass.requireReleaseSigning=true)
fi

regenerate_baseline_profiles() {
    if [[ ! -e /dev/kvm ]]; then
        echo "ERROR: /dev/kvm missing; GMD baseline generation needs KVM." >&2
        echo "Use ./build.sh --debug to skip baselines for a non-release build." >&2
        exit 1
    fi
    # Retry: GMD LMK can kill the app mid-flush ("never flushed profiles").
    local attempt=1
    local max_attempts=3
    ./gradlew "${REQUIRE_RELEASE_SIGNING_ARGS[@]}" \
        :baselineprofile:pixel7aApi37Setup "${GMD_GPU[@]}"
    while true; do
        local attempt_log
        attempt_log="$(mktemp)"
        if ./gradlew "${REQUIRE_RELEASE_SIGNING_ARGS[@]}" \
            :baselineprofile:pixel7aApi37FutureReleaseAndroidTest "${GMD_GPU[@]}" \
            -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=baselineprofile \
            >"$attempt_log" 2>&1 \
            && ./scripts/assert_tests_ran.sh 1 baselineprofile; then
            cat "$attempt_log"
            rm -f "$attempt_log"
            break
        fi
        if [[ "$attempt" -ge "$max_attempts" ]]; then
            cat "$attempt_log" >&2 || true
            rm -f "$attempt_log"
            echo "ERROR: baseline profile generation failed after ${max_attempts} attempts." >&2
            return 1
        fi
        rm -f "$attempt_log"
        echo "Baseline profile attempt ${attempt}/${max_attempts} failed; retrying..." >&2
        attempt=$((attempt + 1))
        sleep 5
    done
    chmod +x ./scripts/install_baseline_profiles.sh
    ./scripts/install_baseline_profiles.sh
}

if [[ "$DO_PUBLISH" -eq 1 ]]; then
    ./scripts/apk_http_serve.sh start "$ROOT_APK" "$ROOT_APK_FUTURE"
    exit 0
fi

# Serve the last debug build. Skips a flavor whose APK is absent instead of
# serving nothing: apk_http_serve.sh bails on the whole set if any listed file
# is missing, and a debug build of only one flavor is a normal state.
if [[ "$DO_PUBLISH_DEBUG" -eq 1 ]]; then
    DEBUG_APKS=()
    for debug_apk in "$DEBUG_APK_COMPAT" "$DEBUG_APK_FUTURE"; do
        if [[ -f "$debug_apk" ]]; then
            DEBUG_APKS+=("$debug_apk")
        fi
    done
    if [[ "${#DEBUG_APKS[@]}" -eq 0 ]]; then
        echo "ERROR: no debug APK to publish. Run ./build.sh --debug first." >&2
        echo "Looked for $DEBUG_APK_COMPAT and $DEBUG_APK_FUTURE." >&2
        exit 1
    fi
    ./scripts/apk_http_serve.sh start "${DEBUG_APKS[@]}"
    exit 0
fi

if [[ "$DO_CLEAN_ONLY" -eq 1 ]]; then
    acquire_lock
    ./gradlew clean
    rm -f "$ROOT_APK" "$ROOT_MAPPING" "$ROOT_APK_FUTURE" "$ROOT_MAPPING_FUTURE"
    rm -f "$ROOT_APK_DEBUG_COMPAT" "$ROOT_APK_DEBUG_FUTURE"
    echo "Clean complete."
    exit 0
fi

if [[ "$DO_FORMAT" -eq 1 ]]; then
    acquire_lock
    ./gradlew ktlintFormat
    echo "ktlintFormat complete. Re-run ./build.sh without --format to verify."
    exit 0
fi

if [[ "$DO_SMOKE" -eq 1 ]]; then
    acquire_lock
    ./gradlew :app:pixel7aApi37Setup "${GMD_GPU[@]}"
    ./gradlew :app:pixel7aApi37FutureDebugAndroidTest "${GMD_GPU[@]}" \
        -Pandroid.testInstrumentationRunnerArguments.annotation="$SMOKE_ANNOTATION"
    ./scripts/assert_tests_ran.sh 1 app
    echo "Smoke complete."
    exit 0
fi

if [[ "$DO_E2E" -eq 1 ]]; then
    acquire_lock
    ./gradlew :app:pixel7aApi37Setup "${GMD_GPU[@]}"
    ./gradlew :app:pixel7aApi37FutureDebugAndroidTest "${GMD_GPU[@]}"
    ./scripts/assert_tests_ran.sh 1 app
    echo "E2E hermetic complete."
    exit 0
fi

if [[ "$DO_SMOKE_SHIPPED" -eq 1 ]]; then
    acquire_lock
    ./gradlew :shippedsmoke:pixel7aApi37Setup "${GMD_GPU[@]}"
    ./gradlew :shippedsmoke:pixel7aApi37FutureReleaseAndroidTest "${GMD_GPU[@]}"
    ./scripts/assert_tests_ran.sh 1 shippedsmoke
    echo "Shipped smoke complete."
    exit 0
fi

if [[ "$DO_MACROBENCHMARK" -eq 1 ]]; then
    acquire_lock
    ./gradlew :macrobenchmark:pixel7aApi37Setup "${GMD_GPU[@]}"
    ./gradlew :macrobenchmark:pixel7aApi37FutureReleaseAndroidTest "${GMD_GPU[@]}" \
        -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
    ./scripts/assert_tests_ran.sh 1 macrobenchmark
    echo "Macrobenchmark complete (emulator numbers are advisory)."
    exit 0
fi

acquire_lock

./scripts/check_ascii.sh
chmod +x ./scripts/check_release_signing_gate.sh
./scripts/check_release_signing_gate.sh
chmod +x ./scripts/check_elf_16k_alignment.sh

GRADLE_APK_COMPAT="app/build/outputs/apk/compat/release/app-compat-release.apk"
GRADLE_MAPPING_COMPAT="app/build/outputs/mapping/compatRelease/mapping.txt"
GRADLE_APK_FUTURE="app/build/outputs/apk/future/release/app-future-release.apk"
GRADLE_MAPPING_FUTURE="app/build/outputs/mapping/futureRelease/mapping.txt"
BUILD_GRADLE="app/build.gradle.kts"

VERSION_BUMPED=0
CURRENT_CODE=""
CURRENT_NAME=""
NEW_CODE=""
NEW_NAME=""

revert_version_bump() {
    if [[ "$VERSION_BUMPED" -ne 1 ]]; then
        return 0
    fi
    sed -i "s/val baseVersionCode = $NEW_CODE/val baseVersionCode = $CURRENT_CODE/" "$BUILD_GRADLE"
    sed -i "s/val baseVersionName = \"$NEW_NAME\"/val baseVersionName = \"$CURRENT_NAME\"/" "$BUILD_GRADLE"
    echo "Build failed: reverted version to $CURRENT_NAME ($CURRENT_CODE)." >&2
}

if [[ "$DO_DEBUG" -eq 1 ]]; then
    echo "Debug build: skipping baseline profile regeneration and version bump."
else
    echo "Release build: regenerating baseline + startup profiles..."
    if ! regenerate_baseline_profiles; then
        exit 1
    fi
    echo "Baseline profiles installed under app/src/release/."

    CURRENT_CODE=$(sed -n 's/.*val baseVersionCode = \([0-9][0-9]*\).*/\1/p' "$BUILD_GRADLE" | head -1)
    CURRENT_NAME=$(sed -n 's/.*val baseVersionName = "\([^"]*\)".*/\1/p' "$BUILD_GRADLE" | head -1)

    if [[ -z "$CURRENT_CODE" || -z "$CURRENT_NAME" ]]; then
        echo "ERROR: could not parse baseVersionCode/baseVersionName from $BUILD_GRADLE" >&2
        exit 1
    fi

    NEW_CODE=$((CURRENT_CODE + 1))
    NEW_NAME=$(echo "$CURRENT_NAME" | awk -F. -v OFS=. '{$NF=$NF+1; print}')

    sed -i "s/val baseVersionCode = $CURRENT_CODE/val baseVersionCode = $NEW_CODE/" "$BUILD_GRADLE"
    sed -i "s/val baseVersionName = \"$CURRENT_NAME\"/val baseVersionName = \"$NEW_NAME\"/" "$BUILD_GRADLE"
    VERSION_BUMPED=1

    echo "Bumped version: $CURRENT_NAME ($CURRENT_CODE) -> $NEW_NAME ($NEW_CODE)"
fi

GRADLE_TASKS=(
    ktlintCheck
    detekt
    lintCompatRelease
    lintFutureRelease
    testCompatDebugUnitTest
    testFutureDebugUnitTest
    :macrobenchmark:assembleFutureRelease
    :baselineprofile:assembleFutureRelease
    :shippedsmoke:assembleFutureRelease
    assembleCompatDebug
    assembleFutureDebug
    assembleCompatRelease
    assembleFutureRelease
)

# Run clean in its own Gradle invocation. Leaving it in GRADLE_TASKS lets
# org.gradle.parallel schedule :ktlintCheck / :*:detekt alongside :*:clean,
# and ktlint's file walk then races with directories being deleted (NoSuchFileException
# on intermediates under app/build/).
if ! ./gradlew clean; then
    revert_version_bump
    exit 1
fi

if ! ./gradlew "${REQUIRE_RELEASE_SIGNING_ARGS[@]}" "${GRADLE_TASKS[@]}"; then
    revert_version_bump
    exit 1
fi

if ! ./scripts/check_elf_16k_alignment.sh "$GRADLE_APK_COMPAT" "$GRADLE_APK_FUTURE"; then
    revert_version_bump
    exit 1
fi

if [[ "$DO_BUILD_HEALTH" -eq 1 ]]; then
    ./gradlew buildHealth || true
    REPORT="build/reports/dependency-analysis/build-health-report.txt"
    [[ -f "$REPORT" ]] && echo "Dependency-analysis report: $REPORT"
fi

# Archive R8 mappings keyed by flavor+versionCode BEFORE the root copies are
# overwritten: devices in the field run historical versionCodes, and without
# the archive their stack traces become permanently un-deobfuscatable after
# the next build. mappings/ is gitignored but must never be deleted.
MAPPINGS_DIR="mappings"
if [[ ! -d "$MAPPINGS_DIR" ]]; then
    # One-time salvage of pre-archive root mappings (versionCode unknown).
    mkdir -p "$MAPPINGS_DIR"
    for prev in "$ROOT_MAPPING" "$ROOT_MAPPING_FUTURE"; do
        if [[ -f "$prev" ]]; then
            cp "$prev" "$MAPPINGS_DIR/unversioned-$(date +%Y%m%d%H%M%S)-$prev"
        fi
    done
fi
BUILT_CODE=$(sed -n 's/.*val baseVersionCode = \([0-9][0-9]*\).*/\1/p' "$BUILD_GRADLE" | head -1)
if [[ -n "$BUILT_CODE" ]]; then
    if [[ -f "$GRADLE_MAPPING_COMPAT" ]]; then
        cp "$GRADLE_MAPPING_COMPAT" "$MAPPINGS_DIR/compat-${BUILT_CODE}-mapping.txt"
    fi
    if [[ -f "$GRADLE_MAPPING_FUTURE" ]]; then
        cp "$GRADLE_MAPPING_FUTURE" "$MAPPINGS_DIR/future-$((1000000 + BUILT_CODE))-mapping.txt"
    fi
    echo "Archived R8 mappings for versionCode $BUILT_CODE under $MAPPINGS_DIR/."
fi

# A dev build exposes its own APKs and stops here: the release artifacts at the
# root belong to the last release build, and its version bump, so overwriting
# them with an unbumped dev build is how you end up serving the wrong APK.
if [[ "$DO_DEBUG" -eq 1 ]]; then
    rm -f "$ROOT_APK_DEBUG_COMPAT" "$ROOT_APK_DEBUG_FUTURE"
    cp "$DEBUG_APK_COMPAT" "$ROOT_APK_DEBUG_COMPAT"
    echo "Copied compat debug APK to $ROOT_APK_DEBUG_COMPAT"
    cp "$DEBUG_APK_FUTURE" "$ROOT_APK_DEBUG_FUTURE"
    echo "Copied future debug APK to $ROOT_APK_DEBUG_FUTURE"
    ./scripts/apk_http_serve.sh start "$ROOT_APK_DEBUG_COMPAT" "$ROOT_APK_DEBUG_FUTURE"
    exit 0
fi

rm -f "$ROOT_APK" "$ROOT_MAPPING" "$ROOT_APK_FUTURE" "$ROOT_MAPPING_FUTURE"
rm -f "$ROOT_APK_DEBUG_COMPAT" "$ROOT_APK_DEBUG_FUTURE"
cp "$GRADLE_APK_COMPAT" "$ROOT_APK"
echo "Copied compat release APK to $ROOT_APK"
if [[ -f "$GRADLE_MAPPING_COMPAT" ]]; then
    cp "$GRADLE_MAPPING_COMPAT" "$ROOT_MAPPING"
    echo "Copied compat release mapping to $ROOT_MAPPING"
fi
cp "$GRADLE_APK_FUTURE" "$ROOT_APK_FUTURE"
echo "Copied future release APK to $ROOT_APK_FUTURE"
if [[ -f "$GRADLE_MAPPING_FUTURE" ]]; then
    cp "$GRADLE_MAPPING_FUTURE" "$ROOT_MAPPING_FUTURE"
    echo "Copied future release mapping to $ROOT_MAPPING_FUTURE"
fi
cp "$DEBUG_APK_COMPAT" "$ROOT_APK_DEBUG_COMPAT"
echo "Copied compat debug APK to $ROOT_APK_DEBUG_COMPAT"
cp "$DEBUG_APK_FUTURE" "$ROOT_APK_DEBUG_FUTURE"
echo "Copied future debug APK to $ROOT_APK_DEBUG_FUTURE"

# Serve release + debug for both flavors (compat + future).
./scripts/apk_http_serve.sh start \
    "$ROOT_APK" "$ROOT_APK_FUTURE" \
    "$ROOT_APK_DEBUG_COMPAT" "$ROOT_APK_DEBUG_FUTURE"
