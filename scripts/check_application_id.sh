#!/usr/bin/env bash
#
# Fail if any previous applicationId is still in the tree. The only allowed
# id is app.efrei.compass (Kotlin namespace, Gradle applicationId, ART
# profiles, tests, and scripts).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Do not write the old id as one literal; reconstruct it so this file cannot
# be a hit for the same search.
old_id="com.$(printf '%s' 'compass').app"
old_path="com/$(printf '%s' 'compass')/app"
want="app.efrei.compass"

hits="$(
    find . -type f \
        ! -path './.git/*' \
        ! -path './.gradle/*' \
        ! -path './.kotlin/*' \
        ! -path './build/*' \
        ! -path '*/build/*' \
        ! -path './.idea/*' \
        ! -path './.worktrees/*' \
        ! -path './mappings/*' \
        ! -name 'app-release-mapping.txt' \
        ! -name 'app-release-future-mapping.txt' \
        -print0 \
        | xargs -0 grep -I -F -n -e "$old_id" -e "$old_path" || true
)"

if [ -n "$hits" ]; then
    echo "ERROR: previous applicationId still mentioned. Use $want only."
    printf '%s\n' "$hits"
    exit 1
fi

echo "applicationId check passed ($want only)."
exit 0
