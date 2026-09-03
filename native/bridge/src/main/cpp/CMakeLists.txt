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

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(WESTON_PREFIX "${PROJECT_ROOT}/native/weston/prefix")
set(ARM64_JNILIBS "${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a")

# Main bridge library
add_library(
    linuxdroid_bridge
    SHARED
    linuxdroid_bridge.cpp
    process_manager.cpp
    filesystem_utils.cpp
    gpu_detector.cpp
    display_bridge.cpp
    input_bridge.cpp
    audio_bridge.cpp
    wayland_foundation_test.cpp
    linuxdroid_backend.c
    gui_host.cpp
)

# Imported Wayland and Weston native dependency stack
add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libwayland-server.so)

add_library(weston-16 SHARED IMPORTED)
set_target_properties(weston-16 PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libweston-16.so)

add_library(pixman-1 SHARED IMPORTED)
set_target_properties(pixman-1 PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libpixman-1.so)

add_library(xkbcommon SHARED IMPORTED)
set_target_properties(xkbcommon PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libxkbcommon.so)

# Link Android system and graphics/audio libraries + Wayland stack
target_link_libraries(
    linuxdroid_bridge
    android
    log
    jnigraphics
    EGL
    GLESv2
    wayland-server
    weston-16
    pixman-1
    xkbcommon
)

target_include_directories(
    linuxdroid_bridge
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libweston-16
)

# Standalone native GUI host test executable (minimal Phase 2 validation)
add_executable(
    gui_host_test
    gui_host_test.cpp
    linuxdroid_backend.c
    gui_host.cpp
)

target_link_libraries(
    gui_host_test
    android
    log
    wayland-server
    weston-16
    pixman-1
    xkbcommon
)

target_include_directories(
    gui_host_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${WESTON_PREFIX}/include/libweston-16
)

