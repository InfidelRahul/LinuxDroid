#!/usr/bin/env bash
#
# LinuxDroid — Bootstrap the cross-built dependencies required by libweston.
#
# The mirror-main Weston (see native/weston/src/meson.build) requires the
# following at *configure* time (all as pkg-config dependencies, no option can
# disable them):
#
#     wayland-server >= 1.24   wayland-client >= 1.24   pixman-1 >= 0.25.2
#     xkbcommon >= 0.5         libinput >= 1.2          libevdev
#     libdrm >= 2.4.108        libdisplay-info (>=0.2,<0.5)     wayland-protocols >= 1.46
#     wayland-scanner (host tool + pkg-config, native: true)
#
# The mirror main's shared/meson.build ALSO declares lib_cairo_shared
# (dependency('cairo') + dependency('libpng')) unconditionally, and the
# headless-backend links it — so cairo + libpng + zlib are required at configure
# AND link time, even though they are NOT linked into libweston-<major>.so.
#
# Of these, the libweston shared library that the LinuxDroid bridge links
# against only actually depends on:
#
#     wayland-server, pixman-1, libdrm, xkbcommon      (+ libm/libdl from the NDK)
#
# The rest (including cairo/libpng for the headless backend module) are consumed
# at configure/build time only; they are still cross-built and installed here so
# the Meson configure of libweston succeeds and stays deterministic — no distro
# package is ever used as a substitute.
#
# This script cross-compiles everything for Android arm64-v8a / API 36 using the
# configured NDK, and installs all headers + libraries + pkg-config files into a
# single DEP_SYSROOT that build-libweston.sh consumes via DEP_PKG_CONFIG_PATH.
#
# Sources: InfidelRahul/ mirror repositories are git-cloned from their `main`
# branch and the resolved commit is recorded under
# $DEP_SYSROOT/.git-commits/<name> (verified by verify-weston.sh). Official
# upstream dependencies use pinned stable releases; their tarball SHA-256 is
# verified when set.
#
# Usage:
#   ANDROID_NDK_ROOT=/opt/ndk \
#   DEP_SYSROOT=/path/to/sysroot \
#   native/weston/bootstrap-deps.sh
#
# Environment:
#   ANDROID_NDK_ROOT   (or ANDROID_NDK_HOME) : Android NDK r29+
#   DEP_SYSROOT        (default: $SCRIPT_DIR/deps/sysroot)
#   JOBS               (default: nproc)
#
# The produced DEP_SYSROOT is consumed by native/weston/build-libweston.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API=36
HOST_TAG="linux-x86_64"

# ---------------------------------------------------------------------------
# Toolchain
# ---------------------------------------------------------------------------
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_ROOT" ]]; then
    echo "[weston-deps] ERROR: ANDROID_NDK_ROOT / ANDROID_NDK_HOME not set." >&2
    exit 1
fi
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [[ ! -d "$TOOLCHAIN" ]]; then
    echo "[weston-deps] ERROR: NDK toolchain not found at $TOOLCHAIN." >&2
    exit 1
fi

command -v meson >/dev/null 2>&1 || { echo "[weston-deps] ERROR: meson not found on PATH." >&2; exit 1; }
command -v ninja >/dev/null 2>&1 || { echo "[weston-deps] ERROR: ninja not found on PATH." >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "[weston-deps] ERROR: curl not found on PATH." >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "[weston-deps] ERROR: sha256sum not found on PATH." >&2; exit 1; }
command -v pkg-config >/dev/null 2>&1 || { echo "[weston-deps] ERROR: pkg-config not found on PATH." >&2; exit 1; }

DEP_SYSROOT="${DEP_SYSROOT:-$SCRIPT_DIR/deps/sysroot}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
WORK_DIR="$SCRIPT_DIR/deps/work"
SRC_DIR="$SCRIPT_DIR/deps/src"

CC="$TOOLCHAIN/aarch64-linux-android${API}-clang"
CXX="$TOOLCHAIN/aarch64-linux-android${API}-clang++"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
STRIP="$TOOLCHAIN/llvm-strip"

# Hard guarantee: this is an ARM64/AArch64-ONLY build. The target triple is
# baked into the NDK clang names; refuse to proceed if the compiler is anything
# other than an aarch64 clang (we do NOT build or ship x86 / x86_64 / armeabi).
if [[ ! -x "$CC" || "$CC" != *"-aarch64-linux-android"* ]]; then
    echo "[weston-deps] ERROR: CC is not an aarch64-linux-android NDK clang: $CC" >&2
    echo "[weston-deps] LinuxDroid supports arm64-v8a / AArch64 ONLY. Set ANDROID_NDK_ROOT to an arm64-capable NDK." >&2
    exit 1
