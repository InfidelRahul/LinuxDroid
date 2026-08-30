# LinuxDroid — PRoot Runtime Boundary

## Ownership

LinuxDroid does not own the PRoot implementation. The native engine, Android compatibility changes, ARM64/Android 16 support, loader, seccomp/ptrace behavior, and native compatibility tests belong in the separate [LinuxDroid_proot repository](https://github.com/InfidelRahul/LinuxDroid_proot).

LinuxDroid consumes a versioned release from that repository:

```text
LinuxDroid_proot release
        │
        ├── arm64-v8a/proot
        ├── arm64-v8a/loader
        ├── x86_64/proot
        └── x86_64/loader
                │
                ▼
        RuntimeAssetsManager
                │
                ▼
        RuntimeLaunchPlan
```

The release metadata must include the version, source commit, ABI, Android minimum, compiler/NDK, SHA-256 values, and feature flags. LinuxDroid validates this metadata and the extracted ELF files before starting a guest process.

The `native/proot` source and bundled `jniLibs` binaries currently present in this checkout are a frozen legacy baseline. They are not a second implementation to extend and are scheduled for removal in the [migration plan](../migration-plan.md). Genuine Android JNI libraries, such as `liblinuxdroid_bridge.so`, remain under the APK's native library packaging; PRoot and its loader are executable runtime assets.

## LinuxDroid responsibilities

LinuxDroid owns the application-side runtime orchestration:

- `RuntimeAssetsManager` installs, validates, upgrades, and rolls back PRoot artifacts.
- `RootfsManager` downloads, verifies, extracts, and validates rootfs files.
- `RuntimeLaunchPlan` contains the resolved PRoot executable, loader, rootfs, environment, bindings, command, terminal mode, and graphics mode.
- `GuestEnvironmentBuilder` constructs the guest environment.
- `BindingResolver` supplies `/proc`, `/dev`, `/sys`, `/tmp`, storage, and optional Wayland bindings.
- `ProotCommandBuilder` converts a launch plan to documented CLI arguments without installing or executing anything.
- `ProcessLauncher` and `PtyLauncher` execute the same launch plan.

LinuxDroid must not implement ptrace, seccomp, syscall interception, ELF loading, or PRoot-specific workarounds. If one of those fails, the fix belongs in LinuxDroid_proot, followed by a new tested release and a LinuxDroid dependency update.

## How the runtime works

```text
Linux application
    ↓ fork/exec
LinuxDroid_proot/proot
    ↓ ptrace(PTRACE_SYSCALL, ...)
Android kernel
    ↓ intercepted syscall
PRoot rewrites paths and applies documented compatibility behavior
    ↓
Persistent Linux rootfs
```

PRoot provides the rootless `chroot`-like view without root access, kernel modules, or a custom kernel. LinuxDroid supplies the rootfs and bindings but does not make assumptions about undocumented PRoot internals.

## Documented launch shape

The command builder may produce a command of this general form, subject to the published LinuxDroid_proot contract:

```bash
<runtime-dir>/proot \
  --rootfs=<env-id>/rootfs/          # Set Linux filesystem root
  --root-id                          # Fake UID/GID as root (0)
  --cwd=<workingDirectory>           # Initial working directory
  --bind=/dev                        # Bind host /dev
  --bind=/proc                       # Bind host /proc
  --bind=/sys                        # Bind host /sys
  --bind=/dev/urandom:/dev/random    # Fix /dev/random when required
  --link2symlink                     # Emulate hard links as symlinks
  [--bind=/storage/emulated/0/LinuxDroid:/home/user/Android]
  /bin/sh                            # Guest command
```

CLI flags, exit codes, signal behavior, loader configuration, and feature support are contract data. They must be tested against the selected release instead of inferred from source code in LinuxDroid.

## Rootfs and lifecycle separation

The rootfs is independent of the PRoot artifact. Stopping or upgrading PRoot must not delete a rootfs, and rootfs installation must not construct PRoot arguments. The rootfs remains persistent across application restarts and runtime failures.

The migration sequence, including the prerequisite baseline, Android compatibility work, release system, integration, and legacy cleanup, is defined in [Updated Final Migration Plan](../migration-plan.md).
