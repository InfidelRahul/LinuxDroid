# LinuxDroid — Architecture Overview

## 1. Introduction & Core Mission
LinuxDroid is a native Android application that provides a persistent, rootless Linux userspace running directly on Android hardware.

LinuxDroid is **NOT** a VM product, does **NOT** require root/su access, does **NOT** depend on custom kernels or kernel modules, does **NOT** use ISO live-boot semantics, and does **NOT** require BusyBox as a dependency.

## 2. Layered Architecture
```
┌────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                   │
│   (HomeScreen, EnvironmentList, Terminal, Diagnostics) │
└───────────────────────────┬────────────────────────────┘
                            │ ViewModel StateFlow
┌───────────────────────────▼────────────────────────────┐
│                    ViewModel Layer                     │
│  (EnvironmentViewModel, TerminalViewModel, Diagnostics)│
└───────────────────────────┬────────────────────────────┘
                            │ Domain Calls
┌───────────────────────────▼────────────────────────────┐
│                   Core / Domain Layer                  │
│    (SessionManager, ProcessManager, PackageManager,    │
│     DiagnosticsManager, ResourceManager, Storage)      │
└───────────────────────────┬────────────────────────────┘
                            │ Runtime & Host Abstractions
┌───────────────────────────▼────────────────────────────┐
│             Host Compatibility Layer (core-host)       │
│  (HostGraphics, HostGpu, HostAudio, HostInput, Net)    │
└───────────────────────────┬────────────────────────────┘
                            │ Direct High-Performance C++
┌───────────────────────────▼────────────────────────────┐
│               Native Bridge (liblinuxdroid_bridge)     │
│       Genuine Android JNI and host hardware bridges    │
└───────────────────────────┬────────────────────────────┘
                            │ consumes versioned artifact
┌───────────────────────────▼────────────────────────────┐
│        LinuxDroid Runtime Integration                  │
│ RuntimeAssetsManager · RootfsManager · LaunchPlan      │
│ Bindings · ProcessLauncher · PtyLauncher               │
└───────────────────────────┬────────────────────────────┘
                            │ LinuxDroid_proot contract
┌───────────────────────────▼────────────────────────────┐
│        LinuxDroid_proot (external repository)          │
│       PRoot native engine · loader · Android fixes     │
└───────────────────────────┬────────────────────────────┘
                            │ ptrace / syscall interception
┌───────────────────────────▼────────────────────────────┐
│          Persistent Linux rootfs and applications      │
│          (/bin/sh, apt, dpkg, Debian arm64)            │
└────────────────────────────────────────────────────────┘
```

`LinuxDroid_proot` is a hard prerequisite and is not a Gradle module owned by this repository. LinuxDroid consumes its versioned `proot` and `loader` artifacts through `RuntimeAssetsManager`. The current `native/proot` module and `jniLibs` binaries are retained only as the migration baseline until the legacy cleanup phase.

## 3. Key Design Tenets
1. **Unconditional Persistence:** Rootfs directories (`<filesDir>/environments/<id>/rootfs`) are never touched or purged on stop, crash, or application restart.
2. **Wayland First:** Wayland is the primary graphical pipeline, connecting to Android's `ANativeWindow` display surface.
3. **Decoupled Lifecycle:** Android UI lifecycle is separated from the Linux runtime lifecycle via foreground services (`LinuxSessionService`).
4. **Host Compatibility Layer:** High-frequency rendering and audio bypass Java/JNI loops, executing directly across native platform boundaries.
