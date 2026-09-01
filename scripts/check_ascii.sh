#!/usr/bin/env bash
#
# Enforce the ASCII-only source policy (see CLAUDE.md).
#
# Scans all git-tracked files for non-ASCII bytes, excluding the documented
# exceptions (localization resources, generated files, binaries).
#
# Usage:
#   scripts/check_ascii.sh          # exit 1 if violations found (CI mode)
#   scripts/check_ascii.sh --warn   # print warnings only, always exit 0
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

WARN_ONLY=0
[ "${1:-}" = "--warn" ] && WARN_ONLY=1

# Path prefixes/globs where non-ASCII is legitimate. Keep in sync with CLAUDE.md.
is_exempt() {
    case "$1" in
        # Localization resources, every locale including the default
        */src/main/res/values*/*) return 0 ;;
        # Captured real server responses; bytes must stay faithful
        */src/test/resources/fixtures/*) return 0 ;;
        # Gradle-generated wrapper
        gradlew) return 0 ;;
        # Generated AOT profiles (install_baseline_profiles.sh -> app/src/release/).
        */src/release/baseline-prof.txt|*/src/release/startup-prof.txt) return 0 ;;
        */baselineProfiles/*) return 0 ;;
        # Binaries
        *.png|*.webp|*.jpg|*.jks|*.jar|*.apk|*.ico|*.gif|*.mp3|*.wav|*.so|*.aar) return 0 ;;
    esac
    return 1
}

# If git is missing, or the forge overlays an empty .git (efreihub Actions
# bind-mounts a tmpfs over /work/.git), scan the tree instead of silently
# passing an empty ls-files list.
list_source_files() {
    if command -v git >/dev/null 2>&1 \
        && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        local tracked
        tracked="$(git ls-files)"
        if [ -n "$tracked" ]; then
            printf '%s\n' "$tracked"
            return 0
        fi
    fi
    find . -type f \
        ! -path './.git/*' \
        ! -path './.gradle/*' \
        ! -path './.kotlin/*' \
        ! -path './build/*' \
        ! -path '*/build/*' \
        ! -path './.idea/*' \
        ! -path './.worktrees/*' \
        ! -name '*.apk' \
        ! -name '*.jar' \
        ! -name '*.so' \
        ! -name '*.aar' \
        ! -name '*.png' \
        ! -name '*.webp' \
        ! -name '*.jpg'
}

violations=0
while IFS= read -r f; do
    f="${f#./}"
    is_exempt "$f" && continue
    [ -f "$f" ] || continue
    if LC_ALL=C grep -qP '[^\x00-\x7F]' "$f" 2>/dev/null; then
        violations=$((violations + 1))
        echo "non-ASCII characters in: $f"
        LC_ALL=C grep -nP '[^\x00-\x7F]' "$f" | head -5 | sed 's/^/    /'
    fi
done < <(list_source_files)

if [ "$violations" -gt 0 ]; then
    if [ "$WARN_ONLY" = 1 ]; then
        echo "WARNING: $violations file(s) contain non-ASCII characters (policy: ASCII-only outside localization files, see CLAUDE.md)."
        exit 0
    fi
    echo "ERROR: $violations file(s) contain non-ASCII characters. ASCII-only policy (see CLAUDE.md): use -, ->, ..., etc. instead of typographic characters."
    exit 1
fi
[ "$WARN_ONLY" = 1 ] || echo "ASCII check passed."
exit 0
