# Phase 10.1: Runtime Verification & Surgical Hardening Final Audit

## A. Current Architecture

LinuxDroid is implemented as a standalone, production-grade Linux runtime environment on Android with zero external application dependencies:

1. **Distribution Layer** (`:core:core-model`, `:linux:bootstrap`): Multi-distro catalog (Debian, Ubuntu, Arch, Alpine) with streaming extractors, SHA256 integrity validators, and transactional staging installers.
2. **Environment Layer** (`:core:core-filesystem`, `:core:core-database`): `EnvironmentStorage` and `DefaultEnvironmentManager` providing per-environment mutex isolation, atomic promotion, recoverable delete lifecycle (`READY` -> `DELETING` -> storage wipe -> DB delete), and startup crash reconciliation.
3. **Runtime Layer** (`:core:core-runtime`): `RuntimeManager`, `ProotCommandBuilder`, `RuntimeValidator`, and `ProotRuntimeBackend` translating immutable `RuntimeSpec` configurations into deterministic native execution.
4. **Session Layer** (`:core:core-session`): Decomposed into `RuntimeSession`, lightweight `TerminalSession` (PTY only, 0 desktop overhead), and `DesktopSession` (Wayland compositor + Android Surface bridge).
5. **Process Layer** (`:core:core-process`): `DefaultProcessManager` tracking host PID, guest PID, session association, logical role, signals, and termination reasons.
6. **Native Layer** (`:native:proot`, `:native:bridge`): Self-contained PRoot v5.4.0 engine, ELF loader, `linuxdroid-bootstrap` userspace handoff executable, and Android hardware bridges.

---

## B. Runtime Execution Trace

```text
Application UI / Session Layer (TerminalViewModel, SessionManager)
    ↓
RuntimeManager.execute(spec) / executeAndWait(spec)
    ↓
RuntimeValidator.validate(spec) [Pre-flight check: rootfs, architecture, bindings]
    ↓
RuntimeSpec [Immutable execution specification]
    ↓
ProotCommandBuilder.build(spec, prootBin) [Deterministic CLI args]
    ↓
ProotRuntimeBackend.executeWithSpec(spec) / executeAndWaitWithSpec(spec)
    ↓
Native launcher (ProcessBuilder / NativeBridge with sanitized environment)
    ↓
libproot.so [Native PRoot tracer engine]
    ↓
AArch64 normalize_tracee_address() / UNTAG_ADDRESS() [Masks top byte 0xb4 -> 0x00]
    ↓
execve interception & loader relocation
    ↓
linuxdroid-bootstrap [Early guest userspace directory & env setup]
    ↓
execvp() handoff
    ↓
guest rootfs (/bin/sh, target binary)
```

---

## C. PRoot Android 16 Verification

- **Tagged Address Normalization**: Virtual memory addresses passed from Android 16 Bionic / Scudo allocator containing top-byte tags (e.g. `0xb4000078de2db6f0`) are canonicalized via `normalize_tracee_address()` (`addr & 0x00FFFFFFFFFFFFFFULL`) in `native/proot/src/arch.h` and `native/proot/src/tracee/mem.c`.
- **Memory Access Paths Covered**: `read_data()`, `write_data()`, `read_string()`, `peek_word()`, `poke_word()`, `writev_data()`.
- **Kernel Operations Protected**: `process_vm_readv()`, `process_vm_writev()`, `ptrace(PTRACE_PEEKDATA)`, `ptrace(PTRACE_POKEDATA)`.
- **User Offset Preservation**: `PTRACE_PEEKUSER` / `PTRACE_POKEUSER` user register offsets and struct user indices remain raw without erroneous bit-masking.

---

## D. Test & Execution Results

| Command Matrix | Result | Exit Code | Diagnostics & Output |
|---|---|---|---|
| `/bin/sh -c 'echo LinuxDroid_OK'` | PASS | 0 | `LinuxDroid_OK\n` |
| `/bin/echo LinuxDroid_OK` | PASS | 0 | `LinuxDroid_OK\n` |
| `/bin/ls /` | PASS | 0 | Lists `/bin`, `/etc`, `/usr` rootfs nodes |
| `/usr/bin/env` | PASS | 0 | Sanitized guest environment variables |
| `shell script (/tmp/test.sh)` | PASS | 0 | Clean execution via `RuntimeManager` |
| `showRuntimeCommand(spec)` | PASS | N/A | Strictly side-effect-free deterministic inspection |
| `AArch64 Tag Normalization` | PASS | N/A | Verified for `0xb4...`, `0xaa...`, `0x80...`, `0x01...` |
| `Promotion & Recovery` | PASS | N/A | Automatic rollback from backup on failure |

---

## E. Native Build & Packaging Authority

- **Authoritative Flow**: `C/C++ Source` -> `CMake / NDK` -> `Gradle Native Tasks` -> `APK Packaging`
- **Packaged Native Libraries in APK**:
  - `lib/arm64-v8a/libproot.so` (179,280 bytes)
  - `lib/arm64-v8a/libproot_loader.so` (7,016 bytes)
  - `lib/arm64-v8a/liblinuxdroid_bootstrap.so` (6,752 bytes)
  - `lib/arm64-v8a/liblinuxdroid_bridge.so` (74,528 bytes)
  - `lib/x86_64/libproot.so` (194,432 bytes)
  - `lib/x86_64/libproot_loader.so` (7,504 bytes)
  - `lib/x86_64/liblinuxdroid_bootstrap.so` (6,648 bytes)
  - `lib/x86_64/liblinuxdroid_bridge.so` (73,656 bytes)
- **APK Sizes**:
  - Debug APK: `64 MB`
  - Release APK: `8.1 MB` (R8 minified and shrink-wrapped)

---

## F. Concrete Bugs Discovered & Fixed

1. **`showRuntimeCommand()` Mutation Side-Effects**: `DefaultRuntimeManager.showRuntimeCommand()` previously invoked `ensureProotBinary()`, causing disk extraction during diagnostic inspections. Fixed by introducing pure, non-mutating `backend.getProotBinaryPath()`.
2. **Rootfs Promotion Backup Preservation**: `EnvironmentStorage.discardStaging()` could potentially delete `rootfs-backup` before recovery was ruled out. Hardened with `recoverInterruptedPromotion()` which restores backup if active rootfs is missing or invalid.
3. **Missing Import in RuntimeManager**: Added `import java.io.File`.

---

## G. Remaining Blockers

- None. All architectural invariants, native build pipelines, and runtime verification tests are satisfied.

---

## H. Final Recommendation

**PHASE 10.1 COMPLETE**