fi
if [[ ! -x "$CXX" || "$CXX" != *"-aarch64-linux-android"* ]]; then
    echo "[weston-deps] ERROR: CXX is not an aarch64-linux-android NDK clang++: $CXX" >&2
    exit 1
fi

log() { printf '[weston-deps] %s\n' "$*"; }
die() { printf '[weston-deps] ERROR: %s\n' "$*" >&2; exit 1; }

# Start from a clean sysroot so a stale/failed earlier build can never mask a
# missing dependency (missing deps must fail CI, never silently pass).
rm -rf "$DEP_SYSROOT/lib" "$DEP_SYSROOT/include" "$DEP_SYSROOT/bin" \
    "$DEP_SYSROOT/share" "$DEP_SYSROOT/.git-commits"
mkdir -p "$DEP_SYSROOT/lib/pkgconfig" "$DEP_SYSROOT/lib" "$DEP_SYSROOT/include" \
    "$DEP_SYSROOT/bin" "$DEP_SYSROOT/share/pkgconfig"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" "$SRC_DIR"

# Cross deps install their .pc into either lib/pkgconfig or share/pkgconfig; make
# pkg-config look in both. PKG_CONFIG_SYSROOT_DIR prefixes the paths so meson
# points -I/-L at the target sysroot, not the host.
export PKG_CONFIG_SYSROOT_DIR="$DEP_SYSROOT"
export PKG_CONFIG_PATH="$DEP_SYSROOT/lib/pkgconfig:$DEP_SYSROOT/share/pkgconfig"

# ---------------------------------------------------------------------------
# Deterministic upstream sources.
#
# Source policy (see native/weston/weston.spec.json):
#   - Repositories under the InfidelRahul/ GitHub org (LinuxDroid mirrors) are
#     fetched from their `main` branch and the exact resolved commit is recorded
#     in $DEP_SYSROOT/.git-commits/<name> and verified (no fixed SHA is pinned).
#   - All other (official upstream) dependencies use the latest stable release
#     and pin its archive SHA-256 where known.
# ---------------------------------------------------------------------------
FFI_VERSION="3.4.6"
FFI_URL="https://github.com/libffi/libffi/releases/download/v${FFI_VERSION}/libffi-${FFI_VERSION}.tar.gz"
FFI_SHA256=""

# The InfidelRahul/ GitHub org hosts LinuxDroid mirrors; per the integration
# policy these track their `main` branch (resolved commit recorded per build).
# The mirror-main weston (native/weston/src) requires wayland-server/client
# >= 1.24.0 and wayland-protocols >= 1.46.
WAYLAND_REPO="https://github.com/InfidelRahul/wayland"
WAYLAND_BRANCH="main"

WAYLAND_PROTOCOLS_REPO="https://github.com/InfidelRahul/wayland-protocols"
WAYLAND_PROTOCOLS_BRANCH="main"

PIXMAN_REPO="https://github.com/InfidelRahul/pixman"
PIXMAN_BRANCH="main"

# libxkbcommon is an OFFICIAL upstream dependency (not an InfidelRahul mirror),
# so it uses the latest stable release and its exact resolved commit.
XKBCOMMON_VERSION="xkbcommon-1.13.2"
XKBCOMMON_URL="https://github.com/xkbcommon/libxkbcommon/archive/refs/tags/${XKBCOMMON_VERSION}.tar.gz"
XKBCOMMON_SHA256="acc4d5f7c3cbba5f9f8d08d8bdbeede84ecede46792f47929aa9321873385528"

LIBEVDEV_VERSION="1.13.1"
LIBEVDEV_URL="https://gitlab.freedesktop.org/libevdev/libevdev/-/archive/${LIBEVDEV_VERSION}/libevdev-${LIBEVDEV_VERSION}.tar.xz"
LIBEVDEV_SHA256=""

LIBDRM_VERSION="2.4.120"
LIBDRM_URL="https://gitlab.freedesktop.org/mesa/drm/-/archive/libdrm-${LIBDRM_VERSION}/drm-libdrm-${LIBDRM_VERSION}.tar.gz"
LIBDRM_SHA256=""

LIBINPUT_VERSION="1.26.0"
LIBINPUT_URL="https://gitlab.freedesktop.org/libinput/libinput/-/archive/${LIBINPUT_VERSION}/libinput-${LIBINPUT_VERSION}.tar.gz"
LIBINPUT_SHA256=""

