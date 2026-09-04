#!/usr/bin/env bash
#
# After a green ./scripts/ci.sh, create a git tag + efreihub release and
# upload the already-compiled signed fat APKs that ci.sh staged under
# /work/compass-ci-release:
#   compat (armeabi-v7a + arm64-v8a + x86 + x86_64 + riscv64) as app-release.apk
#   future (arm64-v8a + x86_64 + riscv64) as app-release-future.apk
# There is NO second assembleCompatRelease/assembleFutureRelease here: the
# CI gate is the only release assemble; this script only verifies + uploads.
#
# Production assemble in ci.sh must pass -Pcompass.requireReleaseSigning=true so a
# missing keystore cannot silently fall back to debug signing.
#
# efreihub injects EFREIHUB_TOKEN and native signing file secrets only on
# default-branch runs. Missing token, signing files, or the ci.sh handoff
# there is a hard failure (does NOT skip, does NOT rebuild, does NOT fall
# back to debug). Tag and non-default-branch refs skip because they must
# not publish.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# Firecracker rootfs is read-only; guest HOME defaults to /root.
# Prefer /work (Firecracker work disk). Host-side signing-gate dry runs may
# lack it; fall back under /tmp so fail-closed checks still run without
# letting guest HOME=/root win in the guest.
if [ -d /work ] && [ -w /work ]; then
  export HOME=/work/.efreihub-home
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/work/.gradle}"
  export TMPDIR="${TMPDIR:-/work/tmp}"
else
  _efreihub_pub_fallback="${TMPDIR:-/tmp}/efreihub-publish-home"
  export HOME="${_efreihub_pub_fallback}/home"
  export GRADLE_USER_HOME="${_efreihub_pub_fallback}/gradle"
  export TMPDIR="${_efreihub_pub_fallback}/tmp"
fi
mkdir -p "$HOME" "$GRADLE_USER_HOME" "$TMPDIR"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
mkdir -p "$ANDROID_USER_HOME"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -Duser.home=${HOME}"

# Publish no longer runs Gradle assemble, but keep glibc for apksigner/tools.
if [ -d /usr/glibc-compat/lib ]; then
  export LD_LIBRARY_PATH="/usr/glibc-compat/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  echo "publish_ci_release: LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
fi

REF="${EFREIHUB_REF:-}"
DEFAULT_BRANCH_REF="${EFREIHUB_DEFAULT_BRANCH_REF:-refs/heads/master}"
case "$REF" in
    refs/tags/*)
        echo "publish_ci_release: skip (ref is a tag: $REF; avoids a tag-triggered publish loop)"
        exit 0
        ;;
esac

if [[ -n "$REF" && "$REF" != "$DEFAULT_BRANCH_REF" ]]; then
    echo "publish_ci_release: skip (ref $REF is not the default branch $DEFAULT_BRANCH_REF; efreihub only injects secrets on default-branch job runs)"
    exit 0
fi

if [[ -z "${EFREIHUB_TOKEN:-}" ]]; then
    echo "ERROR: EFREIHUB_TOKEN is unset on a default-branch run (ref: ${REF:-unset}). efreihub injects this natively for default-branch jobs; a missing token here is a misconfiguration, not an expected skip." >&2
    exit 1
fi

API_BASE="${EFREIHUB_API_URL:-https://efrei.app:50002/hub/api/v1}"
API_BASE="${API_BASE%/}"
REPO_SLUG="${EFREIHUB_REPOSITORY:-admin/compass}"
OWNER="${REPO_SLUG%%/*}"
REPO="${REPO_SLUG#*/}"
if [[ "$OWNER" == "$REPO_SLUG" || -z "$REPO" || "$REPO" == *"/"* ]]; then
    echo "ERROR: EFREIHUB_REPOSITORY must be owner/name, got ${REPO_SLUG}" >&2
    exit 1
fi

