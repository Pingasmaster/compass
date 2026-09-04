#!/usr/bin/env bash
# Shared release version / free-tag helpers for ci.sh + publish_ci_release.sh.
# Sourced only; do not execute. Never prints EFREIHUB_TOKEN or signing material.
#
# version_triple: first three numeric dotted components. Do not use a
# "min == remaining" check after stripping one component to detect a missing
# patch: that breaks when minor and patch are equal (e.g. 1.4.4 was misread
# as 1.4.0) - see sharemaxx dd113a9.
#
set -euo pipefail

safe_json_token() {
    printf '%s' "$1" | tr -cd 'A-Za-z0-9._/-'
}

version_triple() {
    local t="${1#v}"
    t="${t%%-*}"
    local maj min pat rest
    maj="${t%%.*}"
    if [[ "$maj" == "$t" ]]; then
        min=0
        pat=0
    else
        rest="${t#*.}"
        min="${rest%%.*}"
        if [[ "$min" == "$rest" ]]; then
            pat=0
        else
            pat="${rest#*.}"
            pat="${pat%%.*}"
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

# Probe the next free vX.Y.Z tag on the efreihub releases API.
# Requires: EFREIHUB_TOKEN, and optionally EFREIHUB_API_URL / EFREIHUB_REPOSITORY.
# Sets RELEASE_TAG and RELEASE_VERSION_NAME in the caller.
# Temp files go under $1 (caller-provided work dir); caller cleans up.
probe_free_release_tag() {
    local work_dir="$1"
    local base_version_name="$2"
    local api_base="${EFREIHUB_API_URL:-https://efrei.app:50002/hub/api/v1}"
    api_base="${api_base%/}"
    local repo_slug="${EFREIHUB_REPOSITORY:-admin/compass}"
    local owner="${repo_slug%%/*}"
    local repo="${repo_slug#*/}"
    if [[ "$owner" == "$repo_slug" || -z "$repo" || "$repo" == *"/"* ]]; then
        echo "ERROR: EFREIHUB_REPOSITORY must be owner/name, got ${repo_slug}" >&2
        return 1
    fi
    if [[ -z "${EFREIHUB_TOKEN:-}" ]]; then
        echo "ERROR: EFREIHUB_TOKEN is unset; cannot probe a free release tag" >&2
        return 1
    fi

    local auth_header="Authorization: Bearer ${EFREIHUB_TOKEN}"
    local list_json="$work_dir/.ci-release-list.json"
    local get_json="$work_dir/.ci-release-get.json"
    mkdir -p "$work_dir"

    local list_code
    list_code="$(curl -sS -o "$list_json" -w '%{http_code}' \
        -H "$auth_header" \
        -H "Accept: application/json" \
        "${api_base}/repos/${owner}/${repo}/releases" || true)"
    if [[ "$list_code" != "200" ]]; then
        echo "ERROR: list releases HTTP ${list_code}" >&2
        cat "$list_json" >&2 || true
        return 1
    fi

    local highest="$base_version_name"
    local tag maj min pat
    while IFS= read -r tag; do
        [[ -z "$tag" ]] && continue
        if version_gt "$tag" "$highest"; then
            highest="${tag#v}"
            highest="${highest%%-*}"
            read -r maj min pat <<<"$(version_triple "$highest")"
            highest="${maj}.${min}.${pat}"
        fi
    done < <(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$list_json")

    local free_tag="v$(increment_patch "$highest")"
    local attempts=0
    local get_code
    while (( attempts < 20 )); do
        attempts=$((attempts + 1))
        get_code="$(curl -sS -o "$get_json" -w '%{http_code}' \
            -H "$auth_header" \
            -H "Accept: application/json" \
            "${api_base}/repos/${owner}/${repo}/releases/tags/${free_tag}" || true)"
        if [[ "$get_code" == "404" ]]; then
            break
        fi
        if [[ "$get_code" != "200" ]]; then
            echo "ERROR: probe release tag ${free_tag} HTTP ${get_code}" >&2
            cat "$get_json" >&2 || true
            return 1
        fi
        echo "release_version: ${free_tag} already exists; bumping patch"
        free_tag="v$(increment_patch "${free_tag#v}")"
    done
    if (( attempts >= 20 )); then
        echo "ERROR: could not find a free release tag" >&2
        return 1
    fi

    RELEASE_TAG="$free_tag"
    RELEASE_VERSION_NAME="${free_tag#v}"
}

# Well-known same-guest handoff from ci.sh -> publish_ci_release.sh.
# Prefer /work (Firecracker work disk). Override with COMPASS_CI_RELEASE_DIR.
ci_release_handoff_dir() {
    if [[ -n "${COMPASS_CI_RELEASE_DIR:-}" ]]; then
        printf '%s\n' "${COMPASS_CI_RELEASE_DIR}"
        return 0
    fi
    if [[ -d /work && -w /work ]]; then
        printf '%s\n' /work/compass-ci-release
        return 0
    fi
    return 1
}
