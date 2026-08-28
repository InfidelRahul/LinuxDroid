# Phase 10.2: SIGSYS 31 After PRoot Loader Handoff — Investigation & Resolution

## 1. Executive Summary

- **Problem Statement**: After successful `execve` path resolution (`/usr/bin/sh`), binary parsing, interpreter discovery (`ld-linux-aarch64.so.1`), loader path resolution (`libproot_loader.so`), and load script transfer (`[LOAD_SCRIPT_OK]`), the guest process terminated with `[TRACEE_SIGNALED] pid=1026 (vpid 1): terminated with signal 31` (SIGSYS), resulting in exit code 255.
- **Investigation**:
  - Signal 31 on Linux/ARM64 is `SIGSYS` (`Bad system call`).
  - Audited PRoot's seccomp BPF filter subsystem (`native/proot/src/syscall/seccomp.c`, `native/proot/src/tracee/event.c`, `native/proot/src/arch.h`).
  - Identified two interacting root causes:
    1. **Undefined `AUDIT_ARCH_AARCH64` / BPF Fallback Kill**: In `native/proot/src/arch.h`, `AUDIT_ARCH_AARCH64` depended on `EM_AARCH64` which was not pre-defined in header order. In `seccomp.c`, `finalize_program_filter` returned `BPF_STMT(BPF_RET + BPF_K, SECCOMP_RET_KILL)`. When any architecture mismatch or modern guest syscall not in `proot_sysnums` (e.g. `rseq`, `clone3`, `epoll_pwait2`) was executed by `ld-linux-aarch64.so.1`, the kernel seccomp BPF engine executed `SECCOMP_RET_KILL`, immediately killing the process with `SIGSYS 31`.
    2. **Missing `PROOT_NO_SECCOMP=1` in Android Process Environment**: On modern Android kernels (API 28–35+), Android's zygote/platform seccomp filter interacts with child seccomp filters. Setting `PROOT_NO_SECCOMP=1` instructs PRoot to rely purely on classic `PTRACE_SYSCALL` without attaching a restrictive BPF filter that kills guest linkers.

---

## 2. Root Cause Analysis

### Signal & Mechanism
- **Signal**: Signal 31 (`SIGSYS`).
- **Origin**: Linux Kernel Seccomp BPF engine (`SECCOMP_RET_KILL` / `SYS_SECCOMP`, `si_code = 1`).
- **Failing Process**: PID 1026 (Guest tracee child process immediately after `loader.c` handoff to `ld-linux-aarch64.so.1`).
- **Failing Layer**: PRoot Seccomp BPF Accelerator Subsystem.

### Exact Chain of Failure
1. Tracee child spawned in `launch_process()` (`native/proot/src/tracee/event.c`).
2. Child called `enable_syscall_filtering()` (`native/proot/src/syscall/seccomp.c`), installing PRoot's BPF filter with `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)`.
3. Tracer successfully loaded `/usr/bin/sh` and `/lib/ld-linux-aarch64.so.1` via `libproot_loader.so` and wrote load script (`[LOAD_SCRIPT_OK]`).
4. `loader.c` performed `BRANCH(stack_pointer, entry_point)` to hand off control to `ld-linux-aarch64.so.1`.
5. `ld-linux-aarch64.so.1` executed its initialization syscalls (e.g. `rseq`, `set_robust_list`, `mprotect`).
6. PRoot's BPF filter did not have these modern syscalls in `proot_sysnums` and fell through to `finalize_program_filter()` (`SECCOMP_RET_KILL`).
7. Kernel delivered `SIGSYS 31` to the tracee, terminating it with exit code 255.

---

## 3. Surgical Code Changes

1. **Fixed Architecture Constants & Audit Macros** (`native/proot/src/arch.h`):
   - Defined `EM_AARCH64 183`, `__AUDIT_ARCH_64BIT 0x80000000`, `__AUDIT_ARCH_LE 0x40000000`, and `AUDIT_ARCH_AARCH64` explicitly so BPF architecture matching is always deterministic.
2. **Changed Seccomp Finalize to Non-Lethal Fallback** (`native/proot/src/syscall/seccomp.c`):
   - Changed `finalize_program_filter()` from `SECCOMP_RET_KILL` to `SECCOMP_RET_ALLOW`. Unhandled syscalls are passed to the kernel/tracer rather than terminating the thread with `SIGSYS`.
3. **Structured Siginfo Diagnostic Capture** (`native/proot/src/tracee/event.c`):
   - Added `ptrace(PTRACE_GETSIGINFO)` on `WIFSIGNALED` and explicit `case SIGSYS:` in `handle_tracee_event` and `handle_tracee_event_kernel_4_8`.
   - Logs `[SIGSYS_TRAPPED] pid=%d: signo=31, si_code=%d, si_syscall=%d, si_arch=0x%x, si_errno=%d`.
4. **Enabled Android Default `PROOT_NO_SECCOMP=1`** (`core/core-runtime/.../ProotRuntimeBackend.kt`):
   - Injected `PROOT_NO_SECCOMP=1` into `ProcessBuilder` environment by default for all PRoot executions on Android.

---

## 4. Verification & Test Matrix

| Test | Mode | Result | Exit Code | Notes |
|---|---|---|---|---|
| Direct `/bin/sh -c 'echo LinuxDroid_OK'` | Direct PRoot | PASS | 0 | `LinuxDroid_OK` printed |
| Direct `/bin/sh -c 'exit 42'` | Direct PRoot | PASS | 42 | Exit code 42 preserved |
| Bootstrap `/bin/sh -c 'echo LinuxDroid_OK'` | Bootstrap Handoff | PASS | 0 | `linuxdroid-bootstrap` hands off cleanly |
| Bootstrap `/bin/sh -c 'exit 42'` | Bootstrap Handoff | PASS | 42 | Exit code 42 preserved |
| RuntimeManager `/bin/sh -c 'echo LinuxDroid_OK'` | Full RuntimeSpec | PASS | 0 | `LinuxDroid_OK` printed |
| RuntimeManager `/bin/sh -c 'exit 42'` | Full RuntimeSpec | PASS | 42 | Exit code 42 preserved |

---

## 5. Android 16 ARM64 Status

- **AArch64 Pointer Normalization**: `addr & 0x00FFFFFFFFFFFFFFULL` unchanged and verified.
- **SIGSYS 31**: Eliminated.
- **Dynamic Linker Execution**: `ld-linux-aarch64.so.1` completes relocation and executes `/usr/bin/sh` without signal stops.

---

## 6. Conclusion

**PHASE 10.2 COMPLETE**
