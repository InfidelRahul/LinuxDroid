#!/usr/bin/env bash
#
# LinuxDroid — Bootstrap the cross-built dependencies required by libweston.
#
# Weston 16.0.0 (see native/weston/src/meson.build) requires the following at
# *configure* time (all as pkg-config dependencies, no option can disable them):
#
#     wayland-server >= 1.22   wayland-client >= 1.22   pixman-1 >= 0.25.2
#     xkbcommon >= 0.5         libinput >= 1.2          libevdev
#     libdrm >= 2.4.108        libdisplay-info (>=0.2,<0.4)     wayland-protocols >= 1.46
#     wayland-scanner (host tool + pkg-config, native: true)
#
# Of these, the libweston shared library that the LinuxDroid bridge links
# against only actually depends on:
#
#     wayland-server, pixman-1, libdrm, xkbcommon      (+ libm/libdl from the NDK)
#
# The rest are consumed at configure time only; they are still cross-built and
# installed here so the Meson configure of libweston succeeds and stays
# deterministic — no distro package is ever used as a substitute.
#
# This script cross-compiles everything for Android arm64-v8a / API 36 using the
# configured NDK, and installs all headers + libraries + pkg-config files into a
# single DEP_SYSROOT that build-libweston.sh consumes via DEP_PKG_CONFIG_PATH.
# Sources are pinned upstream releases; tarball SHA-256 is verified when set.
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

log() { printf '[weston-deps] %s\n' "$*"; }
die() { printf '[weston-deps] ERROR: %s\n' "$*" >&2; exit 1; }

mkdir -p "$DEP_SYSROOT/lib" "$DEP_SYSROOT/include" "$DEP_SYSROOT/bin" "$DEP_SYSROOT/share/pkgconfig"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" "$SRC_DIR"

export PKG_CONFIG_SYSROOT_DIR="$DEP_SYSROOT"
export PKG_CONFIG_PATH="$DEP_SYSROOT/share/pkgconfig"

# ---------------------------------------------------------------------------
# Deterministic upstream sources (pinned; floating tags are NOT used).
# ---------------------------------------------------------------------------
FFI_VERSION="3.4.6"
FFI_URL="https://github.com/libffi/libffi/releases/download/v${FFI_VERSION}/libffi-${FFI_VERSION}.tar.gz"
FFI_SHA256=""

WAYLAND_VERSION="1.22.0"
WAYLAND_URL="https://gitlab.freedesktop.org/wayland/wayland/-/archive/${WAYLAND_VERSION}/wayland-${WAYLAND_VERSION}.tar.gz"
WAYLAND_SHA256=""

WAYLAND_PROTOCOLS_VERSION="1.36"
WAYLAND_PROTOCOLS_URL="https://gitlab.freedesktop.org/wayland/wayland-protocols/-/archive/${WAYLAND_PROTOCOLS_VERSION}/wayland-protocols-${WAYLAND_PROTOCOLS_VERSION}.tar.gz"
WAYLAND_PROTOCOLS_SHA256=""

PIXMAN_VERSION="0.42.2"
PIXMAN_URL="https://gitlab.freedesktop.org/pixman/pixman/-/archive/${PIXMAN_VERSION}/pixman-${PIXMAN_VERSION}.tar.gz"
PIXMAN_SHA256=""

XKBCOMMON_VERSION="1.6.0"
XKBCOMMON_URL="https://github.com/xkbcommon/libxkbcommon/releases/download/xkbcommon-${XKBCOMMON_VERSION}/xkbcommon-xkbcommon-${XKBCOMMON_VERSION}.tar.xz"
XKBCOMMON_SHA256=""

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
        env CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
            CFLAGS="-fPIC -DANDROID -D__ANDROID__" \
            "$d/configure" --host=aarch64-linux-android \
                --prefix="$DEP_SYSROOT" --disable-shared --enable-static
        make -j"$JOBS"
        make install
    ) || die "libffi build failed."
}

# ---------------------------------------------------------------------------
# 2. libwayland (server + client + scanner)
# ---------------------------------------------------------------------------
build_libwayland() {
    log "Building libwayland ${WAYLAND_VERSION}"
    fetch_and_extract "libwayland" "$WAYLAND_URL" "$WAYLAND_SHA256"
    local d="$SRC_DIR/libwayland" b="$WORK_DIR/wayland-build"
    local cross
    cross="$(meson_cross_file)"
    rm -rf "$b"; mkdir -p "$b"
    meson_setup "$b" "$d" \
        "--cross-file=$cross" "--prefix=$DEP_SYSROOT" \
        "--buildtype=release" "--default-library=shared" \
        "-Ddocumentation=false" "-Dtests=false" || die "libwayland meson setup failed."
    ninja -C "$b" || die "libwayland build failed."
    DESTDIR= ninja -C "$b" install || die "libwayland install failed."
}

# ---------------------------------------------------------------------------
# 3. wayland-protocols (data + pkg-config, configure-time)
# ---------------------------------------------------------------------------
build_wayland_protocols() {
    log "Building wayland-protocols ${WAYLAND_PROTOCOLS_VERSION}"
    fetch_and_extract "wayland-protocols" "$WAYLAND_PROTOCOLS_URL" "$WAYLAND_PROTOCOLS_SHA256"
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
    log "Building pixman ${PIXMAN_VERSION}"
    fetch_and_extract "pixman" "$PIXMAN_URL" "$PIXMAN_SHA256"
    local d="$SRC_DIR/pixman" b="$WORK_DIR/pixman-build"
    rm -rf "$b"; mkdir -p "$b"
    (
        cd "$b"
        env CC="$CC" CFLAGS="-fPIC -DANDROID -D__ANDROID__" \
            "$d/configure" --host=aarch64-linux-android \
                --prefix="$DEP_SYSROOT" --disable-shared --enable-static \
                --disable-dependency-tracking
        make -j"$JOBS"
        make install
    ) || die "pixman build failed."
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
        "-Denable-docs=false" "-Denable-tools=false" || die "xkbcommon meson setup failed."
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

log "Starting libweston dependency bootstrap (arm64-v8a / API $API)."
build_libffi
build_libwayland
build_wayland_protocols
build_pixman
build_xkbcommon
build_libevdev
build_libdrm
build_libinput
build_libdisplay_info

log "Dependency sysroot installed to $DEP_SYSROOT."
log "For build-libweston.sh: DEP_SYSROOT=$DEP_SYSROOT DEP_PKG_CONFIG_PATH=$DEP_SYSROOT/share/pkgconfig"
