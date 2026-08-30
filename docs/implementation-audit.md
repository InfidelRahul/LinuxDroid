# LinuxDroid — Source Code Implementation Audit

**Date:** 2026-08-26  
**Auditor:** Principal Engineer  
**Status Key:**
- `IMPLEMENTED`: Production-ready or fully working core logic
- `PARTIALLY IMPLEMENTED`: Functional foundation present, requires expansion for full capabilities
- `PLACEHOLDER`: Interface or stub declared without concrete engine
- `MOCK`: Fake or simulated behavior
- `UNUSED`: Code present but unreferenced
- `BROKEN`: Code contains functional or architectural defects
- `MISSING`: Required subsystem not yet present in codebase

---

## Subsystem Audit Matrix

| Subsystem | Current Status | Implementation Location | Missing Functionality / Notes |
| :--- | :--- | :--- | :--- |
| **Build & Gradle** | `IMPLEMENTED` | Root, 17 modules, `libs.versions.toml` | NDK 27.2, CMake 3.22, AGP 8.7.3, Kotlin 2.0.21, Java 21 toolchain. Builds all AARs & APK. |
| **Domain Models & State Machine** | `IMPLEMENTED` | `core/core-model/` | 9-state lifecycle (`CREATED` to `FAILED`/`RECOVERING`), immutable IDs, legal transitions with invariants, 100% test coverage. |
| **Logging & Diagnostics Logging** | `IMPLEMENTED` | `core/core-logging/` | `LinuxDroidLogger` with 19 structured `LogSubsystem` tags. Timber integration. |
| **Metadata Database** | `IMPLEMENTED` | `core/core-database/` | Room database (`LinuxDroidDatabase`), `EnvironmentEntity`, `EnvironmentDao`, `EnvironmentMapper` with JSON config serialization. |
| **Persistent Filesystem Layout** | `IMPLEMENTED` | `core/core-filesystem/` | `EnvironmentStorage` (rootfs layout, verify, clean runtime state; never deletes rootfs), `PathValidator` (traversal guard). |
| **Shared Storage Bridge** | `IMPLEMENTED` | `core/core-storage/` | `AndroidStorageManager` managing `/storage/emulated/0/LinuxDroid/` authorization state, directory creation, verification, and revocation safety. |
| **Rootless Runtime Backend** | `IMPLEMENTED` (legacy baseline) | `core/core-runtime/` | `RuntimeBackend` abstraction and `ProotRuntimeBackend` currently use the bundled ptrace-based prototype (`arm64-v8a` and `x86_64`). The migration target is a versioned `LinuxDroid_proot` artifact consumed through `RuntimeAssetsManager`; the in-repository PRoot source and JNI packaging must not be extended. |
| **Rootfs Bootstrap & Setup** | `IMPLEMENTED` | `linux/bootstrap/` | `RootfsBootstrapper` with Debian/Ubuntu arm64 download, SHA256 verification, `tar -xJf` extraction, DNS `resolv.conf`, user home setup. |
| **Native C++ JNI Bridge** | `PARTIALLY IMPLEMENTED` | `native/bridge/` | Versioning, ABI detection, memory check, file executable mode, signal dispatch (`kill`). Needs high-performance display/input/audio/GPU bindings. |
| **Process Management** | `PARTIALLY IMPLEMENTED` | `core/core-process/` | `ProcessManager` & `DefaultProcessManager` (process map, events, signal stopping). Needs comprehensive lifecycle tracking and asynchronous output streaming. |
| **Session Management** | `PARTIALLY IMPLEMENTED` | `core/core-session/` | `DefaultSessionManager` coordinating runtime lifecycle. Needs multi-subsystem startup sequence (GPU, Wayland, Audio, Input, Network, Desktop). |
| **Host Compatibility Layer** | `MISSING` | `core/core-host/` (to be created) | Abstract capability interfaces (`HostGraphics`, `HostGpu`, `HostAudio`, `HostInput`, `HostStorage`, `HostNetwork`, `HostCamera`, `HostSensors`) and native adapters. |
| **Display & Wayland Bridge** | `PLACEHOLDER` | `core/core-display/` | `DisplayManager` is an interface. Requires native display bridge, ANativeWindow binding, Wayland compositor client, surface lifecycle management. |
| **GPU Acceleration** | `PLACEHOLDER` | `core/core-gpu/` | `GpuManager` is an interface. Requires native EGL/Vulkan/GLES capability detection and hardware acceleration management. |
| **Input Subsystem** | `PLACEHOLDER` | `core/core-input/` | `InputManager` is an interface. Requires coordinate scaling, touch/mouse/keyboard event translation, and native Wayland input dispatch. |
| **Audio Subsystem** | `PLACEHOLDER` | `core/core-audio/` | `AudioManager` is an interface. Requires native AAudio/OpenSL ES audio bridge and pulse/pipewire socket bridge. |
| **Networking Subsystem** | `PLACEHOLDER` | `core/core-network/` | `NetworkManager` is an interface. Requires `ConnectivityManager` host network monitoring, DNS verification, and reconnect handling. |
| **Package Management** | `PLACEHOLDER` | `core/core-package/` | `PackageManager` is an interface. Requires execution of `apt-get`, `dpkg`, search, install, update inside proot runtime. |
| **Application Discovery** | `MISSING` | `core/core-package/` or `core/core-app/` | `.desktop` file parser for `/usr/share/applications` and `~/.local/share/applications`, icon extractor, and launcher. |
| **Resource Monitoring** | `PARTIALLY IMPLEMENTED` | `core/core-model/ResourceStatus.kt` | Data class exists. Requires `ResourceManager` collecting CPU, RAM, thermal, battery, and process counts via `/proc` and Android battery/thermal APIs. |
| **Diagnostics Subsystem** | `IMPLEMENTED` | `core/core-diagnostics/` | `DiagnosticsManager` checks runtime, filesystem, memory, display, storage, audio, network. |
| **Recovery Subsystem** | `PARTIALLY IMPLEMENTED` | `core/core-model/`, `EnvironmentViewModel` | State machine supports `RECOVERING`. Requires structured subsystem failure handler and soft restart without rootfs reinstallation. |
| **Android Lifecycle & Service** | `IMPLEMENTED` | `app/service/` | `LinuxSessionService` foreground service with persistent notification and stop action. |
| **UI & ViewModels** | `IMPLEMENTED` | `app/src/main/kotlin/com/linuxdroid/app/` | `HomeScreen`, `EnvironmentListScreen` (create dialog, live install progress, start/stop/shell), `TerminalScreen` (live monospace shell), `DiagnosticsScreen`, `SettingsScreen`, `AboutScreen`, ViewModels wired via Hilt DI. |

