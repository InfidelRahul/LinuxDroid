# LinuxDroid — Rootless Runtime Documentation

## Overview

LinuxDroid uses **proot** to provide a rootless Linux userspace on Android.

proot is a user-space implementation of `chroot`, `mount --bind`, and `binfmt_misc`
using `ptrace(2)`. It requires no root access, no kernel modules, and no custom kernel.

**Homepage**: https://proot-me.github.io  
**Upstream source**: https://github.com/proot-me/proot
**LinuxDroid runtime**: [InfidelRahul/LinuxDroid_proot](https://github.com/InfidelRahul/LinuxDroid_proot)

LinuxDroid consumes the separately maintained LinuxDroid_proot release for Android. The fork owns the PRoot native engine, loader, Android compatibility work, and native tests. LinuxDroid owns runtime asset installation/validation, rootfs lifecycle, launch plans, bindings, PTY, and graphics orchestration; it does not build or patch PRoot as part of the application repository.

The old bundled PRoot implementation in this repository is retained only as a frozen migration baseline. See the [Updated Final Migration Plan](../migration-plan.md) for the external-artifact migration and removal gates.

---

## How proot Works

```
Linux application
    ↓ fork/exec
proot (ptrace parent)
    ↓ ptrace(PTRACE_SYSCALL, ...)
Kernel (standard Android kernel)
    ↓ intercept syscall
proot rewrites path arguments (e.g. /etc/passwd → <rootfs>/etc/passwd)
    ↓
Kernel executes with translated paths
    ↓ syscall return
proot rewrites return values if needed
    ↓
Linux application sees correct Linux-root-relative paths
```

Key syscalls intercepted:
- `open`, `openat`, `stat`, `lstat`, `access` — path rewriting
- `execve`, `execveat` — binary loader integration
- `getuid`, `getgid`, `geteuid`, `getegid` — fake root (returns 0)
- `mount`, `umount` — emulated via path rewriting (bind mounts)
- `chroot` — redirected to rootfs

---

## proot Command Line

LinuxDroid launches proot with the following arguments:

```bash
proot \
  --rootfs=<env-id>/rootfs/          # Set Linux filesystem root
  --root-id                          # Fake UID/GID as root (0)
  --cwd=<workingDirectory>           # Initial working directory
  --bind=/dev                        # Bind host /dev
  --bind=/proc                       # Bind host /proc
  --bind=/sys                        # Bind host /sys
  --bind=/dev/urandom:/dev/random    # Fix /dev/random
  --link2symlink                     # Emulate hard links as symlinks
  [--bind=/storage/emulated/0/LinuxDroid:/home/user/Android]  # Shared storage (when authorized)
  /bin/sh                            # Command to run inside Linux
```

---

## Initial Distribution: Debian arm64

**Chosen for:**
- Official ARM64 (aarch64) support in Debian repositories
- Minimal rootfs available (~300MB base)
- Full `apt` package ecosystem (50,000+ packages)
- Native Wayland, XFCE4, GNOME packages
- No licensing restrictions
- Excellent documentation and community support
- Proven proot compatibility in rootless mobile Linux environments

**Bootstrap method:**
The Debian rootfs is bootstrapped using a pre-built minimal tarball from
Debian's official distribution infrastructure, then decompressed into
`environments/<env-id>/rootfs/`.

---

## Rootfs Bootstrap Process

1. Download official Debian arm64 minimal tarball
2. Verify SHA256 checksum
3. Extract to `environments/<env-id>/rootfs/`
4. Run initial proot setup script (configure DNS, create user, etc.)
5. Mark environment as `READY`

The rootfs is never recreated after this initial bootstrap.

---

## Persistence Guarantee

The `rootfs/` directory is NEVER deleted by any LinuxDroid code path.

Code-reviewed invariants:
- `ProotRuntimeBackend.stop()` — does NOT delete rootfs
- `ProotRuntimeBackend.cleanup()` — does NOT delete rootfs
- `EnvironmentStorage` — only `cleanRuntimeState()` deletes files, which touches `runtime-state/` only
- No code in LinuxDroid calls `deleteRecursively()` on the rootfs path

---

## Limitations

| Limitation | Impact | Workaround |
|-----------|--------|------------|
| ptrace overhead | ~5-15% CPU overhead | Acceptable for interactive use |
| No network namespaces | Linux shares Android network | Uses Android DNS, standard networking |
| No kernel modules | Can't load custom modules | Use packages that don't need modules |
| No `/dev/kvm` | No nested VMs | Not a supported use case |
| Some syscalls not fully emulated | Rare edge cases | Report to proot issue tracker |
| Android may kill process (OOM) | Session may terminate | Foreground service + persistent filesystem |

---

## Android Lifecycle Integration

The Linux session is kept alive by `LinuxSessionService` (foreground service).

```
Android Activity destroyed
    ≠
Linux session destroyed

Android app backgrounded
    ≠
Linux filesystem modified

Only explicit STOP or Android OOM killer
    =
Session termination
```

After session termination, the rootfs is intact and the session can be restarted.