LIBDISPLAY_INFO_VERSION="0.2.0"
LIBDISPLAY_INFO_URL="https://gitlab.freedesktop.org/emersion/libdisplay-info/-/archive/${LIBDISPLAY_INFO_VERSION}/libdisplay-info-${LIBDISPLAY_INFO_VERSION}.tar.gz"
LIBDISPLAY_INFO_SHA256=""

# xkeyboard-config is the reference keyboard-layout database (XKB data). It is an
# OFFICIAL upstream project (no InfidelRahul mirror), so it uses the latest
# stable release (2.48). It is NOT a build-time pkg-config dependency of weston;
# libxkbcommon locates its data at runtime from the standard Linux XKB data path,
# so the layout database is cross-installed into the DEP_SYSROOT's share/X11/xkb
# tree and must be reachable from the Linux (PRoot) userspace at runtime. The
# XKB data set is architecture-independent, so we just install it.
XKBCONFIG_VERSION="xkeyboard-config-2.48"
XKBCONFIG_URL="https://gitlab.freedesktop.org/xkeyboard-config/xkeyboard-config/-/archive/${XKBCONFIG_VERSION}/${XKBCONFIG_VERSION}.tar.gz"
XKBCONFIG_SHA256=""

# The mirror main's Weston requires Cairo + libpng at configure time and to link
# its headless-backend (shared/meson.build declares lib_cairo_shared with
# dependency('cairo') / dependency('libpng') unconditionally; the headless
# backend links dep_lib_cairo_shared). These are BUILD dependencies only — they
# are NOT part of the LinuxDroid renderer (Pixman -> GLES/EGL -> Vulkan) and are
# NOT linked into the LinuxDroid bridge. We build the minimal image-backend
# Cairo (freetype/fontconfig/glib/x11 disabled) plus zlib + libpng.
ZLIB_VERSION="1.3.1"
ZLIB_URL="https://github.com/madler/zlib/archive/refs/tags/v${ZLIB_VERSION}.tar.gz"
ZLIB_SHA256=""

LIBPNG_VERSION="1.6.43"
LIBPNG_URL="https://github.com/pnggroup/libpng/archive/refs/tags/v${LIBPNG_VERSION}.tar.gz"
LIBPNG_SHA256=""

CAIRO_VERSION="1.18.2"
CAIRO_URL="https://gitlab.freedesktop.org/cairo/cairo/-/archive/${CAIRO_VERSION}/cairo-${CAIRO_VERSION}.tar.xz"
CAIRO_SHA256=""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
fetch_and_extract() {
    local name="$1" url="$2" sha="$3"
    local archive="$WORK_DIR/${name}.tar.gz"
    log "Fetching ${name}: ${url}"
    if ! curl -fsSL --retry 3 "$url" -o "$archive"; then
        die "Failed to download ${name} from ${url}."
    fi
    if [[ -n "$sha" ]]; then
        local actual
        actual="$(sha256sum "$archive" | awk '{print $1}')"
        if [[ "$actual" != "$sha" ]]; then
            die "${name} SHA-256 mismatch: got $actual expected $sha (refusing unverified source)."
        fi
        log "${name} SHA-256 verified."
    fi
    rm -rf "$SRC_DIR/$name"
    mkdir -p "$SRC_DIR/$name"
    tar -xf "$archive" -C "$SRC_DIR/$name" --strip-components=1
}

# Git-clone a tracked `main` dependency from an InfidelRahul mirror and record
# the exact resolved commit. The clone is a real git checkout so the commit is
# verifiable via `git rev-parse HEAD` and buildable with Meson straight from the
# source tree (no autoreconf needed for wayland/wayland-protocols/pixman).
# Records the resolved commit under $DEP_SYSROOT/.git-commits/<name>.
fetch_git_clone() {
    local name="$1" repo="$2" branch="$3"
    log "Cloning ${name} (${branch}) from ${repo}"
    rm -rf "$SRC_DIR/$name"
    if ! git clone --depth 1 --branch "$branch" "$repo" "$SRC_DIR/$name" 2>"$WORK_DIR/git.err"; then
        cat "$WORK_DIR/git.err" >&2 || true
        die "Failed to clone ${name} from ${repo}."
    fi
    local resolved
    resolved="$(git -C "$SRC_DIR/$name" rev-parse HEAD)"
    mkdir -p "$DEP_SYSROOT/.git-commits"
    printf '%s\n' "$resolved" > "$DEP_SYSROOT/.git-commits/$name"
    log "$name resolved $branch commit: $resolved"
}

