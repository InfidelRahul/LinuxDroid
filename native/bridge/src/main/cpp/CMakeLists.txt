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

find_path(
    LIBWESTON_INCLUDE_DIR
    NAMES libweston/compositor.h
    PATHS
        "${LINUXDROID_WESTON_PREFIX}/include"
        "${LINUXDROID_WESTON_PREFIX}/include/libweston-16"
        "${LINUXDROID_WESTON_PREFIX}/include/libweston"
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
        ${WAYLAND_SERVER_INCLUDE_DIR}
    )

    # libweston plus libwayland-server (the host uses wl_display_* directly).
    # Additional runtime deps (wayland-protocols, pixman) are resolved by
    # libweston itself at build/link.
    target_link_libraries(
        linuxdroid_bridge
        ${LIBWESTON_LIBRARY}
        ${WAYLAND_SERVER_LIBRARY}
    )
endif()
