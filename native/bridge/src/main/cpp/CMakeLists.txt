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
    -Wno-unguarded-availability
    -Wno-unguarded-availability-new
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
    android_presentation.cpp
    linuxdroid_backend.c
    gui_host.cpp
)

# Imported Wayland and Weston native dependency stack
add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libwayland-server.so)

# Dynamically locate active libweston shared library in jniLibs
file(GLOB LIBWESTON_SO_FILES "${ARM64_JNILIBS}/libweston-*.so")
if(NOT LIBWESTON_SO_FILES)
    message(FATAL_ERROR "No libweston-*.so found in ${ARM64_JNILIBS}")
endif()
list(GET LIBWESTON_SO_FILES 0 LIBWESTON_ACTIVE_SO)
message(STATUS "Using libweston artifact: ${LIBWESTON_ACTIVE_SO}")

add_library(weston SHARED IMPORTED)
set_target_properties(weston PROPERTIES IMPORTED_LOCATION ${LIBWESTON_ACTIVE_SO})

add_library(pixman-1 SHARED IMPORTED)
set_target_properties(pixman-1 PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libpixman-1.so)

add_library(xkbcommon SHARED IMPORTED)
set_target_properties(xkbcommon PROPERTIES IMPORTED_LOCATION ${ARM64_JNILIBS}/libxkbcommon.so)

# Dynamically locate libweston include directories in prefix
file(GLOB LIBWESTON_INC_DIRS "${WESTON_PREFIX}/include/libweston-*")

# Link Android system and graphics/audio libraries + Wayland stack
target_link_libraries(
    linuxdroid_bridge
    android
    log
    jnigraphics
    EGL
    GLESv2
    wayland-server
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    linuxdroid_bridge
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${LIBWESTON_INC_DIRS}
)

# Standalone native GUI host test executable (minimal Phase 3 validation)
add_executable(
    gui_host_test
    gui_host_test.cpp
    android_presentation.cpp
    linuxdroid_backend.c
    gui_host.cpp
)

target_link_libraries(
    gui_host_test
    android
    log
    wayland-server
    weston
    pixman-1
    xkbcommon
)

target_include_directories(
    gui_host_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WESTON_PREFIX}/include
    ${WESTON_PREFIX}/include/pixman-1
    ${LIBWESTON_INC_DIRS}
)

# Standalone native Android presentation test executable (Phase 4 validation)
add_executable(
    android_presentation_test
    android_presentation_test.cpp
    android_presentation.cpp
)

target_link_libraries(
    android_presentation_test
    android
    log
)

target_include_directories(
    android_presentation_test
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

