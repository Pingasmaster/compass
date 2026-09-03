#!/usr/bin/env bash
#
# After a green ./scripts/ci.sh, create a git tag + efreihub release and
# upload two signed fat APKs: compat (armeabi-v7a + arm64-v8a + x86 +
# x86_64 + riscv64) as app-release.apk, future (arm64-v8a + x86_64 +
# riscv64) as app-release-future.apk. The in-app updater lists
# https://efrei.app:50002/hub/api/v1/repos/admin/compass/releases.
#
# Production assemble must pass -Pcompass.requireReleaseSigning=true so a
# missing keystore cannot silently fall back to debug signing.
#
# Skip (exit 0) when EFREIHUB_TOKEN is unset so push CI stays green until the
# colocated runner injects a write PAT (--sandbox-efreihub-token-file +
# --sandbox-efreihub-token-repo admin/compass). Skip tag refs so creating
# the release tag cannot loop another publish.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

REF="${EFREIHUB_REF:-}"
case "$REF" in
    refs/tags/*)
        echo "publish_ci_release: skip (ref is a tag: $REF)"
        exit 0
        ;;
esac

if [[ -z "${EFREIHUB_TOKEN:-}" ]]; then
    echo "publish_ci_release: skip (EFREIHUB_TOKEN unset; runner needs --sandbox-efreihub-token-file and --sandbox-efreihub-token-repo admin/compass)"
    exit 0
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

SIGNING_DIR="${COMPASS_SIGNING_DIR:-/usr/local/compass-signing}"
if [[ ! -f "$ROOT_DIR/release-keystore.jks" && -f "$SIGNING_DIR/release-keystore.jks" ]]; then
    cp "$SIGNING_DIR/release-keystore.jks" "$ROOT_DIR/release-keystore.jks"
fi
if [[ ! -f "$ROOT_DIR/.password-signing-keys" && -f "$SIGNING_DIR/.password-signing-keys" ]]; then
    cp "$SIGNING_DIR/.password-signing-keys" "$ROOT_DIR/.password-signing-keys"
fi
if [[ ! -f "$ROOT_DIR/release-keystore.jks" || ! -f "$ROOT_DIR/.password-signing-keys" ]]; then
    echo "publish_ci_release: skip (keystore not bound at $SIGNING_DIR or repo root; push CI stays green)"
    exit 0
fi

BASE_VERSION_NAME="$(sed -n 's/^[[:space:]]*val baseVersionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n 1)"
if [[ -z "$BASE_VERSION_NAME" ]]; then
    echo "ERROR: could not read baseVersionName from app/build.gradle.kts" >&2
    exit 1
fi

safe_json_token() {
    printf '%s' "$1" | tr -cd 'A-Za-z0-9._/-'
}

# First three numeric dotted components; extra timestamp parts are ignored.
version_triple() {
    local t="${1#v}"
    t="${t%%-*}"
    local maj min pat
    maj="${t%%.*}"
    t="${t#*.}"
    if [[ "$maj" == "$t" ]]; then
        min=0
        pat=0
    else
        min="${t%%.*}"
        t="${t#*.}"
        if [[ "$min" == "$t" ]]; then
            pat=0
        else
            pat="${t%%.*}"
        fi
    fi
    maj="${maj%%[!0-9]*}"
    min="${min%%[!0-9]*}"
    pat="${pat%%[!0-9]*}"
    printf '%s %s %s\n' "${maj:-0}" "${min:-0}" "${pat:-0}"
}

version_gt() {
    local a_maj a_min a_pat b_maj b_min b_pat
    read -r a_maj a_min a_pat <<<"$(version_triple "$1")"
    read -r b_maj b_min b_pat <<<"$(version_triple "$2")"
    if (( a_maj > b_maj )); then return 0; fi
    if (( a_maj < b_maj )); then return 1; fi
    if (( a_min > b_min )); then return 0; fi
    if (( a_min < b_min )); then return 1; fi
    (( a_pat > b_pat ))
}

increment_patch() {
    local maj min pat
    read -r maj min pat <<<"$(version_triple "$1")"
    printf '%s.%s.%s\n' "$maj" "$min" "$((pat + 1))"
}

AUTH_HEADER="Authorization: Bearer ${EFREIHUB_TOKEN}"
RELEASE_JSON="$ROOT_DIR/.ci-release-create.json"
GET_JSON="$ROOT_DIR/.ci-release-get.json"
LIST_JSON="$ROOT_DIR/.ci-release-list.json"
create_payload="$ROOT_DIR/.ci-release-payload.json"
trap 'rm -f "$RELEASE_JSON" "$GET_JSON" "$LIST_JSON" "$create_payload"' EXIT

json_field() {
    local file="$1"
    local key="$2"
    sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "$file" | head -n 1
}

list_code="$(curl -sS -o "$LIST_JSON" -w '%{http_code}' \
    -H "$AUTH_HEADER" \
    -H "Accept: application/json" \
    "${API_BASE}/repos/${OWNER}/${REPO}/releases" || true)"
if [[ "$list_code" != "200" ]]; then
    echo "ERROR: list releases HTTP ${list_code}" >&2
    cat "$LIST_JSON" >&2 || true
    exit 1
fi

HIGHEST="$BASE_VERSION_NAME"
while IFS= read -r tag; do
    [[ -z "$tag" ]] && continue
    if version_gt "$tag" "$HIGHEST"; then
        HIGHEST="${tag#v}"
        HIGHEST="${HIGHEST%%-*}"
        # Keep only X.Y.Z for increment (drop leftover timestamp components).
        read -r maj min pat <<<"$(version_triple "$HIGHEST")"
        HIGHEST="${maj}.${min}.${pat}"
    fi
done < <(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$LIST_JSON")

TAG="v$(increment_patch "$HIGHEST")"
VERSION_NAME="${TAG#v}"
STAMP="$(date +%s)"
TARGET="$(safe_json_token "${EFREIHUB_SHA:-}")"
BODY="CI $(safe_json_token "${REF:-unknown}") $(safe_json_token "${TARGET:-unknown}")"

echo "publish_ci_release: assembling signed APKs as ${TAG} (versionCode ${STAMP})"
./scripts/run_gradle.sh assembleCompatRelease assembleFutureRelease \
    -Pcompass.requireReleaseSigning=true \
    -Pcompass.versionName="$VERSION_NAME" \
    -Pcompass.versionCode="$STAMP"

# Fat APKs (ndk.abiFilters, no ABI splits). AGP names have varied; accept
# both the unsplit flavor name and a leftover *universal* layout.
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
    echo "ERROR: missing fat APKs (compat='$COMPAT_APK' future='$FUTURE_APK')" >&2
    find "$ROOT_DIR/app/build/outputs/apk" -name '*.apk' -print >&2 || true
    exit 1
fi
UPLOAD_FILES=("$COMPAT_APK" "$FUTURE_APK")
UPLOAD_NAMES=("app-release.apk" "app-release-future.apk")

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
    get_code="$(curl -sS -o "$GET_JSON" -w '%{http_code}' \
        -H "$AUTH_HEADER" \
        -H "Accept: application/json" \
        "${API_BASE}/repos/${OWNER}/${REPO}/releases/tags/${TAG}" || true)"
    if [[ "$get_code" == "200" ]]; then
        RELEASE_ID="$(json_field "$GET_JSON" id)"
        echo "publish_ci_release: release ${TAG} already exists (${RELEASE_ID})"
    else
        echo "ERROR: create release HTTP ${code}; get-by-tag HTTP ${get_code}" >&2
        cat "$RELEASE_JSON" >&2 || true
        exit 1
    fi
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

i=0
while (( i < ${#UPLOAD_FILES[@]} )); do
    upload_asset "${UPLOAD_FILES[$i]}" "${UPLOAD_NAMES[$i]}"
    i=$((i + 1))
done

echo "publish_ci_release: published ${TAG} (${RELEASE_ID})"
