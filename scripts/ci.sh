#!/usr/bin/env bash
#
# efreihub push gates: debug lints/tests + debug APKs, release lint/assemble
# (without -Pcompass.requireReleaseSigning), ELF 16k check, and (when
# /dev/kvm exists) shippedsmoke + smoke + hermetic e2e. Dedicated CI
# runner: no catalog rewrite, no committed version bump, no APK copy/serve.
# Tag + fat APK publish is a later workflow step
# (`scripts/publish_ci_release.sh`).
#
# Production efreihub-release assemble must pass
# -Pcompass.requireReleaseSigning=true
# so a missing keystore cannot silently fall back to debug signing.
# CI does not pass that property: the keystore is gitignored on the forge.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# Prefer a real JDK 26, then wrap it so Gradle Worker Daemons (ktlint) also
# get the JEP 498 / JEP 472 opt-in flags.
if [[ -z "${JAVA_HOME:-}" || "${JAVA_HOME}" == "${ROOT_DIR}/.jdk26-home" ]]; then
    unset JAVA_HOME
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
# shellcheck source=scripts/ensure-jdk26-home.sh
source "$ROOT_DIR/scripts/ensure-jdk26-home.sh"

export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED"

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
echo "Assembling debug APKs..."
gradle "${DEBUG_ASSEMBLE_TASKS[@]}"

# Keystore is gitignored; do not arm requireReleaseSigning on the forge.
echo "Running release lint and assemble..."
gradle "${GRADLE_TASKS[@]}"

./scripts/check_elf_16k_alignment.sh "$GRADLE_APK_COMPAT" "$GRADLE_APK_FUTURE"

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
