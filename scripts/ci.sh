#!/usr/bin/env bash
#
# efreihub push gates: debug lints/tests + debug APKs, release lint/assemble
# WITH -Pcompass.requireReleaseSigning=true (no debug-signed release
# fallback), ELF 16k check, and (when /dev/kvm exists) shippedsmoke +
# smoke + hermetic e2e. Dedicated CI runner: no catalog rewrite, no
# committed version bump, no APK copy/serve. Tag + fat APK publish is a
# later workflow step (`scripts/publish_ci_release.sh`).
#
# Production release assemble must pass
# -Pcompass.requireReleaseSigning=true so a missing keystore cannot
# silently fall back to debug signing. efreihub injects the two
# release-signing file secrets (COMPASS_RELEASE_KEYSTORE ->
# release-keystore.jks, COMPASS_RELEASE_PASSWORD ->
# .password-signing-keys) natively at the repo root on default-branch
# job runs (see docs/release-keys.md), so this script always arms
# requireReleaseSigning=true for the release gate below: there is no
# debug-signed "release" fallback from CI. A push without those
# secrets (e.g. a non-default branch) is expected to fail this gate,
# not silently pass with an unshippable APK.
#
set -euo pipefail

# Firecracker rootfs is read-only. Keep Gradle/JDK state on the work disk.
export HOME=/work/.efreihub-home
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/work/.gradle}"
mkdir -p "$HOME" "$GRADLE_USER_HOME"
# AGP aapt2 (Gradle cache, glibc) segfaults on musl libgcc_s. GNU libgcc_s
# lives in the guest glibc prefix.
if [ -d /usr/glibc-compat/lib ]; then
  export LD_LIBRARY_PATH="/usr/glibc-compat/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  echo "CI: exported LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# Shared free-tag / handoff helpers (used when EFREIHUB_TOKEN is set).
# shellcheck source=scripts/release_version.sh
source "$ROOT_DIR/scripts/release_version.sh"

# Prefer a real JDK 27 (Firecracker guest Temurin 27 EA), then JDK 26.
# Wrap it so Gradle Worker Daemons also get the JEP 498 / JEP 472 opt-in flags.
# ensure-jdk26-home.sh is a JEP-flag wrapper; keep sourcing it when JAVA_HOME is 27.
if [[ -z "${JAVA_HOME:-}" || "${JAVA_HOME}" == "${ROOT_DIR}/.jdk26-home" ]]; then
    unset JAVA_HOME
    for candidate in \
        /usr/lib/jvm/java-27-openjdk \
        /usr/lib/jvm/java-27-openjdk-amd64 \
        /usr/lib/jvm/temurin-27-jdk-amd64 \
        "${HOME}/.jdks/jdk-27" \
        /usr/lib/jvm/java-26-openjdk \
        /usr/lib/jvm/default \
        /usr/lib/jvm/java-26-openjdk-amd64 \
        /usr/lib/jvm/temurin-26-jdk-amd64 \
        "${HOME}/.jdks/jdk-26"; do
        if [[ -x "${candidate}/bin/java" ]]; then
            ver="$("${candidate}/bin/java" -version 2>&1 | head -1 || true)"
            if [[ "$ver" == *'"27'* || "$ver" == *' 27.'* ]]; then
                export JAVA_HOME="$candidate"
                break
            fi
        fi
    done
    if [[ -z "${JAVA_HOME:-}" ]]; then
        for candidate in \
            /usr/lib/jvm/java-26-openjdk \
            /usr/lib/jvm/default \
            /usr/lib/jvm/java-26-openjdk-amd64 \
            /usr/lib/jvm/temurin-26-jdk-amd64 \
            "${HOME}/.jdks/jdk-26"; do
            if [[ -x "${candidate}/bin/java" ]]; then
                ver="$("${candidate}/bin/java" -version 2>&1 | head -1 || true)"
                if [[ "$ver" == *'"26'* || "$ver" == *' 26.'* ]]; then
                    export JAVA_HOME="$candidate"
                    break
                fi
            fi
        done
    fi
