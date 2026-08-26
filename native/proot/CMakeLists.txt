cmake_minimum_required(VERSION 3.22.1)

project(proot_runtime VERSION 5.4.0 LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    set(LOADER_ARCH_FLAG "-DARCH_ARM64")
    set(LOADER_TARGET_TRIPLE "--target=aarch64-none-linux-android24")
    set(OBJCOPY_TARGET "elf64-littleaarch64")
    set(OBJCOPY_ARCH "aarch64")
    set(LOADER_ADDRESS "0x70000000")
elseif("${ANDROID_ABI}" STREQUAL "x86_64")
    set(LOADER_ARCH_FLAG "-DARCH_X86_64")
    set(LOADER_TARGET_TRIPLE "--target=x86_64-none-linux-android24")
    set(OBJCOPY_TARGET "elf64-x86-64")
    set(OBJCOPY_ARCH "i386:x86-64")
    set(LOADER_ADDRESS "0x70000000")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
endif()

if(NOT CMAKE_OBJCOPY OR NOT EXISTS "${CMAKE_OBJCOPY}")
    set(CMAKE_OBJCOPY "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy")
endif()

# 1. Compile static talloc library
add_library(talloc STATIC src/talloc/talloc.c)
target_include_directories(talloc PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src/talloc
    ${CMAKE_CURRENT_SOURCE_DIR}/src/talloc/replace
)
target_compile_definitions(talloc PRIVATE
    _GNU_SOURCE=1
)

# 2. Build standalone loader.elf
set(LOADER_ELF "${CMAKE_CURRENT_BINARY_DIR}/loader.elf")
set(LOADER_WRAPPED_O "${CMAKE_CURRENT_BINARY_DIR}/loader-wrapped.o")

add_custom_command(
    OUTPUT ${LOADER_ELF}
    COMMAND ${CMAKE_C_COMPILER}
        ${LOADER_TARGET_TRIPLE}
        --sysroot=${CMAKE_SYSROOT}
        ${LOADER_ARCH_FLAG}
        -DNO_LIBC_HEADER
        -I${CMAKE_CURRENT_SOURCE_DIR}/src
        -I${CMAKE_CURRENT_SOURCE_DIR}/src/uthash
        -I${CMAKE_CURRENT_SOURCE_DIR}/src/talloc
        -fPIC
        -ffreestanding
        -nostdlib
        -static
        -Wl,-Ttext=${LOADER_ADDRESS},-z,noexecstack
        ${CMAKE_CURRENT_SOURCE_DIR}/src/loader/loader.c
        -o ${LOADER_ELF}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/loader/loader.c
            ${CMAKE_CURRENT_SOURCE_DIR}/src/loader/assembly-arm64.h
            ${CMAKE_CURRENT_SOURCE_DIR}/src/loader/assembly-x86_64.h
    COMMENT "Compiling standalone loader for ${ANDROID_ABI}"
)

add_custom_command(
    OUTPUT ${LOADER_WRAPPED_O}
    COMMAND ${CMAKE_OBJCOPY}
        --input-target binary
        --output-target ${OBJCOPY_TARGET}
        --binary-architecture ${OBJCOPY_ARCH}
        loader.elf
        loader-wrapped.o
    WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    DEPENDS ${LOADER_ELF}
    COMMENT "Wrapping loader.elf into object file ${LOADER_WRAPPED_O}"
)

# 3. Build proot executable
set(PROOT_SOURCES
    src/cli/cli.c
    src/cli/proot.c
    src/cli/note.c
    src/execve/enter.c
    src/execve/exit.c
    src/execve/shebang.c
    src/execve/elf.c
    src/execve/ldso.c
    src/execve/auxv.c
    src/execve/aoxp.c
    src/path/binding.c
    src/path/glue.c
    src/path/canon.c
    src/path/path.c
    src/path/proc.c
    src/path/temp.c
    src/syscall/seccomp.c
    src/syscall/syscall.c
    src/syscall/chain.c
    src/syscall/enter.c
    src/syscall/exit.c
    src/syscall/sysnum.c
    src/syscall/socket.c
    src/syscall/heap.c
    src/syscall/rlimit.c
    src/tracee/tracee.c
    src/tracee/mem.c
    src/tracee/reg.c
    src/tracee/event.c
    src/ptrace/ptrace.c
    src/ptrace/user.c
    src/ptrace/wait.c
    src/extension/extension.c
    src/extension/kompat/kompat.c
    src/extension/fake_id0/fake_id0.c
    src/extension/link2symlink/link2symlink.c
    src/extension/portmap/portmap.c
    src/extension/portmap/map.c
    ${LOADER_WRAPPED_O}
)

add_executable(proot ${PROOT_SOURCES})

target_include_directories(proot PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/src/uthash
    ${CMAKE_CURRENT_SOURCE_DIR}/src/talloc
)

target_compile_definitions(proot PRIVATE
    _FILE_OFFSET_BITS=64
    _GNU_SOURCE=1
    VERSION="v5.4.0-LinuxDroid"
)

target_link_libraries(proot PRIVATE
    talloc
    log
    dl
)

# Copy output binaries to application assets and jniLibs for nativeLibraryDir execution
set(ASSETS_TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/assets/proot/${ANDROID_ABI}")
set(JNILIBS_TARGET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/jniLibs/${ANDROID_ABI}")

add_custom_command(TARGET proot POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory ${ASSETS_TARGET_DIR}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${JNILIBS_TARGET_DIR}
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:proot> ${ASSETS_TARGET_DIR}/proot
    COMMAND ${CMAKE_COMMAND} -E copy ${LOADER_ELF} ${ASSETS_TARGET_DIR}/loader
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:proot> ${JNILIBS_TARGET_DIR}/libproot.so
    COMMAND ${CMAKE_COMMAND} -E copy ${LOADER_ELF} ${JNILIBS_TARGET_DIR}/libproot_loader.so
    COMMENT "Copying compiled proot and loader binaries to assets and jniLibs"
)