---

## Detailed Subsystem Breakdown

### 1. Rootless Runtime & Persistence
- **Current implementation (legacy baseline):** `ProotRuntimeBackend` resolves the bundled PRoot prototype from `jniLibs` or extracts it into the app runtime directory. It uses Android `ProcessBuilder` with strict bind mounts (`-b /dev`, `-b /proc`, `-b /sys`, `--link2symlink`, `--root-id`, `--cwd`).
- **Migration target:** `RuntimeAssetsManager` will consume a versioned `proot` and `loader` release from [LinuxDroid_proot](https://github.com/InfidelRahul/LinuxDroid_proot). LinuxDroid will validate and launch the artifact but will not own its source, ptrace implementation, seccomp behavior, or loader fixes.
- **Persistence guarantee:** `EnvironmentStorage` separates `rootfs/` from `runtime-state/` and `tmp/`. The `rootfs/` directory is never purged on stop, restart, or crash recovery.
- **Architectural risk:** PRoot depends on `ptrace` system calls. On modern Android versions (Android 10+), seccomp filters and W^X memory policies must be respected. These native compatibility issues are release-gated and fixed in LinuxDroid_proot before LinuxDroid updates its runtime dependency.

### 2. Host Compatibility Layer & Native Bridge
- **Current implementation:** `native/bridge` contains C++17 JNI functions for PID signaling, memory checks, file execution permissions, and ABI inspection.
- **Missing functionality:** High-performance direct path for graphics buffer exchange (`ANativeWindow`, `AHardwareBuffer`), native input FIFO/socket routing, and native AAudio rendering.
- **Required changes:** Create the `HostCompatibility` abstraction layer in `core/` and expand `native/bridge` with hardware abstraction backends (Graphics, Input, Audio, GPU).

### 3. Display & Wayland Pipeline
- **Current implementation:** `DisplayConfig` domain model and `DisplayManager` interface.
- **Required changes:** Implement `DefaultDisplayManager` and `HostGraphics` with:
  - Surface lifecycle binding (creation, destruction, resize, orientation changes).
  - Native framebuffer/surface bridge connecting to `ANativeWindow`.
  - Wayland compositor runner/controller (cage / weston / headless Wayland server).

### 4. GPU Acceleration & Zero-Copy Graphics
- **Current implementation:** `GpuConfig` and `GpuInfo` data structures.
- **Required changes:** Implement `DefaultGpuManager` with native GLES/Vulkan detection (`eglQueryString`, `vkEnumerateInstanceExtensionProperties`), capability reporting, and fallback to software rasterization when hardware acceleration is unavailable.

### 5. Input System
- **Current implementation:** `InputManager` interface.
- **Required changes:** Implement `DefaultInputManager` and `HostInput` with touch coordinate mapping to Linux resolution, mouse pointer button tracking, and keycode translation from Android `KeyEvent` to Linux `linux/input-event-codes.h` (evdev/uinput compatible).

### 6. Audio Subsystem
- **Current implementation:** `AudioManager` interface.
- **Required changes:** Implement `DefaultAudioManager` and `HostAudio` bridging Linux audio (PulseAudio socket / Simple PCM stream) to Android `AudioTrack` / `AAudio` native player.

### 7. Package Management & Application Discovery
- **Current implementation:** `PackageManager` interface.
- **Required changes:** Implement `DefaultPackageManager` executing `apt-get update`, `apt-get install`, `dpkg -l`, and `apt-cache search` within proot. Implement `ApplicationManager` parsing standard `.desktop` entry files.

### 8. Resource Monitoring & Diagnostics
- **Current implementation:** `DiagnosticsManager` and `ResourceStatus` model.
- **Required changes:** Implement `DefaultResourceManager` calculating real CPU usage via `/proc/stat`, RAM via `/proc/meminfo`, and thermal/battery via Android BatteryManager/PowerManager.

---

## Updated migration direction

The old phase numbering and the assumption that LinuxDroid builds PRoot locally are retired. Follow the [Updated Final Migration Plan](migration-plan.md), whose first gates are the independent `LinuxDroid_proot` baseline, Android/ARM64 compatibility work, native tests, diagnostics, and release artifacts. Only then should LinuxDroid implement `RuntimeAssetsManager` and migrate the runtime integration.

The remaining LinuxDroid work is therefore ordered as follows:

1. Freeze and document this repository's legacy bundled runtime baseline.
2. Wait for a tested, versioned `LinuxDroid_proot` release.
3. Audit `NativeBridge`, `ProotRuntimeBackend`, command construction, validation, rootfs setup, PTY, and packaging against the external-artifact boundary.
4. Add runtime asset installation and verification, then remove PRoot from the JNI packaging.
5. Separate rootfs management, environment construction, binding resolution, launch planning, process launching, and PTY launching.
6. Validate normal execution before optional Wayland and desktop work.
7. Perform differential maintenance in `LinuxDroid_proot`; update LinuxDroid only by selecting a new verified artifact.
8. Remove the legacy in-repository PRoot path only after full integration testing.

