# Phase 10.2: Differential PRoot Runtime Debugging & Fix

## 1. Executive Summary

- **Investigation**: Following successful extraction of guest execution path (`/usr/bin/sh`), the runtime exited with code 255 on target devices.
- **Root Cause Identified**:
  1. **Stale Errno in Diagnostics**: `note(..., INFO, SYSTEM, ...)` in `note.c` invoked `perror(NULL)`, appending leftover `errno` values (such as `EINVAL` or `ENOENT` from previous fallbacks) to success messages (e.g. `execve path successfully read: '/usr/bin/sh': Invalid argument`).
  2. **Rootfs Dynamic Linker Execution Permissions**: When rootfs tar archives are unpacked on Android's internal storage, dynamic linkers (`/lib/ld-linux-aarch64.so.1`, `/lib/ld-musl-aarch64.so.1`, `/lib64/ld-linux-x86-64.so.2`) and versioned libraries (`libc.so.6`) ending in `.so.1`/`.so.2`/`.so.6` were not granted execute bits if the tar header lacked `0111`. In `translate_and_check_exec()`, `access(host_path, X_OK)` failed with `EACCES` (`13`), causing PRoot to cancel `execve` via `PR_void` and exit with code 255.
  3. **Silent Kernel Execve Failures**: In `translate_execve_exit()`, `(int)syscall_result < 0` returned silently without logging the underlying error code.
- **Resolution**:
  1. Replaced `SYSTEM` with `INTERNAL` in all non-failure logging across `enter.c`, `exit.c`, and `event.c` to prevent stale `errno` pollution.
  2. Enhanced `RootfsBootstrapper.kt` extraction rules to enforce executable bits (`0755`) on all binaries, dynamic linkers, and shared libraries across `bin/`, `sbin/`, `lib/`, `libexec/`, `.so`, and `.so.*`.
  3. Added structured diagnostic logs with immediate `saved_errno` across every stage of the `execve` chain.

---

## 2. Failing Function & Source Files

| Defect Area | Source File | Function | Concrete Fix |
|---|---|---|---|
| Stale Errno Logging | `native/proot/src/execve/enter.c` | `translate_execve_enter` | Used `INTERNAL` logging and explicit `saved_errno` |
| Linker Execution Permission | `linux/bootstrap/.../RootfsBootstrapper.kt` | `extractTarArchive` | Enforced `X_OK` on `lib/`, `libexec/`, `.so`, `.so.*` |
| Kernel Execve Diagnostics | `native/proot/src/execve/exit.c` | `translate_execve_exit` | Added `[EXECVE_KERNEL_FAIL]` logging with `saved_err` |
| Tracee Lifecycle Diagnostics | `native/proot/src/tracee/event.c` | `handle_tracee_event` | Added structured `[TRACEE_EXIT]` and `[TRACEE_SIGNALED]` logging |

---

## 3. Differential Comparison Table

| Component | Working Standard | LinuxDroid | Status / Fix |
|---|---|---|---|
| PRoot Engine | v5.4.0 (AArch64 / x86_64) | v5.4.0 with AArch64 tagged pointer normalization | Intact & Verified |
| Address Mask | `addr & 0x00FFFFFFFFFFFFFFULL` | Applied to `mem.c` & `syscall.c` (top byte untagging) | Verified |
| Register Offsets | Raw struct user offsets | Untouched (no erroneous masking in PEEKUSER/POKEUSER) | Verified |
| Loader | `libproot_loader.so` (static) | `libproot_loader.so` in `nativeLibraryDir` via `PROOT_LOADER` | Verified |
| Rootfs Linkers | Executable (`0755`) | Enforced `0755` on `ld-linux-aarch64.so.1`, `ld-musl-aarch64.so.1` | **FIXED** |
| Diagnostic Output | Pure error reporting | Non-stale, structured logs with immediate `saved_errno` | **FIXED** |

---

## 4. Test Matrix Verification

| Test | Stage | Result | Exit Code | Notes |
|---|---|---|---|---|
| Direct `/bin/sh -c 'echo LinuxDroid_OK'` | Direct PRoot | PASS | 0 | Outputs `LinuxDroid_OK\n` |
| Direct `/bin/sh -c 'exit 42'` | Direct PRoot | PASS | 42 | Exit code 42 preserved |
| Bootstrap `/bin/sh -c 'echo LinuxDroid_OK'` | Bootstrap Handoff | PASS | 0 | `linuxdroid-bootstrap` hands off to `/bin/sh` |
| Bootstrap `/bin/sh -c 'exit 42'` | Bootstrap Handoff | PASS | 42 | Exit code 42 preserved |
| LinuxDroid Runtime `/bin/sh` | RuntimeManager | PASS | 0 | Full `RuntimeSpec` execution |
| LinuxDroid Runtime `exit 42` | RuntimeManager | PASS | 42 | Full `RuntimeSpec` execution |

---

## 5. Android 16 Verification

- **AArch64 Pointer Tagging**: Memory addresses containing top-byte tags (e.g. `0xb4000078de2db6f0`) remain properly normalized to canonical addresses (`0x00000078de2db6f0`).
- **Bad Address Elimination**: No `Bad address` errors encountered in `ptrace(PEEKDATA)` or `process_vm_readv`.

---

## 6. Final Status

**PHASE 10.2 COMPLETE**
