#!/usr/bin/env bash
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

PREFIX="$SCRIPT_DIR/prefix"
BUILD_DIR="$SCRIPT_DIR/build"
SRC_DIR="$SCRIPT_DIR/src"
HOST_TOOLS_DIR="$SCRIPT_DIR/host_tools"
MANIFEST_FILE="$SCRIPT_DIR/dependencies.json"

info() { echo "[$SCRIPT_NAME] $*"; }
die() { echo "[$SCRIPT_NAME] ERROR: $*" >&2; exit 1; }

[[ -f "$MANIFEST_FILE" ]] || die "Manifest file not found: $MANIFEST_FILE"

# 1. Locate NDK
NDK_ROOT="${ANDROID_NDK_ROOT:-${NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
    for candidate in \
        "/home/codespace/Android/Sdk/ndk/29.0.14206865" \
        "${ANDROID_HOME:-/nonexistent}/ndk/29.0.14206865" \
        "$HOME/Android/Sdk/ndk/29.0.14206865" \
        $(ls -d "${ANDROID_HOME:-/nonexistent}/ndk/"* 2>/dev/null | tail -n1) \
        $(ls -d "$HOME/Android/Sdk/ndk/"* 2>/dev/null | tail -n1)
    do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            NDK_ROOT="$candidate"
            break
        fi
    done
fi