meson_cross_file() {
    local f="$SCRIPT_DIR/deps/cross-${API}.ini"
    cat > "$f" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
ranlib = '$RANLIB'
strip = '$STRIP'
pkg-config = 'pkg-config'

[host_machine]
system = 'linux'
cpu_family = 'aarch64'
cpu = 'arm64-v8a'
endian = 'little'

[built-in options]
b_lundef = true
b_pie = true
c_args = ['-DANDROID', '-D__ANDROID__', '-fPIC']
cpp_args = ['-DANDROID', '-D__ANDROID__', '-fPIC']

[properties]
needs_exe_wrapper = true
EOF
    echo "$f"
}

# Self-healing meson setup: tries the given -D options, and if meson rejects any
# (e.g. the option name differs in this release), retry with the offending flag
# removed. This keeps the bootstrap robust to option-name drift across upstream
# releases while still passing through the flags that are valid.
meson_setup() {
    local build="$1" src="$2"; shift 2
    local opts=("$@")
    local attempt
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        if meson setup "$build" "$src" "${opts[@]}" 2>"$WORK_DIR/meson.err"; then
            return 0
        fi
        # Meson reports unknown options like:
        #   ERROR: Unknown option "foo"
        #   ERROR: Options "foo" and "bar" are unknown
        # Extract the offending option name, then match it to the -D flag we passed.
        local bad
        bad="$(grep -oE "\"(get_option|unknown).*?\"|Unknown option \"[^\"]+\"|unknown option \"[^\"]+\"" \
            "$WORK_DIR/meson.err" 2>/dev/null \
            | grep -oE '[a-zA-Z0-9_-]+' | tail -1 || true)"
        if [[ -z "$bad" ]]; then
            # Also handle "ERROR: Options \"a\" and \"b\" are unknown".
            bad="$(grep -oiE "unknown" "$WORK_DIR/meson.err" >/dev/null \
                && grep -oE '"[a-zA-Z0-9_-]+"' "$WORK_DIR/meson.err" | head -1 | tr -d '"' || true)"
        fi
        if [[ -z "$bad" ]]; then
            # Not an unknown-option error (e.g. missing dependency): surface it.
            cat "$WORK_DIR/meson.err" >&2
            return 1
        fi
        local flag="-D$bad"
        log "Meson rejected option ${flag}; retrying without it."
        local filtered=()
        for o in "${opts[@]}"; do
            [[ "$o" == "$flag" ]] && continue
            filtered+=("$o")
        done
        if [[ "${#filtered[@]}" -eq "${#opts[@]}" ]]; then
            # Did not actually remove anything; surface the real error.
            cat "$WORK_DIR/meson.err" >&2
            return 1
        fi
        opts=("${filtered[@]}")
        rm -rf "$build"
        mkdir -p "$build"
    done
    cat "$WORK_DIR/meson.err" >&2
    return 1
}

# ---------------------------------------------------------------------------
# 1. libffi  (libwayland dependency)
# ---------------------------------------------------------------------------
build_libffi() {
    log "Building libffi ${FFI_VERSION}"
    fetch_and_extract "libffi" "$FFI_URL" "$FFI_SHA256"
    local d="$SRC_DIR/libffi" b="$WORK_DIR/ffi-build"
    rm -rf "$b"; mkdir -p "$b"
    (
        cd "$b"
        # Autotools cross-compile: --build must be set so configure knows it is
        # cross-compiling and does not try to RUN the conftest (which cannot be
        # executed on the x86_64 build host), and -fuse-ld=lld pins the NDK linker.
        env CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="-fPIE -fPIC -DANDROID -D__ANDROID__" \
            LDFLAGS="-fuse-ld=lld" \
            "$d/configure" \
                --build="$(gcc -dumpmachine)" \
                --host=aarch64-linux-android \
                --prefix="$DEP_SYSROOT" \
                --disable-shared --enable-static
        make -j"$JOBS"
        make install
    ) || { cat "$b/config.log" 2>/dev/null | tail -60 || true; die "libffi build failed."; }
}