# Primary path: efreihub native file secrets (COMPASS_RELEASE_KEYSTORE, COMPASS_RELEASE_PASSWORD) materialize at
# repo root. SIGNING_DIR is a secondary/manual fallback for local testing.
SIGNING_DIR="${COMPASS_SIGNING_DIR:-/usr/local/compass-signing}"
if [[ ! -f "$ROOT_DIR/release-keystore.jks" && -f "$SIGNING_DIR/release-keystore.jks" ]]; then
    cp "$SIGNING_DIR/release-keystore.jks" "$ROOT_DIR/release-keystore.jks"
fi
if [[ ! -f "$ROOT_DIR/.password-signing-keys" && -f "$SIGNING_DIR/.password-signing-keys" ]]; then
    cp "$SIGNING_DIR/.password-signing-keys" "$ROOT_DIR/.password-signing-keys"
fi
if [[ ! -f "$ROOT_DIR/release-keystore.jks" || ! -f "$ROOT_DIR/.password-signing-keys" ]]; then
    echo "ERROR: release-keystore.jks / .password-signing-keys missing at repo root (and not bound at $SIGNING_DIR) on a default-branch run. efreihub injects these natively as the COMPASS_RELEASE_KEYSTORE / COMPASS_RELEASE_PASSWORD file secrets; a missing file here is a misconfiguration, not an expected skip." >&2
    exit 1
fi

# Shared free-tag helpers + handoff path. Sourced after early skip / token /
# signing fail-closed checks so check_release_signing_gate.sh dry runs
# (temp copy of this script only) never need the sibling file.
# shellcheck source=scripts/release_version.sh
source "$ROOT_DIR/scripts/release_version.sh"

# Consume the APKs ci.sh already assembled + stamped. Fail closed: never
# rebuild, never fall back to a debug APK, never invent a tag.
if ! HANDOFF_DIR="$(ci_release_handoff_dir)"; then
    echo "ERROR: /work is not writable; cannot find ci.sh release handoff (expected /work/compass-ci-release). publish must upload the APKs from the CI gate, not assemble again." >&2
    exit 1
fi
MANIFEST="$HANDOFF_DIR/manifest.env"
HANDOFF_COMPAT="$HANDOFF_DIR/app-release.apk"
HANDOFF_FUTURE="$HANDOFF_DIR/app-release-future.apk"
if [[ ! -f "$MANIFEST" || ! -f "$HANDOFF_COMPAT" || ! -f "$HANDOFF_FUTURE" ]]; then
    echo "ERROR: missing ci.sh release handoff at $HANDOFF_DIR (need app-release.apk, app-release-future.apk, manifest.env). The CI gate must leave signed stamped fat APKs for publish; refusing to assemble again or fall back to debug." >&2
    ls -la "$HANDOFF_DIR" >&2 2>/dev/null || true
    exit 1
fi

# shellcheck disable=SC1090
source "$MANIFEST"
if [[ -z "${TAG:-}" || -z "${VERSION_NAME:-}" || -z "${VERSION_CODE:-}" || -z "${SHA256_COMPAT:-}" || -z "${SHA256_FUTURE:-}" ]]; then
    echo "ERROR: $MANIFEST missing TAG / VERSION_NAME / VERSION_CODE / SHA256_COMPAT / SHA256_FUTURE" >&2
    exit 1
fi
if [[ "$TAG" != "v${VERSION_NAME}" ]]; then
    echo "ERROR: handoff TAG ($TAG) does not match VERSION_NAME ($VERSION_NAME)" >&2
    exit 1
fi

verify_apk() {
    local file="$1"
    local expect_sha="$2"
    local label="$3"
    local actual magic
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [[ "$actual" != "$expect_sha" ]]; then
        echo "ERROR: handoff $label sha256 mismatch (manifest=$expect_sha actual=$actual)" >&2
        exit 1
    fi
    magic="$(od -An -tx1 -N4 "$file" | tr -d " \n")"
    if [[ "$magic" != "504b0304" ]]; then
        echo "ERROR: handoff $label is not a zip/APK (magic=$magic): $file" >&2
        exit 1
    fi
}
verify_apk "$HANDOFF_COMPAT" "$SHA256_COMPAT" "compat"
verify_apk "$HANDOFF_FUTURE" "$SHA256_FUTURE" "future"

