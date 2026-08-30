# LinuxDroid — Task Progress

> The canonical migration sequence is [docs/migration-plan.md](docs/migration-plan.md). LinuxDroid_proot is a hard prerequisite; the legacy bundled PRoot entries below describe the frozen baseline only.

## Phase 0: Project Initialization
- [x] Android SDK 35 + NDK 27.2.12479018 bootstrapped
- [x] Gradle 8.12 + wrapper configured
- [x] `settings.gradle.kts` with all module declarations
- [x] `gradle/libs.versions.toml` version catalog
- [x] `local.properties` with SDK/NDK paths
- [x] `gradle.properties` with JVM args and build settings
- [x] `app/proguard-rules.pro`
- [x] All AndroidManifest.xml files (app + all modules)
- [x] Launcher icons and color resources

## Phase 1: Kotlin/Compose Architecture
- [x] `core-model` — Environment, EnvironmentState machine, Session, Process, errors, diagnostics
- [x] `core-logging` — LinuxDroidLogger with subsystem tagging
- [x] `core-database` — Room DB, EnvironmentEntity, EnvironmentDao, EnvironmentMapper
- [x] `core-filesystem` — PathValidator, EnvironmentStorage
- [x] `core-storage` — AndroidStorageManager, StorageAuthorizationState
- [x] `core-runtime` — RuntimeBackend interface, ProotRuntimeBackend
- [x] `core-diagnostics` — DiagnosticsManager
- [x] Stub interfaces: SessionManager, DisplayManager, GpuManager, InputManager, AudioManager, NetworkManager, PackageManager, ProcessManager
- [x] App: LinuxDroidApplication, MainActivity, Compose navigation
- [x] UI screens: Home, EnvironmentList, Settings, Diagnostics, About
- [x] Service: LinuxSessionService (foreground)
- [x] Theme: LinuxDroidTheme (Material3 + dynamic color)

## Phase 2: Native NDK/CMake
- [x] `native/bridge/CMakeLists.txt`
- [x] `linuxdroid_bridge.h` / `linuxdroid_bridge.cpp` — JNI implementations
- [x] `process_manager.h` / `process_manager.cpp` — native process utilities
- [x] `filesystem_utils.h` / `filesystem_utils.cpp` — native filesystem utilities
- [x] `NativeBridge.kt` — single Kotlin JNI entry point

## Phase 3: JNI ↔ Native ↔ Kotlin Communication
- [x] JNI roundtrip test (build + run on device required)
- [x] Verify `getBridgeVersion()` returns 1
- [x] Verify `getAbi()` returns "arm64-v8a" on ARM64 device

## Phase 4: Environment State Machine + Domain
- [x] `EnvironmentStateMachineTest` — 15 unit tests covering valid/invalid transitions

## Phase 5: Storage Authorization
- [x] `AndroidStorageManager` — request/verify/revoke shared storage access
- [x] Integration test: Android → `/storage/emulated/0/LinuxDroid/` → Linux

## Phase 6: Rootless Runtime Prototype — legacy baseline
- [x] `ProotRuntimeBackend` — legacy bundled PRoot launcher
- [x] `linux/bootstrap/bootstrap-debian.sh` — Debian rootfs bootstrapper
- [x] `RootfsBootstrapper.kt` — Kotlin rootfs installation
- [x] Record the current bundled PRoot source and native packaging as migration baseline
- [x] Test: legacy PRoot launches on real ARM64 device
- [ ] Do not extend the bundled PRoot implementation; native fixes move to LinuxDroid_proot

## Phase 7: First Linux Shell — legacy baseline
- [x] Download Debian arm64 rootfs onto device
- [x] Execute `/bin/sh` via the legacy PRoot path
- [x] Capture stdout/stderr
- [x] Verify command output

## Updated migration tracking

The old future-milestone checklist is retired. It incorrectly implied that LinuxDroid should continue building PRoot in this repository and marked work complete before the external runtime contract existed.

The canonical plan is [LinuxDroid — Updated Final Migration Plan](docs/migration-plan.md). Its blocking order is:

1. Freeze the LinuxDroid baseline.
2. Make [LinuxDroid_proot](https://github.com/InfidelRahul/LinuxDroid_proot) independently buildable and testable.
3. Complete Android/ARM64 compatibility, native tests, diagnostics, and release artifacts there.
4. Add `RuntimeAssetsManager` and consume a versioned PRoot artifact in LinuxDroid.
5. Remove PRoot from the JNI packaging only after the replacement path is validated.
6. Complete rootfs isolation, launch planning, process/PTY launchers, integration testing, and final cleanup.

LinuxDroid is a consumer of LinuxDroid_proot, not the owner of the PRoot implementation. Any ptrace, seccomp, loader, ARM64, or syscall-interception fix must land in LinuxDroid_proot first, followed by a tested artifact update here.

