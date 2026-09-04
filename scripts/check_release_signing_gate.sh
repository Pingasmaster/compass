#!/usr/bin/env bash
#
# Structural + behavioral gate for the release-signing story. This is a
# CI honesty check: it must fail if the actual scripts stop enforcing
# real signing, not just if a comment gets edited.
#
# A. Gradle side (static, app/build.gradle.kts):
#   - buildTypes.debug must NEVER assign the release signingConfig - a debug
#     build must not be able to carry the production signature.
#   - buildTypes.release must assign the release signingConfig.
#   - signingConfigs.create("release") must still read
#     compass.requireReleaseSigning and hard-fail (throw GradleException)
#     when the keystore is missing, so a real release build can never
#     silently fall back to debug signing.
#
# B. scripts/ci.sh side (static, but on real code lines, not comments):
#   - the release lint/assemble gradle() call must actually pass
#     -Pcompass.requireReleaseSigning=true, or the Gradle-side gate
#     above is never armed and CI can pass on a debug-signed "release".
#   - scripts/assert_tests_ran.sh must actually be invoked after the
#     debug test gate (unit kind), so a Gradle test filter that matches
#     zero tests cannot pass CI silently.
#
# C. scripts/publish_ci_release.sh side (behavioral: the real script is
#    executed in an isolated temp copy, never against this repo's own
#    signing files):
#   - tag refs and non-default-branch refs skip (exit 0).
#   - a default-branch run with EFREIHUB_TOKEN unset fails (nonzero),
#     it does not skip.
#   - a default-branch run with the token set but the keystore/password
#     files missing fails (nonzero), it does not skip.
#
# D. scripts/build.sh still passes -Pcompass.requireReleaseSigning=true
#    on the local release path.
#
# This is a static/structural + isolated-execution check, not a full
# Gradle TestKit run, and it never touches or prints this repo's real
# release-keystore.jks / .password-signing-keys.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_GRADLE="$ROOT_DIR/app/build.gradle.kts"
CI_SH="$ROOT_DIR/scripts/ci.sh"
PUBLISH_SH="$ROOT_DIR/scripts/publish_ci_release.sh"
BUILD_SH="$ROOT_DIR/build.sh"

fail() {
    echo "ERROR: $1" >&2
    exit 1
}

[[ -f "$BUILD_GRADLE" ]] || fail "$BUILD_GRADLE not found"
[[ -f "$CI_SH" ]] || fail "$CI_SH not found"
[[ -f "$PUBLISH_SH" ]] || fail "$PUBLISH_SH not found"
[[ -f "$BUILD_SH" ]] || fail "$BUILD_SH not found"

# Prints the balanced-brace block starting at the first line in file $1
# matching regex $2, up to (and including) the line where the brace depth
# returns to zero.
extract_block() {
    awk -v pat="$2" '
        BEGIN { depth = 0; capturing = 0 }
        capturing == 0 && $0 ~ pat { capturing = 1 }
        capturing == 1 {
            print
            line = $0
            opens = gsub(/\{/, "{", line)
            closes = gsub(/\}/, "}", line)
            depth += opens - closes
            if (depth == 0) exit
        }
    ' "$1"
}

# Drops whole-line shell comments and blank lines, so a grep against the
# result only matches real, executed code - never a stale comment that
# talks about what the script "must" do.
strip_comments() {
    grep -vE '^[[:space:]]*#' "$1" | grep -vE '^[[:space:]]*$'
}

# Line number of the first non-comment line matching pattern $2 in file
# $1. Numbering is over the comment-stripped stream.
first_code_line() {
    strip_comments "$1" | grep -nE "$2" | head -n1 | cut -d: -f1
}

echo "== A. app/build.gradle.kts signing story =="

DEBUG_BLOCK="$(extract_block "$BUILD_GRADLE" '^[[:space:]]*debug \\{')"
[[ -n "$DEBUG_BLOCK" ]] || fail "could not locate buildTypes.debug {} in $BUILD_GRADLE"

if echo "$DEBUG_BLOCK" | grep -q 'signingConfigs\.getByName("release")'; then
    fail "buildTypes.debug in $BUILD_GRADLE assigns the release signingConfig - debug builds must use AGP debug signing only."
fi

RELEASE_BLOCK="$(extract_block "$BUILD_GRADLE" '^[[:space:]]*release \\{')"
[[ -n "$RELEASE_BLOCK" ]] || fail "could not locate buildTypes.release {} in $BUILD_GRADLE"