# ---------------------------------------------------------------------------
# 2. libwayland (server + client). A HOST wayland-scanner is built first.
# ---------------------------------------------------------------------------
# When cross-compiling, Meson cannot run the target wayland-scanner that is part
# of the wayland build itself, so it falls back to a *native* wayland-scanner
# (dependency('wayland-scanner', native: true)). We therefore build just the
# wayland-scanner for the HOST natively, then use that same scanner binary when
# building wayland for the target and later when building weston
# (build-libweston.sh expects $DEP_SYSROOT/bin/wayland-scanner).
build_host_wayland_scanner() {
    local d="$SRC_DIR/wayland" hp="$WORK_DIR/host-wayland" hb="$WORK_DIR/host-wayland-build"
    rm -rf "$hb" "$hp"; mkdir -p "$hb"
    log "Building HOST wayland-scanner (native)"
    # This is a HOST (native) build: the cross-build's PKG_CONFIG_SYSROOT_DIR
    # must NOT be applied, or it would re-prefix the host expat include/lib
    # paths with the target sysroot and the build would fail to find expat.h.
    local saved_sysroot="${PKG_CONFIG_SYSROOT_DIR:-unset_sentinel}"
    unset PKG_CONFIG_SYSROOT_DIR
    local rc=0
    meson setup "$hb" "$d" \
        "--prefix=$hp" "--buildtype=release" \
        "-Dlibraries=false" "-Ddocumentation=false" "-Dtests=false" \
        "-Ddtd_validation=false" || rc=1
    if [[ "$saved_sysroot" != "unset_sentinel" ]]; then
        export PKG_CONFIG_SYSROOT_DIR="$saved_sysroot"
    fi
    [[ "$rc" -eq 0 ]] || die "host wayland-scanner meson setup failed."
    ninja -C "$hb" || die "host wayland-scanner build failed."
    DESTDIR= ninja -C "$hb" install || die "host wayland-scanner install failed."
    mkdir -p "$DEP_SYSROOT/bin" "$DEP_SYSROOT/lib/pkgconfig"
    cp -f "$hp/bin/wayland-scanner" "$DEP_SYSROOT/bin/wayland-scanner"
    chmod +x "$DEP_SYSROOT/bin/wayland-scanner"
    # Weston's protocol/meson.build uses dependency('wayland-scanner', native: true)
    # then resolves wayland_scanner from its pkg-config variable. Publish a
    # native wayland-scanner.pc in the sysroot pkg-config path pointing at the
    # HOST scanner binary (target cross-builds cannot run the wayland-scanner).
    local wl_version
    wl_version="$(grep -oE "version: *'[0-9]+\.[0-9]+\.[0-9]+'" "$d/meson.build" 2>/dev/null | head -1 | sed -E "s/.*'([0-9]+\.[0-9]+\.[0-9]+)'.*/\1/")"
    cat > "$DEP_SYSROOT/lib/pkgconfig/wayland-scanner.pc" <<EOF
prefix=$DEP_SYSROOT
exec_prefix=\${prefix}
bindir=\${exec_prefix}/bin
datarootdir=\${prefix}/share
pkgdatadir=\${datarootdir}/wayland
wayland_scanner=\${bindir}/wayland-scanner

Name: Wayland Scanner
Description: Wayland scanner
Version: ${wl_version:-0.0.0}
EOF
    log "HOST wayland-scanner installed to $DEP_SYSROOT/bin/wayland-scanner (+ wayland-scanner.pc)"
}

build_libwayland() {
    log "Building libwayland from InfidelRahul/wayland main"
    fetch_git_clone "wayland" "$WAYLAND_REPO" "$WAYLAND_BRANCH"
    build_host_wayland_scanner
    local d="$SRC_DIR/wayland" b="$WORK_DIR/wayland-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    # `-Dscanner=false` so we do not cross-build a target wayland-scanner (which
    # would need aarch64 expat); Meson instead uses the native wayland-scanner
    # pkg-config we published into the sysroot (`wayland-scanner.pc`).
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Ddocumentation=false" "-Dtests=false" "-Dscanner=false" \
        || die "libwayland meson setup failed."
    ninja -C "$b" || die "libwayland build failed."
    DESTDIR= ninja -C "$b" install || die "libwayland install failed."
}

# ---------------------------------------------------------------------------
# 3. wayland-protocols (data + pkg-config, configure-time)
# ---------------------------------------------------------------------------
build_wayland_protocols() {
    log "Building wayland-protocols from InfidelRahul/wayland-protocols main"
    fetch_git_clone "wayland-protocols" "$WAYLAND_PROTOCOLS_REPO" "$WAYLAND_PROTOCOLS_BRANCH"
    local d="$SRC_DIR/wayland-protocols" b="$WORK_DIR/wp-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" "--buildtype=release" \
        || die "wayland-protocols meson setup failed."
    ninja -C "$b" || die "wayland-protocols build failed."
    DESTDIR= ninja -C "$b" install || die "wayland-protocols install failed."
}

# ---------------------------------------------------------------------------
# 4. pixman
# ---------------------------------------------------------------------------
build_pixman() {
    log "Building pixman from InfidelRahul/pixman main (meson)"
    fetch_git_clone "pixman" "$PIXMAN_REPO" "$PIXMAN_BRANCH"
    local d="$SRC_DIR/pixman" b="$WORK_DIR/pixman-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    # The cross file declares host_machine cpu_family=aarch64, so Meson selects
    # the AArch64/NEON paths and omits x86 SIMD. Shared library (libweston links
    # pixman). Keep it minimal; Meson auto-detects enabled SIMD for the target.
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Dtests=false" "-Ddemos=false" || die "pixman meson setup failed."
    ninja -C "$b" || die "pixman build failed."
    DESTDIR= ninja -C "$b" install || die "pixman install failed."
}

