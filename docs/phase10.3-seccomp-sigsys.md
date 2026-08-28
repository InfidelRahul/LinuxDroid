# Phase 10.3: Seccomp Syscall 99 & SIGSYS Emulation — Investigation & Final Resolution

## 1. Executive Summary

- **Evidence**: On Android 16 ARM64, the tracee successfully completed `execve` path resolution (`/usr/bin/sh`), binary parsing, interpreter discovery (`ld-linux-aarch64.so.1`), loader path resolution (`libproot_loader.so`), and load script transfer (`[LOAD_SCRIPT_OK]`), but subsequently stopped on `[SIGSYS_TRAPPED] signo=31, si_code=1 (SYS_SECCOMP), si_syscall=99, si_arch=0xc00000b7`.
- **Root Cause Analysis**:
  1. **Syscall 99 on ARM64**: In the Linux ARM64 system call table (`include/uapi/asm-generic/unistd.h`), syscall 99 is `set_robust_list` (and on 32-bit/compat tables, `setxattr`). Both `set_robust_list` and `setxattr` are invoked during early userspace startup (`ld-linux-aarch64.so.1` / `libc.so.6`).
  2. **Filter Origin**: `si_code = 1 (SYS_SECCOMP)` is generated exclusively by `SECCOMP_RET_TRAP`. In the Android sandbox, Android Zygote / platform seccomp policy marks restricted/disallowed syscalls with `SECCOMP_RET_TRAP` for app processes.
  3. **Signal Forwarding Defect in PRoot**: When the kernel delivered `SIGSYS` to the tracee via `SECCOMP_RET_TRAP`, PRoot caught `SIGSYS` in `event.c` and forwarded `signal = 31` directly back to the tracee via `restart_tracee()`. Because user-space programs do not install custom `SIGSYS` signal handlers, the kernel killed the tracee with `[TRACEE_SIGNALED] signal=31` (exit 255).
- **Resolution**:
  1. Implemented automatic syscall emulation for trapped seccomp signals in `native/proot/src/tracee/event.c`. When `siginfo.si_code == 1` (`SYS_SECCOMP`), PRoot intercepts the trap, sets `poke_reg(tracee, SYSARG_RESULT, (word_t)-ENOSYS)`, pushes registers, logs `[SECCOMP_EMULATED]`, and suppresses the deadly `SIGSYS` signal (`signal = 0`).
  2. Added `PR_set_robust_list` and `PR_get_robust_list` to `proot_sysnums` in `native/proot/src/syscall/seccomp.c`.
  3. Ensured `PROOT_NO_SECCOMP=1` is injected into the execution environment by default in `ProotRuntimeBackend.kt`.

---

## 2. Syscall 99 Breakdown

| Architecture | Syscall Number | Syscall Name | Calling Component |
|---|---|---|---|
| AArch64 (ARM64) | 99 (`0x63`) | `set_robust_list` | `ld-linux-aarch64.so.1` (glibc robust futex initialization) |
| ARM (32-bit EABI) | 99 (`0x63`) | `statfs` | `glibc` filesystem queries |
| x86_64 | 99 (`0x63`) | `sysinfo` | `glibc` system queries |
| Generic / xattr | 5 / 226 | `setxattr` | `coreutils` / filesystem tools |

---

## 3. Filter Owner & Installation Point

- **Filter Owner**: Android Platform / Zygote Sandbox (`bionic/libc/seccomp/seccomp_policy.cpp`).
- **Trigger**: Android app sandbox returns `SECCOMP_RET_TRAP` for restricted or sandbox-blocked system calls.
- **Handling Point in LinuxDroid**: `native/proot/src/tracee/event.c` in `handle_tracee_event()` / `handle_tracee_event_kernel_4_8()`.

---

## 4. Code Changes

| Source File | Function | Change Description |
|---|---|---|
| `native/proot/src/tracee/event.c` | `handle_tracee_event_kernel_4_8` | Intercepts `SIGSYS` (`SYS_SECCOMP`), sets return value `-ENOSYS`, suppresses signal (`signal = 0`), and logs `[SECCOMP_EMULATED]` |
| `native/proot/src/tracee/event.c` | `handle_tracee_event` | Intercepts `SIGSYS` (`SYS_SECCOMP`) in pre-4.8 handler |
| `native/proot/src/syscall/seccomp.c` | `proot_sysnums` | Added `PR_set_robust_list` and `PR_get_robust_list` |
| `core/core-runtime/.../ProotRuntimeBackend.kt` | `start()` | Sets `PROOT_NO_SECCOMP=1` by default |

---

## 5. Security & Stability Assessment

- **Rootless Compatibility**: No root permissions, kernel patches, or hidden capabilities are required.
- **Android Sandbox Preserved**: Intercepting `SIGSYS` and returning `-ENOSYS` to guest Linux binaries is the standard POSIX error response for unimplemented kernel features, allowing Linux software to gracefully fall back or proceed without process termination.

---

## 6. Test Verification Matrix

| Test | Mode | Result | Exit Code | Notes |
|---|---|---|---|---|
| `/bin/true` | Direct & Runtime | PASS | 0 | Exit 0 |
| `/bin/false` | Direct & Runtime | PASS | 1 | Exit 1 |
| `/bin/echo LinuxDroid_OK` | Direct & Runtime | PASS | 0 | `LinuxDroid_OK` output |
| `/bin/sh -c 'echo LinuxDroid_OK'` | Direct & Runtime | PASS | 0 | `LinuxDroid_OK` output |
| `/bin/sh -c 'exit 42'` | Direct & Runtime | PASS | 42 | Exit 42 preserved |

---

## 7. Android 16 ARM64 Verification

- **AArch64 Tagged Pointer Normalization**: Verified (`addr & 0x00FFFFFFFFFFFFFFULL`).
- **ELF & Interpreter Resolution**: Verified.
- **Loader Handoff (`LOAD_SCRIPT_OK`)**: Verified.
- **Seccomp Traps**: Successfully emulated with `-ENOSYS` without SIGSYS termination.

---

## 8. Final Status

**PHASE 10.3 COMPLETE**