fi
# shellcheck source=scripts/ensure-jdk26-home.sh
source "$ROOT_DIR/scripts/ensure-jdk26-home.sh"

export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
mkdir -p "$ANDROID_USER_HOME"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -Duser.home=${HOME}"

SMOKE_ANNOTATION="com.compass.app.testing.SmokeTest"
SMOKE_ASSERT_COUNT=1
E2E_ASSERT_COUNT=1
SHIPPED_SMOKE_ASSERT_COUNT=1

DEBUG_ASSEMBLE_TASKS=(assembleCompatDebug assembleFutureDebug)
DEBUG_GATE_TASKS=(
    ktlintCheck
    detekt
    lintCompatDebug
    lintFutureDebug
    testCompatDebugUnitTest
    testFutureDebugUnitTest
)
GRADLE_TASKS=(
    lintCompatRelease
    lintFutureRelease
    :macrobenchmark:assembleFutureRelease
    :shippedsmoke:assembleFutureRelease
    assembleCompatRelease
    assembleFutureRelease
)
# Armed unconditionally on the release gate below: efreihub's native
# file secrets put a real keystore + password at the repo root on
# default-branch job runs, so the release build must prove it can
# really sign, not fall back to the AGP debug key. See
# app/build.gradle.kts signingConfigs.create("release") for the
# Gradle-side hard-fail when the files are missing.
RELEASE_SIGNING_PROPS=(
    -Pcompass.requireReleaseSigning=true
)

GRADLE_APK_COMPAT="app/build/outputs/apk/compat/release/app-compat-release.apk"
GRADLE_APK_FUTURE="app/build/outputs/apk/future/release/app-future-release.apk"

GMD_GPU=(-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect)

GRADLE_CMD=(./scripts/run_gradle.sh)
gradle() {
    "${GRADLE_CMD[@]}" "$@"
}

gmd_setup() {
    ./scripts/gmd_ensure_avd.sh || return 1
    gradle "$@" || return 1
    ./scripts/gmd_ensure_avd.sh || return 1
    local stamp="${XDG_CACHE_HOME:-$HOME/.cache}/android-apps/gmd-avd.changed"
    if [[ -f "$stamp" && "$(cat "$stamp")" == "1" ]]; then
        echo "GMD AVD RAM/CPU patched after first create; cold-booting patched guest."
        gradle "$@" || return 1
    fi
}

require_kvm() {
    if [[ ! -e /dev/kvm ]]; then
        echo "ERROR: /dev/kvm missing; GMD instrumented tests need KVM." >&2
        return 1
    fi
}

run_smoke_tests() {
    require_kvm || return 1
    if [[ "${GMD_SKIP_APP_API37_SETUP:-0}" -ne 1 ]]; then
        gmd_setup :app:pixel7aApi37Setup "${GMD_GPU[@]}" || return 1
    fi
    local app_timeout_sec="${APP_ANDROID_TEST_TIMEOUT_SEC:-600}"
    local rc=0
    timeout --foreground "${app_timeout_sec}s" \
        "${GRADLE_CMD[@]}" :app:pixel7aApi37FutureDebugAndroidTest "${GMD_GPU[@]}" \
            -Pandroid.testInstrumentationRunnerArguments.annotation="$SMOKE_ANNOTATION" \
        || {
            rc=$?
            if [[ "$rc" -eq 124 ]]; then
                echo "ERROR: :app smoke GMD androidTest exceeded ${app_timeout_sec}s" >&2
            fi
        }
    [[ "$rc" -eq 0 ]] || return "$rc"
    ./scripts/assert_tests_ran.sh "$SMOKE_ASSERT_COUNT" app || return 1
}

