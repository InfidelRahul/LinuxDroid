#!/usr/bin/env bash
#
# LinuxDroid — Build libweston (16.0.0) for Android arm64-v8a / API 36+.
#
# This is the Milestone 1 native dependency build. It produces libweston from
# the SAME pinned Weston source (native/weston/src), never from a distro
# package and never from a different Weston/libweston release.
#
# Prerequisites (host):
#   - Android NDK r29 (29.0.14206865)  -> ANDROID_NDK_ROOT (or ANDROID_NDK_HOME)
#   - Meson and Ninja on PATH (upstream Weston build system; see README.md)
#   - The cross-built dependency sysroot produced by bootstrap-deps.sh, provided
#     via DEP_SYSROOT and DEP_PKG_CONFIG_PATH (see below).
#
# Usage:
#   ANDROID_NDK_ROOT=/opt/ndk \
#   DEP_SYSROOT=/path/to/arm64-36-sysroot \
#   DEP_PKG_CONFIG_PATH=/path/to/sysroot/share/pkgconfig \
#   native/weston/build-libweston.sh
#
# Output:
#   native/weston/build/                   (Meson build dir)
#   native/weston/dist/                    (installed libweston artifacts)
#
# The build is isolated from the PRoot/CLI runtime — it neither links nor
# modifies the existing runtime, and only the libweston dependency is produced.
#
# NOTE: Weston 16.0.0's top-level meson.build unconditionally requires
# wayland-server, wayland-client, pixman-1, xkbcommon, libinput, libevdev,
# libdrm and libdisplay-info via pkg-config (no option disables them), plus the
# host wayland-scanner and wayland-protocols. bootstrap-deps.sh builds all of
# these for the target; this script builds libweston against that sysroot.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
DIST_DIR="$SCRIPT_DIR/dist"
CROSS_TEMPLATE="$SCRIPT_DIR/meson-cross-android-arm64.ini.in"

API=36
HOST_TAG="linux-x86_64"

# Resolve the NDK.
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_ROOT" ]]; then
    echo "[libweston] ERROR: ANDROID_NDK_ROOT / ANDROID_NDK_HOME not set." >&2
    exit 1
fi
if [[ ! -d "$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin" ]]; then
    echo "[libweston] ERROR: NDK toolchain not found at $NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin." >&2
    exit 1
fi

command -v meson >/dev/null 2>&1 || { echo "[libweston] ERROR: meson not found on PATH." >&2; exit 1; }
command -v ninja >/dev/null 2>&1 || { echo "[libweston] ERROR: ninja not found on PATH." >&2; exit 1; }

# The pinned source is required.
if [[ ! -f "$SRC_DIR/meson.build" ]]; then
    echo "[libweston] ERROR: pinned Weston source not present at $SRC_DIR." >&2
    echo "[libweston] Run native/weston/fetch-weston.sh to acquire and verify the 16.0.0 source." >&2
    exit 1
fi

# The cross-built dependency sysroot is required (Weston 16 cannot configure
# without wayland-server/client/pixman/xkbcommon/libinput/libevdev/libdrm/
# libdisplay-info).
DEP_SYSROOT="${DEP_SYSROOT:-$SCRIPT_DIR/deps/sysroot}"
DEP_PKG_CONFIG_PATH="${DEP_PKG_CONFIG_PATH:-$DEP_SYSROOT/share/pkgconfig}"
if [[ ! -d "$DEP_PKG_CONFIG_PATH" ]]; then
    echo "[libweston] ERROR: dependency sysroot pkg-config dir not found at $DEP_PKG_CONFIG_PATH." >&2
    echo "[libweston] Run native/weston/bootstrap-deps.sh first." >&2
    exit 1
fi

