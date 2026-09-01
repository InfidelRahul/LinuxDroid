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
#   - Cross-built dependency sysroot for the arm64-v8a / API 36 target containing:
#       * libwayland-server (+ headers, pkg-config: wayland-server)
#       * wayland-protocols (wayland.xml, stable/*, staging/*)
#       * pixman (+ headers, pkg-config: pixman-1)
#     Provide via DEP_SYSROOT and DEP_PKG_CONFIG_PATH (see below).
#
# Usage:
#   ANDROID_NDK_ROOT=/opt/ndk \
#   DEP_SYSROOT=/path/to/arm64-36-sysroot \
#   DEP_PKG_CONFIG_PATH=/path/to/sysroot/pkgconfig \
#   native/weston/build-libweston.sh
#
# Output:
#   native/weston/build/libweston-16.0.0/  (Meson build dir + install prefix)
#   native/weston/dist/                    (installed libweston artifacts)
#
# The build is isolated from the PRoot/CLI runtime — it neither links nor
# modifies the existing runtime, and only the libweston dependency is produced.

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

# --- Generate the Meson cross file -------------------------------------------
CROSS_FILE="$BUILD_DIR/meson-cross-android-arm64.ini"
mkdir -p "$BUILD_DIR"
sed \
    -e "s|@NDK@|$NDK_ROOT|g" \
    -e "s|@API@|$API|g" \
    -e "s|@HOST_TAG@|$HOST_TAG|g" \
    "$CROSS_TEMPLATE" > "$CROSS_FILE"
echo "[libweston] Generated cross file: $CROSS_FILE"

# --- Configure libweston with the MINIMUM dependency set -----------------------
# Renderer strategy for bring-up is Pixman. The desktop shell, DRM, X11,
# XWayland, RDP, PipeWire, and the GL renderer are explicitly disabled.
# The custom Android backend is NOT built in Milestone 1.
MESON_OPTS=(
    "--cross-file=$CROSS_FILE"
    "--prefix=$DIST_DIR"
    "--buildtype=release"
    "-Dbackend-drm=false"
    "-Dbackend-rdp=false"
    "-Dbackend-x11=false"
    "-Dbackend-wayland=false"
    "-Dxwayland=false"
    "-Dremoting=false"
    "-Dpipewire=false"
    "-Drenderer-gl=false"
    "-Dlibunwind=false"
    "-Dtests=false"
    "-Ddemo-clients=false"
    "-Dsimple-clients=false"
    "-Dimage-io=false"
    "-Dcolormanagement=false"
)

# Allow an external dependency set if provided (not bundled / not distro).
if [[ -n "${DEP_SYSROOT:-}" ]]; then
    MESON_OPTS+=("--default-library=shared")
    export PKG_CONFIG_SYSROOT_DIR="$DEP_SYSROOT"
    export PKG_CONFIG_PATH="$DEP_PKG_CONFIG_PATH:${PKG_CONFIG_PATH:-}"
    export PKG_CONFIG_LIBDIR="$DEP_PKG_CONFIG_PATH"
fi

echo "[libweston] Configuring libweston (version 16.0.0) for arm64-v8a / API $API"
meson setup "$BUILD_DIR/weston-build" "$SRC_DIR" "${MESON_OPTS[@]}"

echo "[libweston] Compiling libweston"
ninja -C "$BUILD_DIR/weston-build"

echo "[libweston] Installing libweston into $DIST_DIR"
ninja -C "$BUILD_DIR/weston-build" install

# --- Deterministic verification --------------------------------------------------
# Confirms the built dependency matches the frozen pins (version + commit),
# reading from the actual source tree.
echo "[libweston] Verifying built dependency version/commit"
"$SCRIPT_DIR/verify-weston.sh" --strict-source

echo "[libweston] libweston 16.0.0 built for arm64-v8a / API 36+."
echo "[libweston] Artifacts: $DIST_DIR"