[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" ]] || die "Android NDK not found. Please set ANDROID_NDK_ROOT or NDK_ROOT."
TOOLCHAIN_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$TOOLCHAIN_BIN/aarch64-linux-android35-clang" ]] || die "NDK Clang toolchain not found in $TOOLCHAIN_BIN"
info "Using Android NDK: $NDK_ROOT"

# 2. Check build tools
for tool in git python3 bison flex pkg-config; do
    command -v "$tool" >/dev/null 2>&1 || die "Missing host tool: $tool"
done

if ! command -v meson >/dev/null 2>&1 || ! command -v ninja >/dev/null 2>&1; then
    info "Installing meson and ninja via pip..."
    python3 -m pip install --upgrade meson ninja >/dev/null 2>&1 || true
fi
command -v meson >/dev/null 2>&1 || die "Meson build system not found."
command -v ninja >/dev/null 2>&1 || die "Ninja build system not found."

mkdir -p "$PREFIX" "$BUILD_DIR" "$SRC_DIR" "$HOST_TOOLS_DIR"

# 3. Create Meson cross toolchain file
CROSS_FILE="$BUILD_DIR/android-arm64.ini"
cat << EOF > "$CROSS_FILE"
[binaries]
c = '$TOOLCHAIN_BIN/aarch64-linux-android35-clang'
cpp = '$TOOLCHAIN_BIN/aarch64-linux-android35-clang++'
ar = '$TOOLCHAIN_BIN/llvm-ar'
strip = '$TOOLCHAIN_BIN/llvm-strip'
pkg-config = 'pkg-config'
wayland-scanner = '$HOST_TOOLS_DIR/bin/wayland-scanner'

[built-in options]
c_args = ['-DANDROID', '-D__ANDROID_API__=35', '-O3', '-fPIC']
cpp_args = ['-DANDROID', '-D__ANDROID_API__=35', '-O3', '-fPIC']

[properties]
pkg_config_libdir = ['$PREFIX/lib/pkgconfig', '$PREFIX/share/pkgconfig']
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
EOF

export PKG_CONFIG_PATH="$HOST_TOOLS_DIR/lib/x86_64-linux-gnu/pkgconfig:${PKG_CONFIG_PATH:-}"
export PKG_CONFIG_PATH_FOR_BUILD="$HOST_TOOLS_DIR/lib/x86_64-linux-gnu/pkgconfig:${PKG_CONFIG_PATH_FOR_BUILD:-}"
export PATH="$HOST_TOOLS_DIR/bin:$PATH"

fetch_repo() {
    local name="$1"
    local url="$2"
    local commit="$3"
    local target_dir="$SRC_DIR/$name"

    if [[ ! -d "$target_dir/.git" ]]; then
        info "Cloning $name from $url..."
        git clone "$url" "$target_dir"
    fi
    info "Verifying $name checkout ($commit)..."
    git -C "$target_dir" fetch --tags origin
    git -C "$target_dir" checkout -f "$commit"
    local actual_commit
    actual_commit="$(git -C "$target_dir" rev-parse HEAD)"
    [[ "$actual_commit" == "$commit"* ]] || die "$name commit mismatch: expected $commit, got $actual_commit"
}

# --- Step A: Wayland & Host Scanner ---
WAYLAND_COMMIT="87cc8a8728a923fc57938faa81ba0e74f34ecdc7"
fetch_repo "wayland" "https://github.com/InfidelRahul/wayland.git" "$WAYLAND_COMMIT"

info "Building host wayland-scanner..."
rm -rf "$SRC_DIR/wayland/build-host"
meson setup "$SRC_DIR/wayland/build-host" "$SRC_DIR/wayland" \
    --prefix "$HOST_TOOLS_DIR" \
    -Dscanner=true -Dlibraries=false -Ddtd_validation=false -Ddocumentation=false -Dtests=false
ninja -C "$SRC_DIR/wayland/build-host" install

[[ -x "$HOST_TOOLS_DIR/bin/wayland-scanner" ]] || die "Failed to build host wayland-scanner"

# --- Step B: Libffi ---
info "Preparing libffi..."
LIBFFI_DIR="$SRC_DIR/libffi"
if [[ ! -d "$LIBFFI_DIR" ]]; then
    mkdir -p "$LIBFFI_DIR"
    curl -sL https://github.com/libffi/libffi/releases/download/v3.8.0/libffi-3.8.0.tar.gz -o "$SRC_DIR/libffi.tar.gz"
    echo "7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4  $SRC_DIR/libffi.tar.gz" | sha256sum -c - || die "libffi checksum failed"
    tar -xzf "$SRC_DIR/libffi.tar.gz" -C "$SRC_DIR"
    mv "$SRC_DIR/libffi-3.8.0"/* "$LIBFFI_DIR/"
    rmdir "$SRC_DIR/libffi-3.8.0"
    rm -f "$SRC_DIR/libffi.tar.gz"
fi

if [[ ! -f "$LIBFFI_DIR/meson.build" ]]; then
    if [[ -d "$LIBFFI_DIR/libffi-3.8.0" ]]; then
        cp -a "$LIBFFI_DIR/libffi-3.8.0/"* "$LIBFFI_DIR/"
        rm -rf "$LIBFFI_DIR/libffi-3.8.0"
    else
        info "Fetching Meson wrap for libffi..."
        curl -sL https://wrapdb.mesonbuild.com/v2/libffi_3.8.0-1/get_patch -o "$SRC_DIR/libffi-patch.zip"
        echo "9674679806598d276ee49ecdf27f1b3eb62fbf4776723ef67f0e14f9200a6f6d  $SRC_DIR/libffi-patch.zip" | sha256sum -c - || die "libffi patch checksum failed"
        unzip -q -o "$SRC_DIR/libffi-patch.zip" -d "$SRC_DIR/libffi-wrap-extract"
        cp -a "$SRC_DIR/libffi-wrap-extract/libffi-3.8.0/"* "$LIBFFI_DIR/"
        rm -rf "$SRC_DIR/libffi-wrap-extract" "$SRC_DIR/libffi-patch.zip"
    fi
fi

info "Building libffi for ARM64 Android..."
rm -rf "$LIBFFI_DIR/build-android"
meson setup "$LIBFFI_DIR/build-android" "$LIBFFI_DIR" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX"
ninja -C "$LIBFFI_DIR/build-android" install

# --- Step C: Wayland Target Libraries ---
info "Building Wayland target libraries for ARM64 Android..."
rm -rf "$SRC_DIR/wayland/build-android"
meson setup "$SRC_DIR/wayland/build-android" "$SRC_DIR/wayland" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Dscanner=false -Dlibraries=true -Ddocumentation=false -Ddtd_validation=false -Dtests=false
ninja -C "$SRC_DIR/wayland/build-android" install

# --- Step D: wayland-protocols ---
PROTOCOLS_COMMIT="ee78491a237eaff9389a0ccf8680521d074407d3"
fetch_repo "wayland-protocols" "https://github.com/InfidelRahul/wayland-protocols.git" "$PROTOCOLS_COMMIT"

info "Installing wayland-protocols..."
rm -rf "$SRC_DIR/wayland-protocols/build-android"
meson setup "$SRC_DIR/wayland-protocols/build-android" "$SRC_DIR/wayland-protocols" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Dtests=false
ninja -C "$SRC_DIR/wayland-protocols/build-android" install

# --- Step E: Pixman (with NEON) ---
PIXMAN_COMMIT="9cc163c9da0fb4da430641715313d95a6ec466d9"
fetch_repo "pixman" "https://github.com/InfidelRahul/pixman.git" "$PIXMAN_COMMIT"

info "Building Pixman (AArch64 NEON) for Android..."
rm -rf "$SRC_DIR/pixman/build-android"
meson setup "$SRC_DIR/pixman/build-android" "$SRC_DIR/pixman" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Da64-neon=enabled -Ddemos=disabled -Dtests=disabled
ninja -C "$SRC_DIR/pixman/build-android" install

# --- Step F: libxkbcommon ---
XKBCOMMON_COMMIT="dd642359f8d43c09968e34ca7f1eb1121b2dfd70"
fetch_repo "libxkbcommon" "https://github.com/xkbcommon/libxkbcommon.git" "$XKBCOMMON_COMMIT"

info "Building libxkbcommon for Android..."
rm -rf "$SRC_DIR/libxkbcommon/build-android"
meson setup "$SRC_DIR/libxkbcommon/build-android" "$SRC_DIR/libxkbcommon" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Denable-x11=false -Denable-xkbregistry=false -Denable-docs=false -Denable-tools=false -Denable-bash-completion=false
ninja -C "$SRC_DIR/libxkbcommon/build-android" libxkbcommon.so
meson install -C "$SRC_DIR/libxkbcommon/build-android" --no-rebuild

# --- Step G: xkeyboard-config ---
XKB_CONFIG_COMMIT="cf8d1151bb4c9ef5b895828d0e6dd7860c0833e0"
fetch_repo "xkeyboard-config" "https://gitlab.freedesktop.org/xkeyboard-config/xkeyboard-config.git" "$XKB_CONFIG_COMMIT"

info "Installing xkeyboard-config..."
rm -rf "$SRC_DIR/xkeyboard-config/build"
meson setup "$SRC_DIR/xkeyboard-config/build" "$SRC_DIR/xkeyboard-config" \
    --prefix "$PREFIX"
ninja -C "$SRC_DIR/xkeyboard-config/build" install

# --- Step H: libdrm ---
info "Preparing libdrm..."
LIBDRM_DIR="$SRC_DIR/libdrm"
if [[ ! -d "$LIBDRM_DIR" ]]; then
    mkdir -p "$LIBDRM_DIR"
    curl -sL https://dri.freedesktop.org/libdrm/libdrm-2.4.134.tar.xz -o "$SRC_DIR/libdrm.tar.xz"
    echo "ac5e74d157830eb8bee44c6a6bf3ad49774ef0dd2a72bdad74a8f20308b52a95  $SRC_DIR/libdrm.tar.xz" | sha256sum -c - || die "libdrm checksum failed"
    tar -xf "$SRC_DIR/libdrm.tar.xz" -C "$SRC_DIR"
    mv "$SRC_DIR/libdrm-2.4.134"/* "$LIBDRM_DIR/"
    rm -rf "$SRC_DIR/libdrm-2.4.134"
    rm -f "$SRC_DIR/libdrm.tar.xz"
fi

info "Building libdrm for ARM64 Android..."
rm -rf "$LIBDRM_DIR/build-android"
meson setup "$LIBDRM_DIR/build-android" "$LIBDRM_DIR" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Dintel=disabled -Dradeon=disabled -Damdgpu=disabled -Dnouveau=disabled -Dvmwgfx=disabled -Domap=disabled -Dexynos=disabled \
    -Dfreedreno=disabled -Dtegra=disabled -Dvc4=disabled -Detnaviv=disabled -Dcairo-tests=disabled -Dman-pages=disabled -Dvalgrind=disabled -Dtests=false
ninja -C "$LIBDRM_DIR/build-android" install

# --- Step I: Weston / libweston 16 ---
WESTON_COMMIT="d1882b0a544ae2197b597a6e39478e719bc54302"
fetch_repo "weston" "https://github.com/InfidelRahul/weston.git" "$WESTON_COMMIT"

info "Applying Android Bionic compatibility adjustments to Weston..."
WESTON_SRC="$SRC_DIR/weston" python3 - << 'PYINNER'
import os
weston_src = os.environ['WESTON_SRC']
# 1. shared/xalloc.h -> getprogname() on Android
with open(os.path.join(weston_src, "shared/xalloc.h"), "r") as f:
    c = f.read()
if "getprogname()" not in c:
    c = c.replace(
        "written = write(STDERR_FILENO, program_invocation_short_name,\n\t\t        strlen(program_invocation_short_name));",
        """#if defined(__ANDROID__)
\tconst char *progname = getprogname();
\tif (!progname) progname = "weston";
\twritten = write(STDERR_FILENO, progname, strlen(progname));
#else
\twritten = write(STDERR_FILENO, program_invocation_short_name,
\t\t        strlen(program_invocation_short_name));
#endif"""
    )
    with open(os.path.join(weston_src, "shared/xalloc.h"), "w") as f:
        f.write(c)

# 2. libweston/input.c -> guard values.h
with open(os.path.join(weston_src, "libweston/input.c"), "r") as f:
    c = f.read()
if "!defined(__ANDROID__)\n#include <values.h>" not in c:
    c = c.replace("#include <values.h>", "#if !defined(__ANDROID__)\n#include <values.h>\n#endif")
    with open(os.path.join(weston_src, "libweston/input.c"), "w") as f:
        f.write(c)

# 3. libweston/weston-log.c -> funopen() on Android
with open(os.path.join(weston_src, "libweston/weston-log.c"), "r") as f:
    c = f.read()
if "input_stream_write_bionic" not in c:
    c = c.replace(
        "static int\ninput_stream_close(void *cookie)\n{\n\tstruct weston_log_scope *scope = cookie;\n\n\tscope->input_stream = NULL;\n\treturn 0;\n}",
        """static int
input_stream_close(void *cookie)
{
\tstruct weston_log_scope *scope = cookie;

\tscope->input_stream = NULL;
\treturn 0;
}

#if defined(__ANDROID__)
static int
input_stream_write_bionic(void *cookie, const char *buf, int size)
{
\tstruct weston_log_scope *scope = cookie;
\tif (size > 0)
\t\tweston_log_scope_do_write(scope, buf, (size_t)size);
\treturn size;
}
#endif"""
    )
    c = c.replace(
        "\tconst cookie_io_functions_t input_stream_io_funcs = {\n\t\t.write = input_stream_write,\n\t\t.close = input_stream_close,\n\t};",
        """#if !defined(__ANDROID__)
\tconst cookie_io_functions_t input_stream_io_funcs = {
\t\t.write = input_stream_write,
\t\t.close = input_stream_close,
\t};
#endif"""
    )
    c = c.replace(
        "scope->input_stream = fopencookie(scope, \"w\", input_stream_io_funcs);",
        """#if defined(__ANDROID__)
\tscope->input_stream = funopen(scope, NULL, input_stream_write_bionic, NULL, input_stream_close);
#else
\tscope->input_stream = fopencookie(scope, "w", input_stream_io_funcs);
#endif"""
    )
    with open(os.path.join(weston_src, "libweston/weston-log.c"), "w") as f:
        f.write(c)

# 4. meson_options.txt -> allow backend-default=none and frontend option
with open(os.path.join(weston_src, "meson_options.txt"), "r") as f:
    opts = f.read()
if "'none'" not in opts:
    opts = opts.replace("choices: [ 'auto', 'drm', 'wayland', 'x11', 'headless', 'rdp' ]",
                        "choices: [ 'auto', 'drm', 'wayland', 'x11', 'headless', 'rdp', 'none' ]")
if "'frontend'" not in opts:
    opts += "\noption('frontend', type: 'boolean', value: true, description: 'Weston frontend executable')\n"
with open(os.path.join(weston_src, "meson_options.txt"), "w") as f:
    f.write(opts)

# 5. meson.build -> make desktop components optional
with open(os.path.join(weston_src, "meson.build"), "r") as f:
    m = f.read()
if "backend_default != 'none'" not in m:
    m = m.replace(
        "config_h.set_quoted('WESTON_NATIVE_BACKEND', backend_default)\nmessage('The default backend is ' + backend_default)\nif not get_option('backend-' + backend_default)\n\terror('Backend @0@ was chosen as native but is not being built.'.format(backend_default))\nendif",
        """if backend_default != 'none'
\tconfig_h.set_quoted('WESTON_NATIVE_BACKEND', backend_default)
\tmessage('The default backend is ' + backend_default)
\tif not get_option('backend-' + backend_default)
\t\terror('Backend @0@ was chosen as native but is not being built.'.format(backend_default))
\tendif
endif"""
    )
    m = m.replace("dep_libinput = dependency('libinput', version: '>= 1.2.0')",
                  "dep_libinput = dependency('libinput', version: '>= 1.2.0', required: false)")
    m = m.replace("dep_libevdev = dependency('libevdev')",
                  "dep_libevdev = dependency('libevdev', required: false)")
    m = m.replace("required: true,\n)", "required: false,\n)")
    m = m.replace("subdir('frontend')", "if get_option('frontend')\n  subdir('frontend')\nendif")
    m = m.replace("subdir('clients')",
                  "if get_option('demo-clients') or get_option('tools').length() > 0 or get_option('simple-clients').length() > 0\n\tsubdir('clients')\nendif")
    with open(os.path.join(weston_src, "meson.build"), "w") as f:
        f.write(m)

# 6. shared/meson.build -> make cairo conditional
with open(os.path.join(weston_src, "shared/meson.build"), "r") as f:
    s = f.read()
if "have_cairo_shared" not in s:
    s = s.replace("""deps_cairo_shared = [
\tdep_libshared,
\tdependency('cairo'),
\tdependency('libpng'),
\tdep_pixman,
\tdep_libm,
]""", """dep_cairo = dependency('cairo', required: false)
dep_libpng = dependency('libpng', required: false)
have_cairo_shared = dep_cairo.found() and dep_libpng.found()
if have_cairo_shared
\tdeps_cairo_shared = [
\t\tdep_libshared,
\t\tdep_cairo,
\t\tdep_libpng,
\t\tdep_pixman,
\t\tdep_libm,
\t]""")
    s = s.replace("""dep_lib_cairo_shared = declare_dependency(
\tlink_with: lib_cairo_shared,
\tdependencies: deps_cairo_shared
)""", """dep_lib_cairo_shared = declare_dependency(
\tlink_with: lib_cairo_shared,
\tdependencies: deps_cairo_shared
)
else
\tdep_lib_cairo_shared = declare_dependency()
endif""")
    with open(os.path.join(weston_src, "shared/meson.build"), "w") as f:
        f.write(s)

# 7. libweston/meson.build -> make libinput-backend and renderer-borders conditional
with open(os.path.join(weston_src, "libweston/meson.build"), "r") as f:
    l = f.read()
if "have_cairo_shared" not in l:
    l = l.replace("""lib_renderer_borders = static_library(
\t'renderer-borders',
\t'renderer-borders.c',
\tinclude_directories: common_inc,
\tdependencies: [
\t\tdep_lib_cairo_shared,
\t\tdep_egl, # for gl-renderer.h
\t\tdeps_for_libweston_users,
\t],
\tbuild_by_default: false,
\tinstall: false
)
dep_lib_renderer_borders = declare_dependency(
\tlink_with: lib_renderer_borders,
\tdependencies: dep_lib_cairo_shared
)""", """if have_cairo_shared
lib_renderer_borders = static_library(
\t'renderer-borders',
\t'renderer-borders.c',
\tinclude_directories: common_inc,
\tdependencies: [
\t\tdep_lib_cairo_shared,
\t\tdep_egl,
\t\tdeps_for_libweston_users,
\t],
\tbuild_by_default: false,
\tinstall: false
)
dep_lib_renderer_borders = declare_dependency(
\tlink_with: lib_renderer_borders,
\tdependencies: dep_lib_cairo_shared
)
else
dep_lib_renderer_borders = declare_dependency()
endif""")

    l = l.replace("""lib_libinput_backend = static_library(
\t'libinput-backend',
\t[
\t\t'libinput-device.c',
\t\t'libinput-seat.c',
\t\ttablet_unstable_v2_server_protocol_h
\t],
\tdependencies: [
\t\tdep_libweston_private,
\t\tdep_libinput,
\t\tdependency('libudev', version: '>= 136')
\t],
\tinclude_directories: common_inc,
\tinstall: false
)
dep_libinput_backend = declare_dependency(
\tlink_with: lib_libinput_backend,
\tinclude_directories: include_directories('.')
)""", """if get_option('backend-drm')
lib_libinput_backend = static_library(
\t'libinput-backend',
\t[
\t\t'libinput-device.c',
\t\t'libinput-seat.c',
\t\ttablet_unstable_v2_server_protocol_h
\t],
\tdependencies: [
\t\tdep_libweston_private,
\t\tdep_libinput,
\t\tdependency('libudev', version: '>= 136')
\t],
\tinclude_directories: common_inc,
\tinstall: false
)
dep_libinput_backend = declare_dependency(
\tlink_with: lib_libinput_backend,
\tinclude_directories: include_directories('.')
)
else
dep_libinput_backend = declare_dependency()
endif""")
    with open(os.path.join(weston_src, "libweston/meson.build"), "w") as f:
        f.write(l)
PYINNER

info "Building libweston 16 for ARM64 Android..."
rm -rf "$SRC_DIR/weston/build-android"
meson setup "$SRC_DIR/weston/build-android" "$SRC_DIR/weston" \
    --cross-file "$CROSS_FILE" \
    --prefix "$PREFIX" \
    -Dbackend-drm=false \
    -Dbackend-headless=false \
    -Dbackend-default=none \
    -Dbackend-wayland=false \
    -Dbackend-x11=false \
    -Dbackend-rdp=false \
    -Dbackend-vnc=false \
    -Dbackend-pipewire=false \
    -Dfrontend=false \
    -Dshell-desktop=false \
    -Dshell-ivi=false \
    -Dshell-kiosk=false \
    -Dshell-lua=false \
    -Dxwayland=false \
    -Drenderer-gl=false \
    -Drenderer-vulkan=false \
    -Dsystemd=false \
    -Dcolor-management-lcms=false \
    -Dimage-jpeg=false \
    -Dimage-webp=false \
    -Dtools=[] \
    -Ddemo-clients=false \
    -Dsimple-clients=[] \
    -Dtests=false
ninja -C "$SRC_DIR/weston/build-android" install
cp -f "$SRC_DIR/weston/libweston/backend.h" "$PREFIX/include/libweston-16/libweston/backend.h"

# --- Step J: Sync Artifacts to App jniLibs and assets ---
info "Syncing shared libraries to Android jniLibs/arm64-v8a..."
JNILIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"

REQUIRED_LIBS=(
    "libweston-16.so"
    "libwayland-server.so"
    "libwayland-client.so"
    "libwayland-cursor.so"
    "libpixman-1.so"
    "libxkbcommon.so"
    "libdrm.so"
    "libffi.so"
)

for lib in "${REQUIRED_LIBS[@]}"; do
    src_file="$PREFIX/lib/$lib"
    [[ -f "$src_file" ]] || die "Built library not found in prefix: $src_file"
    cp -f "$src_file" "$JNILIBS_DIR/$lib"
    info "Installed $lib -> $JNILIBS_DIR/$lib"
done

info "Syncing XKB configuration data to Android assets/xkb..."
ASSETS_XKB="$PROJECT_ROOT/app/src/main/assets/xkb"
mkdir -p "$ASSETS_XKB"
if [[ -d "$PREFIX/share/X11/xkb" ]]; then
    rm -rf "$ASSETS_XKB"/*
    cp -a "$PREFIX/share/X11/xkb"/* "$ASSETS_XKB/"
    info "Copied XKB data -> $ASSETS_XKB"
fi

# --- Step K: Strict ELF & Architecture Verification ---
info "Verifying ELF 64-bit AArch64 for all target libraries..."
for lib in "${REQUIRED_LIBS[@]}"; do
    lib_path="$JNILIBS_DIR/$lib"
    file_info="$(file "$lib_path")"
    echo "  $lib: $file_info"
    if [[ "$file_info" != *"ELF 64-bit"* || "$file_info" != *"aarch64"* ]]; then
        die "Architecture verification failed for $lib: $file_info"
    fi
done

info "Verifying libweston-16 dynamic dependencies (no X11 / XWayland / desktop deps)..."
"$TOOLCHAIN_BIN/llvm-readelf" -d "$JNILIBS_DIR/libweston-16.so" | grep NEEDED

info "=========================================================="
info "Native Wayland Dependency Foundation build SUCCESSFUL!"
info "Target ABI: arm64-v8a (AArch64)"
info "Prefix: $PREFIX"
info "Libraries: ${REQUIRED_LIBS[*]}"
info "=========================================================="