echo "$RELEASE_BLOCK" | grep -q 'signingConfigs\.getByName("release")' \
    || fail "buildTypes.release in $BUILD_GRADLE no longer assigns the release signingConfig"

SIGNING_BLOCK="$(extract_block "$BUILD_GRADLE" '^[[:space:]]*create\\("release"\\) \\{')"
[[ -n "$SIGNING_BLOCK" ]] || fail "could not locate signingConfigs.create(\"release\") {} in $BUILD_GRADLE"

echo "$SIGNING_BLOCK" | grep -q 'gradleProperty("compass.requireReleaseSigning")' \
    || fail "signingConfigs release block no longer reads compass.requireReleaseSigning"

echo "$SIGNING_BLOCK" | grep -q 'throw GradleException' \
    || fail "signingConfigs release block no longer hard-fails when the keystore is missing"

echo "== B. scripts/ci.sh actually arms the gate (not just comments) =="

CI_SH_CODE="$(strip_comments "$CI_SH")"

echo "$CI_SH_CODE" | grep -Eq -- 'RELEASE_SIGNING_PROPS=\(' \
    || fail "$CI_SH no longer defines a RELEASE_SIGNING_PROPS array for the release gate"

echo "$CI_SH_CODE" | grep -q -- '-Pcompass.requireReleaseSigning=true' \
    || fail "$CI_SH no longer passes -Pcompass.requireReleaseSigning=true on a real (non-comment) line"

echo "$CI_SH_CODE" | grep -Eq -- 'gradle[[:space:]]+"\$\{RELEASE_SIGNING_PROPS\[@\]\}"[[:space:]]+"\$\{GRADLE_TASKS\[@\]\}"' \
    || fail "$CI_SH does not call gradle with RELEASE_SIGNING_PROPS applied to GRADLE_TASKS - requireReleaseSigning is defined but not wired up"

echo "$CI_SH_CODE" | grep -E -- 'gradle[[:space:]]+"\$\{DEBUG_(GATE|ASSEMBLE)_TASKS\[@\]\}"' | grep -q RELEASE_SIGNING_PROPS \
    && fail "$CI_SH applies RELEASE_SIGNING_PROPS to a debug gradle() call - requireReleaseSigning must be scoped to the release gate only"

echo "== B2. scripts/ci.sh actually invokes assert_tests_ran.sh after the test gate =="

echo "$CI_SH_CODE" | grep -q -- './scripts/assert_tests_ran.sh' \
    || fail "$CI_SH no longer invokes scripts/assert_tests_ran.sh - a Gradle test filter matching zero tests could pass CI silently"

TEST_GATE_LINE="$(first_code_line "$CI_SH" 'gradle[[:space:]]+"\$\{DEBUG_GATE_TASKS\[@\]\}"')"
ASSERT_LINE="$(first_code_line "$CI_SH" '^[[:space:]]*\./scripts/assert_tests_ran\.sh 1 app unit')"
[[ -n "$TEST_GATE_LINE" ]] || fail "$CI_SH no longer calls gradle with DEBUG_GATE_TASKS (the test/lint gate)"
[[ -n "$ASSERT_LINE" ]] || fail "could not find the unit assert_tests_ran.sh invocation line in $CI_SH"
(( ASSERT_LINE > TEST_GATE_LINE )) \
    || fail "$CI_SH invokes unit assert_tests_ran.sh (line $ASSERT_LINE) before the Gradle test gate (line $TEST_GATE_LINE) - it must run AFTER the test task so there is a report to check"

echo "$CI_SH_CODE" | grep -q -- './scripts/assert_tests_ran.sh' \
    || fail "$CI_SH no longer invokes scripts/assert_tests_ran.sh for GMD lanes"

echo "check_release_signing_gate: A/B OK (debug never release-signed, release keystore hard-fail wired, ci.sh arms requireReleaseSigning on the release gate only, unit assert_tests_ran runs after tests)."

echo "== C. scripts/publish_ci_release.sh fails closed (isolated dry run, no network, no real secrets) =="

WORK="$(mktemp -d "${TMPDIR:-/tmp}/compass-publish-gate.XXXXXX")"
cleanup_work() { rm -rf "$WORK"; }
trap cleanup_work EXIT