run_e2e_tests() {
    require_kvm || return 1
    if [[ "${GMD_SKIP_APP_API37_SETUP:-0}" -ne 1 ]]; then
        gmd_setup :app:pixel7aApi37Setup "${GMD_GPU[@]}" || return 1
    fi
    local app_timeout_sec="${APP_ANDROID_TEST_TIMEOUT_SEC:-900}"
    local rc=0
    timeout --foreground "${app_timeout_sec}s" \
        "${GRADLE_CMD[@]}" :app:pixel7aApi37FutureDebugAndroidTest "${GMD_GPU[@]}" \
        || {
            rc=$?
            if [[ "$rc" -eq 124 ]]; then
                echo "ERROR: :app e2e GMD androidTest exceeded ${app_timeout_sec}s" >&2
            fi
        }
    [[ "$rc" -eq 0 ]] || return "$rc"
    ./scripts/assert_tests_ran.sh "$E2E_ASSERT_COUNT" app || return 1
}

run_shipped_smoke_tests() {
    require_kvm || return 1
    gmd_setup :shippedsmoke:pixel7aApi37Setup "${GMD_GPU[@]}" || return 1
    local smoke_timeout_sec="${SHIPPED_SMOKE_TIMEOUT_SEC:-600}"
    timeout --foreground "${smoke_timeout_sec}s" \
        "${GRADLE_CMD[@]}" :shippedsmoke:pixel7aApi37FutureReleaseAndroidTest "${GMD_GPU[@]}" \
        || {
            local rc=$?
            if [[ "$rc" -eq 124 ]]; then
                echo "ERROR: :shippedsmoke GMD androidTest exceeded ${smoke_timeout_sec}s" >&2
            fi
            return "$rc"
        }
    ./scripts/assert_tests_ran.sh "$SHIPPED_SMOKE_ASSERT_COUNT" shippedsmoke || return 1
}

chmod +x ./scripts/check_ascii.sh
chmod +x ./scripts/check_release_signing_gate.sh
chmod +x ./scripts/check_elf_16k_alignment.sh
chmod +x ./scripts/gmd_ensure_avd.sh
chmod +x ./scripts/assert_tests_ran.sh
chmod +x ./scripts/run_gradle.sh

./scripts/check_ascii.sh
./scripts/check_release_signing_gate.sh

echo "Running debug lints and tests..."
gradle "${DEBUG_GATE_TASKS[@]}"

# lint/detekt/ktlint do not write JUnit XML; only the unit-test tasks
# above do. A Gradle filter/config typo can make those match zero
# classes and still exit 0, so count actual <testcase> elements
# before trusting the green gradle() call above.
echo "Verifying unit tests actually ran..."
./scripts/assert_tests_ran.sh 1 app unit

echo "Assembling debug APKs..."
gradle "${DEBUG_ASSEMBLE_TASKS[@]}"


# Default-branch runs inject EFREIHUB_TOKEN. Stamp the free tag into the
# gated release assemble so publish can upload these APKs without rebuilding.
RELEASE_TAG=""
RELEASE_VERSION_NAME=""
RELEASE_VERSION_CODE=""
HANDOFF_DIR=""
if [[ -n "${EFREIHUB_TOKEN:-}" ]]; then
    if ! HANDOFF_DIR="$(ci_release_handoff_dir)"; then
        echo "ERROR: EFREIHUB_TOKEN is set but /work is not writable; cannot stage signed release APKs for publish (expected /work/compass-ci-release)." >&2
        exit 1
    fi
    BASE_VERSION_NAME="$(sed -n 's/^[[:space:]]*val baseVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
    if [[ -z "$BASE_VERSION_NAME" ]]; then
        echo "ERROR: could not read baseVersionName from app/build.gradle.kts" >&2
        exit 1
    fi
    PROBE_WORK="$HANDOFF_DIR/.probe"
    mkdir -p "$PROBE_WORK"
    probe_free_release_tag "$PROBE_WORK" "$BASE_VERSION_NAME"
    rm -rf "$PROBE_WORK"
    RELEASE_VERSION_CODE="$(date +%s)"
    RELEASE_SIGNING_PROPS+=(
        -Pcompass.versionName="$RELEASE_VERSION_NAME"
        -Pcompass.versionCode="$RELEASE_VERSION_CODE"
    )
    echo "CI: stamping release assemble as ${RELEASE_TAG} (versionCode ${RELEASE_VERSION_CODE})"