# ---------------------------------------------------------------------------
# 5. xkbcommon
# ---------------------------------------------------------------------------
build_xkbcommon() {
    log "Building xkbcommon ${XKBCOMMON_VERSION}"
    fetch_and_extract "xkbcommon" "$XKBCOMMON_URL" "$XKBCOMMON_SHA256"
    local d="$SRC_DIR/xkbcommon" b="$WORK_DIR/xkbcommon-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Denable-docs=false" "-Denable-tools=false" "-Denable-x11=false" \
        || die "xkbcommon meson setup failed."
    ninja -C "$b" || die "xkbcommon build failed."
    DESTDIR= ninja -C "$b" install || die "xkbcommon install failed."
}

# ---------------------------------------------------------------------------
# 6. libevdev
# ---------------------------------------------------------------------------
build_libevdev() {
    log "Building libevdev ${LIBEVDEV_VERSION}"
    fetch_and_extract "libevdev" "$LIBEVDEV_URL" "$LIBEVDEV_SHA256"
    local d="$SRC_DIR/libevdev" b="$WORK_DIR/libevdev-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Dtests=false" "-Ddocumentation=false" || die "libevdev meson setup failed."
    ninja -C "$b" || die "libevdev build failed."
    DESTDIR= ninja -C "$b" install || die "libevdev install failed."
}

# ---------------------------------------------------------------------------
# 7. libdrm
# ---------------------------------------------------------------------------
build_libdrm() {
    log "Building libdrm ${LIBDRM_VERSION}"
    fetch_and_extract "libdrm" "$LIBDRM_URL" "$LIBDRM_SHA256"
    local d="$SRC_DIR/libdrm" b="$WORK_DIR/libdrm-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Dtests=false" "-Dman-pages=false" "-Dvalgrind=false" \
        || die "libdrm meson setup failed."
    ninja -C "$b" || die "libdrm build failed."
    DESTDIR= ninja -C "$b" install || die "libdrm install failed."
}

# ---------------------------------------------------------------------------
# 8. libinput (configure-time only for libweston)
# ---------------------------------------------------------------------------
build_libinput() {
    log "Building libinput ${LIBINPUT_VERSION}"
    fetch_and_extract "libinput" "$LIBINPUT_URL" "$LIBINPUT_SHA256"
    local d="$SRC_DIR/libinput" b="$WORK_DIR/libinput-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    # libinput wants a udev/session backend; for the Android target we build the
    # core library + headers only (no udev/libseat) to satisfy libweston's
    # configure-time pkg-config dependency. libweston.so does NOT link libinput.
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Dtests=false" "-Ddocumentation=false" "-Ddebug-gui=false" \
        "-Devents=false" "-Dlibudev=false" "-Dlibseat=false" \
        || die "libinput meson setup failed."
    ninja -C "$b" || die "libinput build failed."
    DESTDIR= ninja -C "$b" install || die "libinput install failed."
}

# ---------------------------------------------------------------------------
# 9. libdisplay-info (configure-time only for libweston)
# ---------------------------------------------------------------------------
build_libdisplay_info() {
    log "Building libdisplay-info ${LIBDISPLAY_INFO_VERSION}"
    fetch_and_extract "libdisplay-info" "$LIBDISPLAY_INFO_URL" "$LIBDISPLAY_INFO_SHA256"
    local d="$SRC_DIR/libdisplay-info" b="$WORK_DIR/libdisplay-info-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        || die "libdisplay-info meson setup failed."
    ninja -C "$b" || die "libdisplay-info build failed."
    DESTDIR= ninja -C "$b" install || die "libdisplay-info install failed."
}

