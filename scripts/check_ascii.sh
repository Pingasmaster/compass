#!/usr/bin/env bash
#
# Enforce the ASCII-only source policy (see CLAUDE.md).
#
# Usage:
#   scripts/check_ascii.sh          # exit 1 if violations found (CI mode)
#   scripts/check_ascii.sh --warn   # print warnings only, always exit 0
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

WARN_ONLY=0
[ "${1:-}" = "--warn" ] && WARN_ONLY=1

is_exempt() {
    case "$1" in
        */src/main/res/values*/*) return 0 ;;
        */src/test/resources/fixtures/*) return 0 ;;
        gradlew) return 0 ;;
        *.png|*.webp|*.jpg|*.jks|*.jar|*.apk|*.ico|*.gif|*.mp3|*.wav|*.so|*.aar|*.onnx) return 0 ;;
        # Generated baseline profiles may contain binary-ish content in comments
        */baselineProfiles/*) return 0 ;;
    esac
    return 1
}

violations=0
while IFS= read -r f; do
    is_exempt "$f" && continue
    [ -f "$f" ] || continue
    if LC_ALL=C grep -qP '[^\x00-\x7F]' "$f" 2>/dev/null; then
        violations=$((violations + 1))
        echo "non-ASCII characters in: $f"
        LC_ALL=C grep -nP '[^\x00-\x7F]' "$f" | head -5 | sed 's/^/    /'
    fi
done < <(git ls-files)

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
