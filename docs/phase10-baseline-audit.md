# Phase 10: Baseline Audit & Inventory

## 1. Current Architecture Overview

LinuxDroid is structured around 5 decoupled, fundamental concepts:
1. **Distribution**: `DistributionDefinition`, `DistributionCatalog`, `DistributionSource`, `DistributionManifest` in `:core:core-model`, with `DistributionInstaller` and `DistributionValidator` in `:linux:bootstrap`.
2. **Environment**: `Environment` (Room entities, configuration, persistent directory tree) coordinated by `EnvironmentManager` and `EnvironmentStorage`.
3. **Runtime**: Immutable `RuntimeSpec` transformed via `ProotCommandBuilder` and executed by `ProotRuntimeBackend` through `RuntimeManager`.
4. **Session**: Managed by `SessionManager` and decomposed into `RuntimeSession`, `TerminalSession`, and `DesktopSession`.
5. **Process**: Monitored by `ProcessManager` using `ProcessHandle` (host PID, guest PID, role, signal, exit codes).

## 2. Relevant Modules and Dependencies

- `:core:core-model`: Data classes, value classes, errors, serialization, `RuntimeSpec`, `RuntimeProfile`, `DistributionDefinition`.
- `:core:core-logging`: Structured `LinuxDroidLogger` with subsystem tagging.
- `:core:core-database`: Room DAO (`EnvironmentDao`), entity mapping, `DefaultEnvironmentManager`.
- `:core:core-filesystem`: `EnvironmentStorage` (rootfs/metadata/tmp layout and verification).
- `:core:core-runtime`: `RuntimeManager`, `ProotRuntimeBackend`, `ProotCommandBuilder`, `RuntimeValidator`.
- `:core:core-session`: `SessionManager`, `RuntimeSession`, `TerminalSession`, `DesktopSession`.
- `:core:core-process`: `ProcessManager`, `DefaultProcessManager`.
- `:core:core-host`: Hardware/platform capability abstractions, `RuntimeProfile` mapping.
- `:core:core-display`: `DisplayManager`, surface lifecycles, Wayland socket hooks.
- `:core:core-gpu`: GPU capability detection via JNI.
- `:core:core-input`: Input event dispatchers (touch, mouse, keyboard).
- `:core:core-audio`: PCM audio stream playback via Android `AudioTrack`.
- `:core:core-network`: Network connectivity monitoring and DNS resolution.
- `:native:bridge`: JNI C++ layer for hardware bridges and signal dispatching.
- `:native:proot`: PRoot runtime source, loader ELF, and `linuxdroid-bootstrap` C executable.
- `:linux:bootstrap`: Rootfs downloader, compressor streams, installer, validator.
- `:app`: Android application container, Compose UI, ViewModels, Hilt DI.

## 3. Runtime & Environment Execution Paths

- **Execution Chain**: `RuntimeManager.execute(spec)` -> `RuntimeValidator.validate(spec)` -> `ProotCommandBuilder.build(spec)` -> `ProotRuntimeBackend.executeWithSpec(spec)` -> `NativeBridge` / `ProcessBuilder` -> `libproot.so` -> `linuxdroid-bootstrap` -> guest userspace.
- **Environment Lifecycle**: `CREATED` -> `INSTALLING` -> `READY` -> `STARTING` -> `RUNNING` -> `STOPPING` -> `STOPPED` -> `DELETED` / `FAILED`.

## 4. Native Build Path

- Authoritative CMake pipeline builds:
  - `libproot.so` + `libproot_loader.so`
  - `liblinuxdroid_bootstrap.so`
  - `liblinuxdroid_bridge.so`
- Post-build commands mirror binaries into `app/src/main/assets/proot/${ANDROID_ABI}` and `app/src/main/jniLibs/${ANDROID_ABI}`.

## 5. Baseline Test & Build Results

- **All Unit Tests**: 100% Passed (across all 16 modules).
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk` (64 MB).
- **Architectures**: `arm64-v8a` and `x86_64` binaries packaged and validated.