# ---------------------------------------------------------------------------
# 11. xkeyboard-config (XKB layout database; architecture-independent data)
# ---------------------------------------------------------------------------
build_xkbconfig() {
    log "Installing xkeyboard-config ${XKBCONFIG_VERSION} (data only)"
    fetch_and_extract "xkeyboard-config" "$XKBCONFIG_URL" "$XKBCONFIG_SHA256"
    local d="$SRC_DIR/xkeyboard-config" b="$WORK_DIR/xkbc-build"
    rm -rf "$b"; mkdir -p "$b"
    # Recent xkeyboard-config uses Meson: install the XKB data tree into DEP_SYSROOT
    # ($DEP_SYSROOT/share/X11/xkb) so libxkbcommon can find it at runtime via the
    # standard Linux XKB data path — no Android host path is hardcoded.
    if [[ -f "$d/meson.build" ]]; then
        local cross
        cross="$(meson_cross_file)"
        meson_setup "$b" "$d" \
            "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
            "--buildtype=release" \
            || die "xkeyboard-config meson setup failed."
        ninja -C "$b" || die "xkeyboard-config build failed."
        DESTDIR= ninja -C "$b" install || die "xkeyboard-config install failed."
    else
        # Fall back to autotools for older releases.
        (
            cd "$b"
            env CC="$CC" CFLAGS="-fPIE -fPIC -DANDROID -D__ANDROID__" \
                LDFLAGS="-fuse-ld=lld" \
                "$d/configure" \
                    --build="$(gcc -dumpmachine)" \
                    --host=aarch64-linux-android \
                    --prefix="$DEP_SYSROOT"
            make -j"$JOBS"
            make install
        ) || { cat "$b/config.log" 2>/dev/null | tail -60 || true; die "xkeyboard-config build failed."; }
    fi
    log "xkeyboard-config XKB data installed to $DEP_SYSROOT/share/X11/xkb"
}

# ---------------------------------------------------------------------------
# 12. zlib (needed by libpng and cairo)
# ---------------------------------------------------------------------------
build_zlib() {
    log "Building zlib ${ZLIB_VERSION}"
    fetch_and_extract "zlib" "$ZLIB_URL" "$ZLIB_SHA256"
    local d="$SRC_DIR/zlib" b="$WORK_DIR/zlib-build"
    rm -rf "$b"; mkdir -p "$b"
    (
        cd "$b"
        # zlib's hand-rolled configure supports cross via CHOST and only
        # COMPILES its conftest (never runs the target binary), so it is safe to
        # cross-compile with the NDK toolchain. It honors explicit AR/CC so we
        # pin the NDK tools and lld linker.
        env CHOST=aarch64-linux-android \
            CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="-fPIE -fPIC -DANDROID -D__ANDROID__" \
            LDFLAGS="-fuse-ld=lld" \
            "$d/configure" --prefix="$DEP_SYSROOT" --shared
        make -j"$JOBS"
        make install
    ) || { cat "$b/configure.log" 2>/dev/null | tail -60 || true; die "zlib build failed."; }
}

# ---------------------------------------------------------------------------
# 11. libpng (needed by cairo's image backend)
# ---------------------------------------------------------------------------
build_libpng() {
    log "Building libpng ${LIBPNG_VERSION}"
    fetch_and_extract "libpng" "$LIBPNG_URL" "$LIBPNG_SHA256"
    local d="$SRC_DIR/libpng" b="$WORK_DIR/libpng-build"
    rm -rf "$b"; mkdir -p "$b"
    (
        cd "$b"
        env CC="$CC" \
            CFLAGS="-fPIE -fPIC -DANDROID -D__ANDROID__" \
            LDFLAGS="-L$DEP_SYSROOT/lib -fuse-ld=lld" \
            CPPFLAGS="-I$DEP_SYSROOT/include" \
            ZLIB_CFLAGS="-I$DEP_SYSROOT/include" \
            ZLIB_LIBS="-L$DEP_SYSROOT/lib -lz" \
            "$d/configure" \
                --build="$(gcc -dumpmachine)" \
                --host=aarch64-linux-android \
                --prefix="$DEP_SYSROOT" \
                --enable-shared --disable-static \
                --disable-dependency-tracking
        make -j"$JOBS"
        make install
    ) || { cat "$b/config.log" 2>/dev/null | tail -60 || true; die "libpng build failed."; }
    # Cairo's meson looks up pkg-config module 'libpng'; libpng may install only
    # libpng16.pc, so also publish a compat libpng.pc when one is missing.
    if [[ -f "$DEP_SYSROOT/lib/pkgconfig/libpng16.pc" && ! -f "$DEP_SYSROOT/lib/pkgconfig/libpng.pc" ]]; then
        cp "$DEP_SYSROOT/lib/pkgconfig/libpng16.pc" "$DEP_SYSROOT/lib/pkgconfig/libpng.pc"
        log "Published compat pkg-config: libpng.pc"
    fi
}

