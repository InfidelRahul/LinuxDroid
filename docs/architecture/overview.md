# LinuxDroid — Architecture Overview

## Product

LinuxDroid provides a persistent, rootless Linux userspace running directly on Android hardware.

- **No root required**
- **No VM required**
- **No custom kernel required**
- **Persistent filesystem** — changes survive stop/restart/Android lifecycle events

---

## Top-Level Architecture

```
Android UI (Jetpack Compose)
    ↓
Kotlin application layer (ViewModels, Hilt DI)
    ↓
LinuxDroid Core (domain logic, managers)
    ↓
JNI / Native API (single bridge point: NativeBridge.kt)
    ↓
Native Runtime (C++17 via Android NDK)
    ↓
proot (rootless chroot via ptrace syscall interception)
    ↓
Persistent Linux rootfs (Debian arm64)
    ↓
Wayland compositor
    ↓
Android display bridge (SurfaceView)
    ↓
Android graphics / GPU
    ↓
Device display
```

---

## Rootless Runtime: proot

The core technology enabling rootless Linux is **proot**.

proot works by:
1. Using `ptrace(2)` to intercept all syscalls from Linux processes
2. Rewriting filesystem paths so the rootfs appears to be at `/`
3. Faking `getuid()`/`getgid()` to return 0 (fake root)
4. Emulating bind mounts via path rewriting

**Why proot and not bubblewrap/namespaces?**
- Android's kernel disables user namespaces on most devices
- proot requires no kernel features beyond `ptrace` (available on all Android versions)
- Termux, UserLAnd, and similar production apps use the same approach

**Limitations:**
- Performance overhead from ptrace (~5-15% depending on workload)
- Some syscalls cannot be fully emulated (e.g. advanced networking syscalls)
- Network uses the host Android stack — no separate network namespace

---

## Module Structure

```
app/                    — Single Android Activity, Compose navigation, Hilt root
core/
  core-model/           — Pure domain models (Environment, Session, Process, errors)
  core-logging/         — Structured logging with subsystem tagging
  core-database/        — Room database for Android-side metadata
  core-runtime/         — RuntimeBackend interface + proot implementation
  core-process/         — ProcessManager interface
  core-session/         — SessionManager interface
  core-filesystem/      — Path validation, EnvironmentStorage
  core-storage/         — AndroidStorageManager (shared dir)
  core-display/         — DisplayManager interface
  core-gpu/             — GpuManager interface
  core-input/           — InputManager interface
  core-audio/           — AudioManager interface
  core-network/         — NetworkManager interface
  core-package/         — PackageManager interface (delegates to Linux pkg mgr)
  core-diagnostics/     — DiagnosticsManager
native/
  bridge/               — JNI bridge (ONLY JNI entry point), C++17 native code
linux/
  bootstrap/            — Rootfs bootstrap scripts
  distributions/        — Distribution provider abstraction
  configuration/        — Startup scripts and environment setup
```

---

## Persistence Model

```
Android internal storage (app private):
  filesDir/environments/
    <env-id>/
      rootfs/        ← Linux filesystem root (NEVER deleted by LinuxDroid)
      metadata/      ← Environment metadata files
      runtime-state/ ← Transient state (cleaned at startup)
      tmp/           ← Temporary files

Android external storage (user authorized):
  /storage/emulated/0/LinuxDroid/
    ← Shared with Linux at /home/user/Android/
```

**Critical rule:** The `rootfs/` directory is NEVER deleted by any LinuxDroid code.  
Runtime failures, crashes, and recovery NEVER touch the rootfs.

---

## State Machine

The `EnvironmentState` state machine enforces valid lifecycle transitions:

```
CREATED → INSTALLING → READY ←→ STOPPED
                         ↓            ↑
                       STARTING → RUNNING → STOPPING → STOPPED
                         ↓           ↓
                       FAILED → RECOVERING → READY
                         ↓
                       FAILED (unrecoverable)
```

Invalid transitions throw `IllegalStateTransitionException`.  
The UI is NEVER authoritative for state — only the runtime reports state.

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Android UI | Kotlin + Jetpack Compose + Material3 |
| DI | Hilt |
| Navigation | Jetpack Navigation Compose |
| Database | Room (metadata only) |
| Async | Kotlin Coroutines + StateFlow/SharedFlow |
| JNI | Kotlin external + C++17 |
| Native build | CMake 3.22 |
| Runtime | proot (bundled binary) |
| Display | Wayland (primary), XWayland (X11 compat) |
| Target ABI | arm64-v8a (primary), x86_64 (emulator) |
| Min SDK | 28 (Android 9) |
| Target SDK | 35 (Android 15) |

---

## Security Principles

1. LinuxDroid is **never** root and never acquires root
2. All filesystem paths are validated via `PathValidator` before use
3. `NativeBridge` is the **only** JNI call site in the codebase
4. The Linux rootfs is sandboxed within the app's private storage
5. The shared directory (`/storage/emulated/0/LinuxDroid/`) requires explicit user authorization
6. Commands are never passed through a shell for execution — always executed as a `List<String>`
7. Environment IDs are validated to be alphanumeric to prevent path injection
