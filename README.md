# LinuxDroid

A production-quality, native Android application that provides a persistent, rootless Linux userspace running directly on Android hardware.

## Key Properties

| Property | Value |
|----------|-------|
| **Root required** | ❌ No |
| **VM required** | ❌ No |
| **Custom kernel required** | ❌ No |
| **Kernel modules required** | ❌ No |
| **BusyBox required** | ❌ No |
| **Rootfs persistent** | ✅ Yes (never deleted) |
| **Initial distribution** | Debian arm64 |
| **Runtime** | LinuxDroid_proot versioned artifact (PRoot, ptrace-based rootless chroot) |
| **Display** | Wayland-first, XWayland for X11 |
| **Min SDK** | 28 (Android 9) |

## Architecture

```
Android UI (Jetpack Compose + Material3)
    ↓
Core modules (domain logic, managers)
    ↓
RuntimeAssetsManager + RuntimeLaunchPlan
    ↓
JNI bridge (native/bridge - genuine Android JNI only)
    ↓
Versioned LinuxDroid_proot artifact (proot + loader)
    ↓
Persistent Linux rootfs (Debian arm64)
    ↓
Wayland compositor → Android Surface → GPU → Display
```

## Module Structure

```
app/                — Application (Compose UI, Hilt root, foreground service)
core/
  core-model        — Domain models (Environment, Session, Process, errors)
  core-logging      — Structured subsystem logging
  core-database     — Room database (Android-side metadata only)
  core-runtime      — RuntimeBackend, asset validation, launch planning
  core-process      — ProcessManager
  core-session      — SessionManager
  core-filesystem   — PathValidator, EnvironmentStorage
  core-storage      — AndroidStorageManager (shared directory)
  core-display      — DisplayManager
  core-gpu          — GpuManager
  core-input        — InputManager
  core-audio        — AudioManager
  core-network      — NetworkManager
  core-package      — PackageManager abstraction
  core-diagnostics  — DiagnosticsManager
native/bridge       — Genuine Android JNI bridge (ONLY JNI entry point)
linux/bootstrap     — Rootfs download + installation
LinuxDroid_proot    — External PRoot + loader release consumed as an artifact
docs/               — Architecture, runtime, security, testing documentation
```

## Building

```bash
# Prerequisites: Java 17 or 21, Android SDK 35, NDK 27.2.12479018
export ANDROID_SDK_ROOT=/workspaces/android-sdk
export ANDROID_HOME=/workspaces/android-sdk
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.12+1.1-tem

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Full check
./gradlew check
```

> **Note**: Java 25+ is NOT supported (Kotlin 2.0.x compatibility issue). Use Java 17 or 21.

## Documentation

- [Architecture Overview](docs/architecture/overview.md)
- [Rootless Runtime](docs/runtime/rootless-runtime.md)
- [Security Architecture](docs/security/security.md)
- [Testing Guide](docs/testing/testing.md)
- [Build Guide](docs/development/build.md)
- [Updated Final Migration Plan](docs/migration-plan.md)

## How It Works

LinuxDroid consumes **LinuxDroid_proot** — our separately maintained, versioned PRoot runtime. PRoot is a user-space implementation of `chroot` using `ptrace(2)`; it intercepts Linux syscalls and rewrites filesystem paths so the Linux rootfs appears to be at `/`. LinuxDroid is responsible for assets, rootfs, launch plans, bindings, PTY, and graphics. The external repository owns the native engine, loader, Android compatibility, and native tests.

This requires:
- No root access
- No kernel modules
- No custom kernel
- Standard Android kernel (available on all Android 5+ devices)

The repository still contains the old bundled PRoot implementation while the migration is in progress. That code is a frozen baseline, not the target dependency model. See the [updated migration plan](docs/migration-plan.md).

The Linux filesystem is stored in the app's private storage and is **never deleted** by LinuxDroid. It persists across app restarts, Android reboots, and session failures.

## License

See [LICENSE](LICENSE) for details.