mkdir -p "$WORK/scripts"
cp "$PUBLISH_SH" "$WORK/scripts/publish_ci_release.sh"
chmod +x "$WORK/scripts/publish_ci_release.sh"

run_publish_gate() {
    (cd "$WORK" && env -i PATH="/usr/bin:/bin" HOME="$WORK" "$@" ./scripts/publish_ci_release.sh) \
        > "$WORK/out.log" 2>&1
}

NL=$'\n'
set +e

run_publish_gate EFREIHUB_REF="refs/tags/v1.2.3"
rc=$?
[[ "$rc" -eq 0 ]] || fail "publish_ci_release.sh must skip (exit 0) on a tag ref; got exit $rc:${NL}$(cat "$WORK/out.log")"

run_publish_gate EFREIHUB_REF="refs/heads/feature/some-branch"
rc=$?
[[ "$rc" -eq 0 ]] || fail "publish_ci_release.sh must skip (exit 0) on a non-default-branch ref; got exit $rc:${NL}$(cat "$WORK/out.log")"

run_publish_gate EFREIHUB_REF="refs/heads/master"
rc=$?
[[ "$rc" -ne 0 ]] || fail "publish_ci_release.sh must fail (nonzero) on a default-branch run with EFREIHUB_TOKEN unset, not skip; got exit 0:${NL}$(cat "$WORK/out.log")"
grep -q 'EFREIHUB_TOKEN' "$WORK/out.log" || fail "missing-token failure did not name EFREIHUB_TOKEN in its error"

run_publish_gate EFREIHUB_REF="refs/heads/master" EFREIHUB_TOKEN="dry-run-fake-token-not-a-secret"
rc=$?
[[ "$rc" -ne 0 ]] || fail "publish_ci_release.sh must fail (nonzero) on a default-branch run with the keystore/password files missing, not skip; got exit 0:${NL}$(cat "$WORK/out.log")"
grep -q 'release-keystore.jks' "$WORK/out.log" || fail "missing-signing-files failure did not name release-keystore.jks in its error"

set -e

echo "check_release_signing_gate: C OK (publish_ci_release.sh skips only on tag/non-default-branch refs, fails closed on missing token/signing files on the default branch)."

echo "== D. local ./build.sh still arms the release-signing property =="

grep -q -- '-Pcompass.requireReleaseSigning=true' "$BUILD_SH" \
    || fail "$BUILD_SH no longer passes -Pcompass.requireReleaseSigning=true on the release path"

echo "check_release_signing_gate: D OK (local build.sh still requires release signing)."

echo "== E. publish uploads ci.sh handoff; no second release assemble =="

strip_comments_pub() {
    # Drop full-line comments and blank lines for invocation greps.
    sed -e '/^[[:space:]]*#/d' -e '/^[[:space:]]*$/d' "$1"
}

PUBLISH_CODE="$(strip_comments_pub "$PUBLISH_SH")"
if echo "$PUBLISH_CODE" | grep -Eq -- '(run_gradle\.sh|gradlew|[[:space:]]gradle)[[:space:]].*assemble(Compat|Future)?Release|assemble(Compat|Future)?Release[[:space:]]+-P'; then
    fail "$PUBLISH_SH still invokes assembleRelease - publish must upload the ci.sh handoff APKs only"
fi
echo "$PUBLISH_CODE" | grep -q -- 'compass-ci-release' \
    || fail "$PUBLISH_SH no longer looks for the /work/compass-ci-release handoff from ci.sh"
echo "$PUBLISH_CODE" | grep -q -- 'manifest.env' \
    || fail "$PUBLISH_SH no longer reads manifest.env from the ci.sh handoff"

CI_CODE_D="$(strip_comments_pub "$CI_SH")"
echo "$CI_CODE_D" | grep -q -- 'compass-ci-release' \
    || fail "$CI_SH no longer stages the signed APKs under compass-ci-release for publish"
echo "$CI_CODE_D" | grep -q -- 'probe_free_release_tag' \
    || fail "$CI_SH no longer probes a free release tag before the gated release assemble"
echo "$CI_CODE_D" | grep -q -- '-Pcompass.versionName=' \
    || fail "$CI_SH no longer stamps -Pcompass.versionName into the gated release assemble"

echo "check_release_signing_gate: E OK (single release assemble in ci.sh; publish consumes /work handoff)."

echo "check_release_signing_gate: OK."
