cmake_minimum_required(VERSION 3.22.1)

project(
    linuxdroid_bridge
    VERSION 1.0.0
    LANGUAGES C CXX
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compile flags
add_compile_options(
    -Wall
    -Wextra
    -fexceptions
    -frtti
    -DANDROID
)

# ---------------------------------------------------------------------------
# libweston integration (Phase 3)
# ---------------------------------------------------------------------------
# The pinned libweston 16.0.0 build output (see native/weston/) is installed
# under native/weston/dist. When it is present, the bridge compiles the real
# libweston compositor host + the LinuxDroid custom backend and links libweston.
# When it is absent (default checkout / CI, where libweston cannot currently be
# built), weston_host.cpp compiles its no-libweston fallback so the app still
# builds and the CLI/runtime remains unaffected.
set(
    LINUXDROID_WESTON_PREFIX
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../weston/dist"
    CACHE PATH "Pinned libweston install prefix (defaults to native/weston/dist)"
)

# Weston 16 installs its public headers under <prefix>/include/libweston-16/libweston/
# (meson: dir_include_libweston = 'libweston-@major@', dir_include_libweston_install
# = <that>/libweston). The top-level public header is libweston/libweston.h.
find_path(
    LIBWESTON_INCLUDE_DIR
    NAMES libweston/libweston.h
    PATHS
        "${LINUXDROID_WESTON_PREFIX}/include/libweston-16"
        "${LINUXDROID_WESTON_PREFIX}/include/libweston"
        "${LINUXDROID_WESTON_PREFIX}/include"
    NO_DEFAULT_PATH
)
# libweston/backend.h is the private (internal-backend) header that defines the
# full `struct weston_backend` used by linuxdroid_backend.c. build-libweston.sh
# installs it beside the public headers.
find_path(
    LIBWESTON_BACKEND_INCLUDE_DIR
    NAMES libweston/backend.h
    PATHS
        "${LINUXDROID_WESTON_PREFIX}/include/libweston-16"
        "${LINUXDROID_WESTON_PREFIX}/include/libweston"
        "${LINUXDROID_WESTON_PREFIX}/include"
    NO_DEFAULT_PATH
)
find_path(
    WAYLAND_SERVER_INCLUDE_DIR
    NAMES wayland-server.h
    PATHS
        "${LINUXDROID_WESTON_PREFIX}/include"
        "${LINUXDROID_WESTON_PREFIX}/include/libweston-16"
        "${LINUXDROID_WESTON_PREFIX}/include/libweston"
    NO_DEFAULT_PATH
)
# libweston.h includes <pixman.h> and <xkbcommon/xkbcommon.h>; pixman installs
# pixman.h to a pixman-1/ subdir, so add it to the include search.
find_path(
    PIXMAN_INCLUDE_DIR
    NAMES pixman.h
    PATHS
        "${LINUXDROID_WESTON_PREFIX}/include/pixman-1"
        "${LINUXDROID_WESTON_PREFIX}/include"
    NO_DEFAULT_PATH
)
find_library(
    LIBWESTON_LIBRARY
    NAMES libweston-16 libweston weston-16 weston
    PATHS "${LINUXDROID_WESTON_PREFIX}/lib"
    NO_DEFAULT_PATH
)
find_library(
    WAYLAND_SERVER_LIBRARY
    NAMES wayland-server libwayland-server
    PATHS "${LINUXDROID_WESTON_PREFIX}/lib"
    NO_DEFAULT_PATH
)

if(LIBWESTON_INCLUDE_DIR AND WAYLAND_SERVER_INCLUDE_DIR AND LIBWESTON_LIBRARY)
    set(LINUXDROID_HAS_LIBWESTON TRUE)
    message(STATUS "[linuxdroid] libweston found at ${LIBWESTON_LIBRARY}; enabling compositor host.")
else()
    set(LINUXDROID_HAS_LIBWESTON FALSE)
    message(STATUS "[linuxdroid] libweston NOT found (${LINUXDROID_WESTON_PREFIX}); "
                   "building compositor host fallback (no libweston).")
endif()

# When the build is gated on the real libweston (CI Phase 3), a fallback-only
# build must FAIL rather than silently shipping the no-libweston path.
option(LINUXDROID_REQUIRE_LIBWESTON "Fail the build if the real libweston path is not available" OFF)
if(LINUXDROID_REQUIRE_LIBWESTON AND NOT LINUXDROID_HAS_LIBWESTON)
    message(FATAL_ERROR
        "[linuxdroid] LINUXDROID_REQUIRE_LIBWESTON is set but the real libweston "
        "16.0.0 build was NOT detected under ${LINUXDROID_WESTON_PREFIX}. The "
        "Phase 3 compositor must link the generated libweston; a fallback-only "
        "build is not a success. "
        "Build native/weston (fetch + bootstrap deps + build-libweston.sh) first.")
endif()

# Main bridge library
add_library(
    linuxdroid_bridge
    SHARED
    linuxdroid_bridge.cpp
    process_manager.cpp
    filesystem_utils.cpp
    gpu_detector.cpp
    gui_host.cpp
    weston_host.cpp
    input_bridge.cpp
    audio_bridge.cpp
)

# Link Android system and graphics/audio libraries
target_link_libraries(
    linuxdroid_bridge
    android
    log
    jnigraphics
    EGL
    GLESv2
)

target_include_directories(
    linuxdroid_bridge
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(LINUXDROID_HAS_LIBWESTON)
    # Compile the custom LinuxDroid backend module.
    target_sources(
        linuxdroid_bridge
        PRIVATE
        linuxdroid_backend.c
    )

    # Enable the real libweston compositor host path and the backend module.
    target_compile_definitions(
        linuxdroid_bridge
        PRIVATE
        LINUXDROID_HAS_LIBWESTON=1
    )

    target_include_directories(
        linuxdroid_bridge
        PRIVATE
        ${LIBWESTON_INCLUDE_DIR}
        ${LIBWESTON_BACKEND_INCLUDE_DIR}
        ${WAYLAND_SERVER_INCLUDE_DIR}
        ${PIXMAN_INCLUDE_DIR}
    )

    # Make libweston's transitive shared deps (libpixman-1, libxkbcommon, libdrm)
    # resolvable by the Android linker if it requires them at link time.
    link_directories("${LINUXDROID_WESTON_PREFIX}/lib")

    # libweston plus libwayland-server (the host uses wl_display_* directly).
    # Additional runtime deps (wayland-protocols, pixman) are resolved by
    # libweston itself at build/link.
    target_link_libraries(
        linuxdroid_bridge
        ${LIBWESTON_LIBRARY}
        ${WAYLAND_SERVER_LIBRARY}
    )
endif()
