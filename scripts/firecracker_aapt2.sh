#!/usr/bin/env bash
# Firecracker Alpine guest: AGP aapt2 is a glibc PIE. musl libgcc_s SIGSEGVs
# at ip 0x4310 even with LD_LIBRARY_PATH. Run aapt2 through the GNU loader
# baked at /opt/gnu (ld.so + libc + libgcc_s). No outbound Debian/Google
# fetch: guest DNS to those hosts flakes and aborts the job.
#
# Source from scripts/ci.sh. No-op on a glibc host (no /opt/gnu required).
set -euo pipefail

if [ ! -d /usr/glibc-compat ] && [ ! -d /opt/gnu ]; then
    return 0 2>/dev/null || exit 0
fi

GNU_ROOT=""
GNU_LD=""
for cand in /opt/gnu /work/gnu "${HOME:-}/gnu-root"; do
    [ -n "$cand" ] || continue
    if [ -x "$cand/ld-linux-x86-64.so.2" ]; then
        GNU_ROOT="$cand"
        GNU_LD="$cand/ld-linux-x86-64.so.2"
        break
    fi
    if [ -x "$cand/ld.so" ]; then
        GNU_ROOT="$cand"
        GNU_LD="$cand/ld.so"
        break
    fi
done

if [ -z "$GNU_ROOT" ] || [ -z "$GNU_LD" ]; then
    echo "ERROR: GNU libc for aapt2 is missing (expected /opt/gnu)." >&2
    echo "ERROR: bake ld-linux-x86-64.so.2 + libc.so.6 + libgcc_s.so.1 into the Firecracker guest; do not download ftp.debian.org from CI." >&2
    ls -la /opt /opt/gnu /usr/glibc-compat /usr/glibc-compat/lib 2>&1 | head -40 >&2 || true
    exit 1
fi

if [ ! -e "$GNU_ROOT/libc.so.6" ] || [ ! -e "$GNU_ROOT/libgcc_s.so.1" ]; then
    echo "ERROR: $GNU_ROOT is incomplete (need libc.so.6 and libgcc_s.so.1)" >&2
    ls -la "$GNU_ROOT" >&2 || true
    exit 1
fi

echo "CI: GNU aapt2 loader $GNU_LD lib=$GNU_ROOT"

wrap_glibc_bin() {
    local bin="$1"
    local extra_lib="${2:-}"
    local real="${bin}.real"
    local hdr libs
    [ -n "$bin" ] && [ -f "$bin" ] || return 0
    libs="${GNU_ROOT}"
    if [ -n "$extra_lib" ] && [ -d "$extra_lib" ]; then
        libs="${GNU_ROOT}:${extra_lib}"
    fi
    if [ -f "$real" ]; then
        if grep -q 'ld-linux-x86-64.so.2' "$bin" 2>/dev/null; then
            return 0
        fi
        rm -f "$bin"
    else
        hdr=$(head -c 4 "$bin" 2>/dev/null || true)
        [ "$hdr" = $'\177ELF' ] || return 0
        mv "$bin" "$real"
    fi
    cat >"$bin" <<WRAP
#!/bin/sh
exec ${GNU_LD} --library-path "${libs}" ${real} "\$@"
WRAP
    chmod +x "$bin" "$real"
    echo "CI: wrapped $(basename "$bin") $bin via $GNU_LD lib=$libs"
}

DEST=/work/aapt2
mkdir -p "$DEST"
SDK_AAPT2=""
for cand in \
    /opt/android-sdk/build-tools/37.0.0/aapt2 \
    /opt/android-sdk/build-tools/36.0.0/aapt2 \
    /work/android-sdk/build-tools/37.0.0/aapt2 \
    /work/android-sdk/build-tools/36.0.0/aapt2; do
    if [ -f "$cand" ]; then
        SDK_AAPT2="$cand"
        break
    fi
done
if [ -z "$SDK_AAPT2" ]; then
    SDK_AAPT2="$(find /opt/android-sdk /work/android-sdk -type f -name aapt2 2>/dev/null | head -1 || true)"
fi
if [ -z "$SDK_AAPT2" ] || [ ! -f "$SDK_AAPT2" ]; then
    echo "ERROR: no SDK aapt2 to wrap (guest glibc aapt2 would SIGSEGV at 0x4310)" >&2
    ls -la /opt/android-sdk/build-tools 2>&1 >&2 || true
    exit 1
fi

SDK_DIR="$(dirname "$SDK_AAPT2")"
SDK_LIB64="${SDK_DIR}/lib64"
echo "CI: using SDK aapt2 $SDK_AAPT2"
cp -a "$SDK_AAPT2" "$DEST/aapt2"
chmod +x "$DEST/aapt2"
wrap_glibc_bin "$DEST/aapt2" "$SDK_LIB64"

if [ -f "$SDK_DIR/zipalign" ]; then
    cp -a "$SDK_DIR/zipalign" "$DEST/zipalign"
    chmod +x "$DEST/zipalign"
    wrap_glibc_bin "$DEST/zipalign" "$SDK_LIB64"
    export ZIPALIGN="$DEST/zipalign"
    echo "CI: ZIPALIGN=$ZIPALIGN"
fi

if [ -d "${GRADLE_USER_HOME:-}/caches" ]; then
    while IFS= read -r bin; do
        wrap_glibc_bin "$bin"
    done < <(find "${GRADLE_USER_HOME}/caches" -type f -name aapt2 2>/dev/null | head -50)
fi

AAPT2_OVERRIDE="$DEST/aapt2"
if grep -q '^android.aapt2FromMavenOverride=' "$ROOT_DIR/gradle.properties" 2>/dev/null; then
    sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=${AAPT2_OVERRIDE}|" "$ROOT_DIR/gradle.properties"
else
    printf '\nandroid.aapt2FromMavenOverride=%s\n' "$AAPT2_OVERRIDE" >>"$ROOT_DIR/gradle.properties"
fi
echo "CI: android.aapt2FromMavenOverride=$AAPT2_OVERRIDE"

if ! "$AAPT2_OVERRIDE" version >/dev/null 2>&1; then
    echo "ERROR: wrapped aapt2 failed to start" >&2
    "$AAPT2_OVERRIDE" version >&2 || true
    exit 1
fi
echo "CI: aapt2 version: $("$AAPT2_OVERRIDE" version 2>/dev/null | head -1)"

unset -f wrap_glibc_bin
unset GNU_ROOT GNU_LD DEST SDK_AAPT2 SDK_DIR SDK_LIB64 AAPT2_OVERRIDE cand bin
