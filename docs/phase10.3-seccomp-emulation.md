# Phase 10.3: Seccomp Trapped Syscall Emulation & Post-rseq Lifecycle Analysis

## 1. Current Architecture

```
Guest Process (/usr/bin/sh / glibc)
          │
          ▼  Issues syscall (e.g. set_robust_list / rseq)
Linux Kernel (AArch64)
          │
          ▼  Android App Sandbox Seccomp Filter returns SECCOMP_RET_TRAP
Kernel delivers SIGSYS (si_code = 1 / SYS_SECCOMP) to Tracee
          │
          ▼  ptrace event intercepted by PRoot
PRoot Tracer (native/proot/src/tracee/event.c: handle_tracee_event)
          │
          ├─► Identifies si_signo=31, si_code=1, si_syscall (99, 293)
          ├─► Fetches registers via fetch_regs()
          ├─► Sets return value: poke_reg(tracee, SYSARG_RESULT, (word_t)-ENOSYS) [x0 = -ENOSYS]
          ├─► Commits registers via push_regs()
          ├─► Suppresses lethal signal (signal = 0)
          └─► Logs [SECCOMP_EMULATED]
          │
          ▼  Resumes tracee via PTRACE_SYSCALL / PTRACE_CONT
Guest Process continues execution with errno = ENOSYS
```

---

## 2. Syscalls Observed & Verified (AArch64 / `0xc00000b7`)

| Number | Name | Caller | Action | Emulated Return | Behavior on ENOSYS |
|---:|---|---|---|---|---|
| **99** | `set_robust_list` | `ld-linux-aarch64.so.1` / `libc.so.6` | Thread/process initialization | `-ENOSYS` (`-38`) | glibc disables robust futex recovery and continues normally |
| **293** | `rseq` | `ld-linux-aarch64.so.1` (glibc 2.35+) | Restartable Sequences initialization | `-ENOSYS` (`-38`) | glibc sets `__rseq_size = 0` / disables rseq and continues normally |

---

## 3. Register Return Value & Resume Verification

1. **AArch64 Return Register**:
   - Return register for system calls is `x0` (`SYSARG_RESULT`).
   - `poke_reg(tracee, SYSARG_RESULT, (word_t)-ENOSYS)` updates `x0` to `-38`.
   - `push_regs(tracee)` writes the register array back to the kernel.
2. **Instruction Pointer (`pc`)**:
   - Under Linux kernel `seccomp_send_sigsys()` for `SECCOMP_RET_TRAP`, `pc` is already at the instruction following the trapped `svc #0` (instruction address + 4).
   - Resuming with `signal = 0` allows the tracee to continue at `pc` with `x0 = -ENOSYS`.
3. **No Infinite Looping**:
   - Per-syscall counts confirm that each trapped call occurs once during glibc initialization (`set_robust_list: 1`, `rseq: 1`) and does not loop.

---

## 4. Execution Test Matrix

| Command | SIGSYS Trapped | Trapped Syscalls | Output | Exit Code | Result |
|---|---|---|---|---:|---|
| `/bin/true` | Intercepted & Emulated | 99, 293 | *(none)* | `0` | **PASS** |
| `/bin/false` | Intercepted & Emulated | 99, 293 | *(none)* | `1` | **PASS** |
| `/bin/echo LinuxDroid_OK` | Intercepted & Emulated | 99, 293 | `LinuxDroid_OK` | `0` | **PASS** |
| `/bin/sh -c 'echo LinuxDroid_OK'` | Intercepted & Emulated | 99, 293 | `LinuxDroid_OK` | `0` | **PASS** |
| `/bin/sh -c 'exit 42'` | Intercepted & Emulated | 99, 293 | *(none)* | `42` | **PASS** |

---

## 5. Security & Isolation

- **Rootless Operation**: Fully rootless, running inside standard Android app sandbox without elevated permissions.
- **Seccomp Integrity**: No global seccomp bypass. Trapped syscalls receive standard POSIX `-ENOSYS` errors, exactly matching behavior on kernels where those syscalls are not implemented.

---

## 6. Final Status

**PHASE 10.3 COMPLETE**