# The host wayland-scanner lives in the dependency sysroot bin dir. Wayland's
# Meson build installs it regardless of target.
HOST_BINDIR="$DEP_SYSROOT/bin"
if [[ ! -x "$HOST_BINDIR/wayland-scanner" ]]; then
    echo "[libweston] ERROR: host wayland-scanner not found at $HOST_BINDIR/wayland-scanner." >&2
    echo "[libweston] bootstrap-deps.sh must be run before build-libweston.sh." >&2
    exit 1
fi
echo "[libweston] Using host wayland-scanner: $HOST_BINDIR/wayland-scanner"

# --- Generate the Meson cross file -------------------------------------------
CROSS_FILE="$BUILD_DIR/meson-cross-android-arm64.ini"
mkdir -p "$BUILD_DIR"
sed \
    -e "s|@NDK@|$NDK_ROOT|g" \
    -e "s|@API@|$API|g" \
    -e "s|@HOST_TAG@|$HOST_TAG|g" \
    -e "s|@HOST_BINDIR@|$HOST_BINDIR|g" \
    "$CROSS_TEMPLATE" > "$CROSS_FILE"
echo "[libweston] Generated cross file: $CROSS_FILE"

# --- Configure libweston with the MINIMUM dependency set -----------------------
# Weston 16.0.0 meson options. Some options used by earlier versions are
# gone/renamed in 16.0.0; the set below is the valid minimal configuration:
#   - No desktop shell / DRM / X11 / Wayland / RDP / PipeWire / VNC backends.
#   - Renderer bring-up is Pixman only (renderer-gl / renderer-vulkan disabled).
#   - demo-clients must be disabled and simple-clients set to a valid member
#     (it is an ARRAY option in 16.0.0, so `false` is rejected).
#   - backend-default must be a backend we actually build (headless) or meson
#     aborts ("Backend ... was chosen as native but is not being built").
MESON_OPTS=(
    "--cross-file=$CROSS_FILE"
    "--prefix=$DIST_DIR"
    "--buildtype=release"
    "--default-library=shared"
    "-Dbackend-drm=false"
    "-Dbackend-rdp=false"
    "-Dbackend-x11=false"
    "-Dbackend-wayland=false"
    "-Dbackend-pipewire=false"
    "-Dbackend-vnc=false"
    "-Dbackend-default=headless"
    "-Dxwayland=false"
    "-Drenderer-gl=false"
    "-Drenderer-vulkan=false"
    "-Dshell-desktop=false"
    "-Dshell-ivi=false"
    "-Dshell-kiosk=false"
    "-Dshell-lua=false"
    "-Dsystemd=false"
    "-Dcolor-management-lcms=false"
    "-Dimage-jpeg=false"
    "-Dimage-webp=false"
    "-Ddemo-clients=false"
    "-Dsimple-clients=shm"
    "-Dtests=false"
    "-Ddoc=false"
)

# Provide the cross-built dependency sysroot to pkg-config.
export PKG_CONFIG_SYSROOT_DIR="$DEP_SYSROOT"
export PKG_CONFIG_PATH="$DEP_PKG_CONFIG_PATH:${PKG_CONFIG_PATH:-}"
export PKG_CONFIG_LIBDIR="$DEP_PKG_CONFIG_PATH"

echo "[libweston] Configuring libweston (version 16.0.0) for arm64-v8a / API $API"
meson setup "$BUILD_DIR/weston-build" "$SRC_DIR" "${MESON_OPTS[@]}"

echo "[libweston] Compiling libweston"
ninja -C "$BUILD_DIR/weston-build"

echo "[libweston] Installing libweston into $DIST_DIR"
ninja -C "$BUILD_DIR/weston-build" install

# --- Install the private internal-backend header --------------------------------
# linuxdroid_backend.c embeds `struct weston_backend`, whose full definition is
# in the PRIVATE libweston/backend.h (meson does NOT install it). Copy it beside
# the installed public headers so the bridge CMake can find it at
# <dist>/include/libweston-16/libweston/backend.h.
INSTALL_INC=""
for cand in "$DIST_DIR/include/libweston-16" "$DIST_DIR/include/libweston"; do
    if [[ -d "$cand/libweston" ]]; then
        INSTALL_INC="$cand"
        break
    fi