# ---------------------------------------------------------------------------
# 12. cairo (image backend only; build dependency of Weston's headless backend)
# ---------------------------------------------------------------------------
build_cairo() {
    log "Building cairo ${CAIRO_VERSION} (image backend, freetype/fontconfig/glib disabled)"
    fetch_and_extract "cairo" "$CAIRO_URL" "$CAIRO_SHA256"
    local d="$SRC_DIR/cairo" b="$WORK_DIR/cairo-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    # Minimal image-surface backend. Disable everything not needed so we do NOT
    # drag in freetype, fontconfig, glib, x11/wayland/xcb, pdf/ps/svg/script.
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Dpng=enabled" "-Dzlib=enabled" \
        "-Dfreetype=disabled" "-Dfontconfig=disabled" "-Dglib=disabled" \
        "-Dcairo-gobject=disabled" \
        "-Dxlib=disabled" "-Dxcb=disabled" "-Dquartz=disabled" \
        "-Dwin32=disabled" "-Ddwrite=disabled" \
        "-Dcairo-script=disabled" "-Dcairo-trace=disabled" \
        "-Dcairo-pdf=disabled" "-Dcairo-ps=disabled" "-Dcairo-svg=disabled" \
        "-Dcairo-te=disabled" "-Dcairo-glesv2=disabled" "-Dcairo-glesv3=disabled" \
        "-Dtests=disabled" "-Dbenchmarks=disabled" \
        || die "cairo meson setup failed."
    ninja -C "$b" || die "cairo build failed."
    DESTDIR= ninja -C "$b" install || die "cairo install failed."
}

log "Starting libweston dependency bootstrap (arm64-v8a / API $API)."
build_libffi
build_libwayland
build_wayland_protocols
build_pixman
build_zlib
build_libpng
build_cairo
build_xkbcommon
build_xkbconfig
build_libevdev
build_libdrm
build_libinput
build_libdisplay_info

# ---------------------------------------------------------------------------
# Final verification: every produced shared library must be AArch64 ELF; reject
# x86 / x86_64 / arm32. Also surface the resolved mirror commits. This guards
# against an accidental host-architecture fallback (must fail CI).
# ---------------------------------------------------------------------------
verify_sysroot_arch() {
    log "Verifying produced libraries are AArch64 ELF..."
    local f count=0
    local -a bad=()
    while IFS= read -r -d '' f; do
        count=$((count+1))
        local magic class b0 b1 em
        magic="$(od -An -tx1 -N1 "$f" | tr -d ' \n')"
        # ELF magic = 7f 45 4c 46
        if [[ "$magic" != "7f" ]]; then
            bad+=("NOT-ELF: $f"); continue
        fi
        class="$(od -An -tx1 -j4 -N1 "$f" | tr -d ' \n')"   # 01=32-bit, 02=64-bit
        # e_machine is bytes 18..19 little-endian; EM_AARCH64 = 183 (0xB7).
        b0="$(od -An -tx1 -j18 -N1 "$f" | tr -d ' ')"
        b1="$(od -An -tx1 -j19 -N1 "$f" | tr -d ' ')"
        em="$(( 0x$b1 * 256 + 0x$b0 ))"
        if [[ "$class" != "02" ]]; then
            bad+=("NOT-64BIT: $f")
        elif [[ "$em" -ne 183 ]]; then
            bad+=("WRONG-ARCH($em): $f")
        fi
    done < <(find "$DEP_SYSROOT/lib" -type f \( -name '*.so' -o -name '*.so.*' \) -print0 2>/dev/null)
    if [[ "$count" -eq 0 ]]; then
        die "No shared libraries found under $DEP_SYSROOT/lib — nothing was built (missing dependency build)."
    fi
    if [[ "${#bad[@]}" -gt 0 ]]; then
        printf '[weston-deps] ERROR: broker-produced libraries with wrong architecture:\n' >&2
        printf '  %s\n' "${bad[@]}" >&2
        exit 1
    fi
    log "All produced shared libraries are AArch64 ELF. ✓"
}

verify_sysroot_arch

if [[ -d "$DEP_SYSROOT/.git-commits" ]]; then
    log "Resolved mirror commits:"
    while IFS= read -r c; do
        name="$(basename "$c")"
        resolved="$(cat "$c")"
        log "  $name -> $resolved"
    done < <(find "$DEP_SYSROOT/.git-commits" -type f 2>/dev/null | sort)
fi
if [[ ! -x "$DEP_SYSROOT/bin/wayland-scanner" ]]; then
    die "HOST wayland-scanner missing at $DEP_SYSROOT/bin/wayland-scanner."
fi

log "Dependency sysroot installed to $DEP_SYSROOT."
log "For build-libweston.sh: DEP_SYSROOT=$DEP_SYSROOT DEP_PKG_CONFIG_PATH=$DEP_SYSROOT/lib/pkgconfig:$DEP_SYSROOT/share/pkgconfig"