APKSIGNER=""
shopt -s nullglob
for candidate in \
    /work/android-sdk/build-tools/*/apksigner \
    ${ANDROID_HOME:+"$ANDROID_HOME"/build-tools/*/apksigner}
do
    if [[ -x "$candidate" ]]; then
        APKSIGNER="$candidate"
        break
    fi
done
shopt -u nullglob
if [[ -n "$APKSIGNER" ]]; then
    echo "publish_ci_release: verifying signatures with $APKSIGNER"
    "$APKSIGNER" verify --verbose "$HANDOFF_COMPAT" >/dev/null
    "$APKSIGNER" verify --verbose "$HANDOFF_FUTURE" >/dev/null
else
    echo "publish_ci_release: apksigner not found; relying on ci.sh signing gate + sha256"
fi

TARGET="$(safe_json_token "${EFREIHUB_SHA:-}")"
BODY="CI $(safe_json_token "${REF:-unknown}") $(safe_json_token "${TARGET:-unknown}")"
echo "publish_ci_release: uploading ci.sh artifacts ${TAG} (versionCode ${VERSION_CODE}, compat=${SHA256_COMPAT}, future=${SHA256_FUTURE})"

AUTH_HEADER="Authorization: Bearer ${EFREIHUB_TOKEN}"
RELEASE_JSON="$ROOT_DIR/.ci-release-create.json"
GET_JSON="$ROOT_DIR/.ci-release-get.json"
create_payload="$ROOT_DIR/.ci-release-payload.json"
trap 'rm -f "$RELEASE_JSON" "$GET_JSON" "$create_payload"' EXIT

json_field() {
    local file="$1"
    local key="$2"
    sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

# Tag was probed free in ci.sh before assemble. A create race is rare; fail
# closed rather than reusing an existing release (asset re-upload returns
# HTTP 400) or rebuilding with a different versionName.
if [[ -n "$TARGET" ]]; then
    printf '{"tag_name":"%s","name":"%s","body":"%s","target_commitish":"%s"}\n' \
        "$TAG" "$TAG" "$BODY" "$TARGET" > "$create_payload"
else
    printf '{"tag_name":"%s","name":"%s","body":"%s"}\n' \
        "$TAG" "$TAG" "$BODY" > "$create_payload"
fi
code="$(curl -sS -o "$RELEASE_JSON" -w '%{http_code}' \
    -H "$AUTH_HEADER" \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    -X POST \
    --data-binary @"$create_payload" \
    "${API_BASE}/repos/${OWNER}/${REPO}/releases" || true)"
RELEASE_ID=""
if [[ "$code" == "201" ]]; then
    RELEASE_ID="$(json_field "$RELEASE_JSON" id)"
else
    echo "ERROR: create release ${TAG} HTTP ${code} (tag was probed free in ci.sh; retry the job)" >&2
    cat "$RELEASE_JSON" >&2 || true
    exit 1
fi
if [[ -z "$RELEASE_ID" ]]; then
    echo "ERROR: could not parse release id" >&2
    exit 1
fi

upload_asset() {
    local file="$1"
    local name="$2"
    echo "publish_ci_release: uploading ${name}"
    curl -sS -f \
        -H "$AUTH_HEADER" \
        -H "Content-Type: application/octet-stream" \
        -X POST \
        --data-binary @"$file" \
        "${API_BASE}/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=${name}" \
        >/dev/null
}

upload_asset "$HANDOFF_COMPAT" "app-release.apk"
upload_asset "$HANDOFF_FUTURE" "app-release-future.apk"

echo "publish_ci_release: published ${TAG} (${RELEASE_ID}) from ci.sh handoff (no rebuild)"