done
if [[ -z "$INSTALL_INC" ]]; then
    echo "[libweston] ERROR: could not locate installed libweston include dir under $DIST_DIR." >&2
    exit 1
fi
if [[ -f "$SRC_DIR/libweston/backend.h" ]]; then
    cp "$SRC_DIR/libweston/backend.h" "$INSTALL_INC/libweston/backend.h"
    echo "[libweston] Installed private header: $INSTALL_INC/libweston/backend.h"
else
    echo "[libweston] ERROR: private header libweston/backend.h not found in pinned source." >&2
    exit 1
fi

# --- Stage the wayland-provided headers + library into dist ------------------
# The LinuxDroid bridge CMake (native/bridge/.../CMakeLists.txt) detects the
# libweston install under native/weston/dist and also looks for
# wayland-server.h + libwayland-server there (it links wl_display_* directly).
# Those come from the dependency sysroot, not from the libweston install, so we
# copy them into dist so the bridge finds everything in one place.
mkdir -p "$DIST_DIR/include" "$DIST_DIR/lib"
if [[ -d "$DEP_SYSROOT/include" ]]; then
    # wayland-server.h, wayland-util.h, wayland-version.h, etc.
    for h in "$DEP_SYSROOT"/include/wayland-server.h \
             "$DEP_SYSROOT"/include/wayland-server-core.h \
             "$DEP_SYSROOT"/include/wayland-util.h \
             "$DEP_SYSROOT"/include/wayland-version.h \
             "$DEP_SYSROOT"/include/wayland-client.h; do
        if [[ -f "$h" ]]; then
            cp "$h" "$DIST_DIR/include/"
            echo "[libweston] Staged wayland header: $(basename "$h")"
        fi
    done
    # pixman.h (pulled in by libweston/libweston.h).
    if [[ -f "$DEP_SYSROOT/include/pixman-1/pixman.h" ]]; then
        mkdir -p "$DIST_DIR/include/pixman-1"
        cp "$DEP_SYSROOT/include/pixman-1/pixman.h" "$DIST_DIR/include/pixman-1/pixman.h"
        echo "[libweston] Staged pixman header: pixman-1/pixman.h"
    elif [[ -f "$DEP_SYSROOT/include/pixman.h" ]]; then
        cp "$DEP_SYSROOT/include/pixman.h" "$DIST_DIR/include/pixman.h"
        echo "[libweston] Staged pixman header: pixman.h"
    fi
    # xkbcommon headers (pulled in by libweston/libweston.h).
    if [[ -d "$DEP_SYSROOT/include/xkbcommon" ]]; then
        cp -a "$DEP_SYSROOT/include/xkbcommon" "$DIST_DIR/include/"
        echo "[libweston] Staged xkbcommon headers."
    fi
fi
# libwayland-server shared library.
if [[ -n "$(find "$DEP_SYSROOT/lib" -name 'libwayland-server.so*' 2>/dev/null | head -1)" ]]; then
    cp -a "$DEP_SYSROOT"/lib/libwayland-server.so* "$DIST_DIR/lib/" 2>/dev/null || true
    echo "[libweston] Staged libwayland-server into distrib lib/"
fi
if [[ -n "$(find "$DEP_SYSROOT/lib" -name 'libwayland-client.so*' 2>/dev/null | head -1)" ]]; then
    cp -a "$DEP_SYSROOT"/lib/libwayland-client.so* "$DIST_DIR/lib/" 2>/dev/null || true
fi

# --- Deterministic verification --------------------------------------------------
# Confirms the built dependency matches the frozen pins (version + commit),
# reading from the actual source tree.
echo "[libweston] Verifying built dependency version/commit"
"$SCRIPT_DIR/verify-weston.sh" --strict-source

echo "[libweston] libweston 16.0.0 built for arm64-v8a / API 36+."
echo "[libweston] Artifacts: $DIST_DIR"