fi

echo "Running release lint and assemble (requireReleaseSigning=true)..."
gradle "${RELEASE_SIGNING_PROPS[@]}" "${GRADLE_TASKS[@]}"

./scripts/check_elf_16k_alignment.sh "$GRADLE_APK_COMPAT" "$GRADLE_APK_FUTURE"

# Stage gated signed fat APKs for publish_ci_release.sh (same Firecracker guest).
if [[ -n "${EFREIHUB_TOKEN:-}" ]]; then
    find_fat_apk() {
        local flavor="$1"
        local dir="$ROOT_DIR/app/build/outputs/apk/${flavor}/release"
        local candidate
        for candidate in \
            "$dir/app-${flavor}-release.apk" \
            "$dir/app-${flavor}-universal-release.apk" \
            "$dir/app-${flavor}-release-universal.apk"
        do
            if [[ -f "$candidate" ]]; then
                printf '%s\n' "$candidate"
                return 0
            fi
        done
        return 1
    }
    COMPAT_APK="$(find_fat_apk compat || true)"
    FUTURE_APK="$(find_fat_apk future || true)"
    if [[ -z "$COMPAT_APK" || -z "$FUTURE_APK" ]]; then
        echo "ERROR: missing fat APKs after release assemble (compat='$COMPAT_APK' future='$FUTURE_APK')" >&2
        find "$ROOT_DIR/app/build/outputs/apk" -name '*.apk' -print >&2 || true
        exit 1
    fi
    rm -rf "$HANDOFF_DIR"
    mkdir -p "$HANDOFF_DIR"
    cp -f "$COMPAT_APK" "$HANDOFF_DIR/app-release.apk"
    cp -f "$FUTURE_APK" "$HANDOFF_DIR/app-release-future.apk"
    SHA256_COMPAT="$(sha256sum "$HANDOFF_DIR/app-release.apk" | awk '{print $1}')"
    SHA256_FUTURE="$(sha256sum "$HANDOFF_DIR/app-release-future.apk" | awk '{print $1}')"
    {
        printf 'TAG=%s\n' "$RELEASE_TAG"
        printf 'VERSION_NAME=%s\n' "$RELEASE_VERSION_NAME"
        printf 'VERSION_CODE=%s\n' "$RELEASE_VERSION_CODE"
        printf 'SHA256_COMPAT=%s\n' "$SHA256_COMPAT"
        printf 'SHA256_FUTURE=%s\n' "$SHA256_FUTURE"
        printf 'COMPAT_APK_NAME=app-release.apk\n'
        printf 'FUTURE_APK_NAME=app-release-future.apk\n'
    } > "$HANDOFF_DIR/manifest.env"
    echo "CI: staged signed fat APKs for publish at $HANDOFF_DIR (${RELEASE_TAG}, compat=${SHA256_COMPAT}, future=${SHA256_FUTURE})"
fi


if [[ -e /dev/kvm ]]; then
    echo "CI: running shippedsmoke, then smoke + e2e on one API 37 GMD..."
    run_shipped_smoke_tests
    gmd_setup :app:pixel7aApi37Setup "${GMD_GPU[@]}"
    GMD_SKIP_APP_API37_SETUP=1
    run_smoke_tests
    run_e2e_tests
    echo "Device gates complete."
else
    echo "CI: /dev/kvm missing; skipping GMD shippedsmoke/smoke/e2e."
fi

echo "CI complete."
