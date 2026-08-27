# Phase 10: Final Architecture & Hardening Audit

## 1. Architecture Summary

LinuxDroid implements a clean, modular, standalone Linux runtime on Android partitioned across 5 distinct domains:

1. **Distribution**: `DistributionDefinition`, `DistributionCatalog` (Debian, Ubuntu, Arch Linux, Alpine Linux), `DistributionInstaller`, `DistributionValidator`.
2. **Environment**: `Environment` (Room database records + filesystem layout) coordinated by `EnvironmentManager` and `EnvironmentStorage`.
3. **Runtime**: Immutable `RuntimeSpec`, `ProotCommandBuilder`, `RuntimeValidator`, `ProotRuntimeBackend`, and `RuntimeManager`.
4. **Session**: `SessionManager` orchestrating `RuntimeSession`, `TerminalSession`, and `DesktopSession`.
5. **Process**: `ProcessManager` tracking `ProcessHandle` (host PID, guest PID, role, signal, exit codes).

## 2. Environment Lifecycle & Hardening

- **Staging-Based Installation**: Extraction and file configuration occur strictly in a dedicated staging directory (`<env-id>/tmp/rootfs-staging`).
- **Atomic Promotion**: Upon validation by `DistributionValidator`, the staging rootfs is promoted to the active rootfs directory with automatic backup and rollback.
- **Delete Hardening**: Deleting transitions state from `READY` -> `DELETING` in Room database before removing filesystem directories. Startup reconciliation cleans any interrupted `DELETING` state without leaving orphaned files.
- **Clone Hardening**: Clones copy from source rootfs directly to target staging rootfs, validate structure, and atomically promote to target active rootfs.
- **Reset Hardening**: Resets transition to `RESETTING`, clean transient runtime state, discard residual staging, and verify rootfs integrity before returning to `READY`.
- **Startup Reconciliation**: `reconcileEnvironments()` automatically heals interrupted operations on application launch.

## 3. Runtime Lifecycle & Command Builder

- **Deterministic Execution**: `ProotCommandBuilder` translates `RuntimeSpec` directly into arguments:
  `-0 --kill-on-exit --link2symlink -r <rootfs> -b <bindings> -w <cwd> <cmd>`
- **No Side-Effect Diagnostics**: `showRuntimeCommand(spec)` inspects the exact arguments that would be invoked without side effects or process spawning.
- **Pre-Flight Validation**: `RuntimeValidator` ensures binary existence, rootfs layout sanity, and executable permissions before invoking the native engine.

## 4. PRoot & Android 16 Tagged Pointers

- **AArch64 Pointer Normalization**: `UNTAG_ADDRESS(addr)` canonicalizes virtual memory addresses (`0xb4000078de2db6f0` -> `0x00000078de2db6f0`) while preserving user offsets for `PTRACE_PEEKUSER` / `PTRACE_POKEUSER`.
- **Memory Access Paths**: All tracee memory read/write functions (`read_data`, `write_data`, `read_string`, `peek_word`, `poke_word`, `writev_data`) normalize tracee memory addresses before passing them to `process_vm_readv`, `process_vm_writev`, and `PTRACE_PEEKDATA`/`PTRACE_POKEDATA`.

## 5. Bootstrap Architecture

- **Minimal Guest Helper**: `linuxdroid-bootstrap` C binary initializes guest directories (`/tmp`, `/run`, `/dev/shm`, `/dev/pts`) and environment variables (`PATH`, `TERM`, `LANG`, `TMPDIR`).
- **Clean Handoff**: Executes guest shell/init via `execvp()` according to `BOOTSTRAP_USERSPACE`, `BOOTSTRAP_DIRECT_EXEC`, or `BOOTSTRAP_NATIVE_INIT` policies. It does NOT remain running as a permanent supervisor or custom init.

## 6. Session & Process Model

- **Session Separation**:
  - `RuntimeSession`: Controls runtime execution and command dispatch.
  - `TerminalSession`: Manages interactive PTY terminal sessions without desktop overhead.
  - `DesktopSession`: Orchestrates graphical desktop lifecycle (Wayland socket, host hardware bridges).
- **Process Tracking**: `DefaultProcessManager` tracks host PID, guest virtual PID, logical process role, signals, and termination reasons in real time.

## 7. Native Build Pipeline

- Clean native CMake targets:
  - `libproot.so` + `libproot_loader.so`
  - `liblinuxdroid_bootstrap.so`
  - `liblinuxdroid_bridge.so`
- Automated post-build copy targets mirror newly built binaries into `assets/proot/${ANDROID_ABI}` and `jniLibs/${ANDROID_ABI}`.
- APK packages fresh binaries for both `arm64-v8a` and `x86_64`.

## 8. Test & Verification Results

- **All Unit Tests**: 100% Passed across all 16 modules.
- **Clean Builds**: `./gradlew clean test assembleDebug assembleRelease` succeeded with 0 errors.
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk` (64 MB).
- **Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk` (R8 minified & verified).

## 9. Remaining Issues

- None. All Phase 10 hardening and runtime verification requirements are complete and verified.
