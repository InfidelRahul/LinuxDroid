# LinuxDroid — Updated Final Migration Plan

**Status:** Proposed execution plan
**Last updated:** 2026-08-30
**Canonical PRoot repository:** [InfidelRahul/LinuxDroid_proot](https://github.com/InfidelRahul/LinuxDroid_proot)

This is the source of truth for the LinuxDroid runtime migration. It supersedes the old plan in which LinuxDroid built and shipped the PRoot implementation from its own `native/proot` module.

## Architectural boundary

`LinuxDroid_proot` is a hard prerequisite for the LinuxDroid runtime. It owns the native PRoot engine, the Android compatibility work, the loader, and native tests. LinuxDroid consumes a versioned, verified release artifact; it does not own, build, or patch PRoot source code as part of the Android application build.

```text
┌──────────────────────────┐
│   LinuxDroid_proot       │
│                          │
│ PRoot native engine      │
│ Android compatibility    │
│ ARM64 / Android 16      │
│ loader                   │
│ seccomp / ptrace         │
│ native tests             │
└────────────┬─────────────┘
             │
     versioned artifact
             │
             ▼
┌──────────────────────────┐
│       LinuxDroid         │
│                          │
│ RuntimeAssetsManager     │
│ RootfsManager            │
│ LaunchPlan               │
│ Environment              │
│ Bindings                 │
│ PTY                      │
│ Wayland                 │
│ Desktop                  │
└──────────────────────────┘
```

The existing bundled PRoot implementation is a **legacy baseline** to be recorded in Phase 0 and removed only during the later cleanup phases. It must not be treated as the target architecture, and it must not be extended while the baseline is being frozen.

## Mandatory gates

1. Phases 0–5 must be completed before LinuxDroid starts consuming the external runtime.
2. No LinuxDroid integration is allowed to compensate for a failing `LinuxDroid_proot` binary. Native failures are fixed and tested in `LinuxDroid_proot` first.
3. LinuxDroid may only consume artifacts with explicit version, commit, ABI, Android minimum, toolchain, checksum, and feature metadata.
4. The contract between the repositories is the published artifact and its documented CLI/runtime behavior, not undocumented PRoot internals.
5. The old in-repository implementation remains untouched during baseline capture and is deleted only after the replacement path has passed integration tests.

---

## Phase 0 — Freeze LinuxDroid baseline

**Goal:** Preserve the current working state before integrating the new PRoot.

- [ ] Record current LinuxDroid commit.
- [ ] Create migration branch.
- [ ] Build debug APK.
- [ ] Build release APK.
- [ ] Record current runtime behavior.
- [ ] Record current PRoot implementation.
- [ ] Record current rootfs implementation.
- [ ] Record current PTY implementation.
- [ ] Record current native packaging.
- [ ] Record current failure modes.

**Important:** Do not modify PRoot in LinuxDroid during this phase. The current `native/proot` source and `jniLibs` packaging are baseline material only.

---

## Phase 1 — LinuxDroid_proot baseline

This phase is new and must happen before the architecture migration in LinuxDroid.

**Repository:** [LinuxDroid_proot](https://github.com/InfidelRahul/LinuxDroid_proot)

**Goal:** Make the separate PRoot repository independently buildable and testable.

- [ ] Identify upstream PRoot baseline.
- [ ] Identify all existing LinuxDroid changes.
- [ ] Build LinuxDroid_proot on desktop Linux.
- [ ] Build ARM64 Android binary.
- [ ] Build loader.
- [ ] Verify ELF architecture.
- [ ] Verify executable permissions.
- [ ] Test directly on Android.
- [ ] Record failures.
- [ ] Establish reproducible build.

**Deliverable:**

```text
LinuxDroid_proot
├── proot
└── loader
```

Both artifacts must be independently executable on Android. LinuxDroid integration does not happen in this phase.

---

## Phase 2 — LinuxDroid_proot Android compatibility

**Goal:** Fix the native engine before LinuxDroid consumes it.

**Focus:** ARM64, `ptrace`, memory, `process_vm`, signals, `execve`, seccomp, ELF, loader, and Android 16.

- [ ] Audit ARM64 pointer handling.
- [ ] Audit address tagging.
- [ ] Audit ptrace memory access.
- [ ] Audit `process_vm_readv`.
- [ ] Audit `process_vm_writev`.
- [ ] Audit `PTRACE_PEEKDATA`.
- [ ] Audit `PTRACE_POKEDATA`.
- [ ] Audit syscall interception.
- [ ] Audit signal handling.
- [ ] Audit seccomp.
- [ ] Audit ELF execution.
- [ ] Audit loader.
- [ ] Test Android 16.
- [ ] Document every LinuxDroid-specific modification.

### Modification review rule

For every modification:

```text
Upstream behavior
       ↓
Termux implementation/reference
       ↓
Android behavior
       ↓
LinuxDroid requirement
       ↓
Our implementation
       ↓
Native test
```

Architecture and solutions are references, not code to merge blindly.

---

## Phase 3 — LinuxDroid_proot native test suite

Create a real compatibility suite in `LinuxDroid_proot`:

```text
LinuxDroid_proot/
└── tests/
    ├── ptrace/
    ├── memory/
    ├── syscall/
    ├── seccomp/
    ├── exec/
    ├── loader/
    ├── signals/
    └── filesystem/
```

- [ ] `/bin/true`.
- [ ] `/bin/false`.
- [ ] `/bin/echo`.
- [ ] `/bin/sh`.
- [ ] `fork`.
- [ ] `exec`.
- [ ] `wait`.
- [ ] Signals.
- [ ] Pipes.
- [ ] Files.
- [ ] Directories.
- [ ] Symlinks.
- [ ] `/proc`.
- [ ] `/dev`.
- [ ] Dynamic ELF.
- [ ] Static ELF.
- [ ] Child processes.

The suite must run against the Android artifact, not only against a host build.

---

## Phase 4 — LinuxDroid_proot diagnostic interface

Before integration, PRoot needs useful diagnostics. Create `ProotDiagnosticReport` in `LinuxDroid_proot` covering:

- Asset
- ELF
- ABI
- ptrace
- memory
- syscall
- seccomp
- loader
- exec
- signal

Instead of LinuxDroid seeing only `exit code 255`, it should eventually be able to receive a report such as:

```text
PRoot startup       PASS
ARM64                PASS
ptrace               PASS
memory               PASS
seccomp              PASS
loader               PASS
execve               PASS
guest shell          FAIL
reason: ...
```

---

## Phase 5 — LinuxDroid_proot release system

This phase starts only after the native engine works.

### Artifact structure

```text
LinuxDroid_proot release
│
├── arm64-v8a/
│   ├── proot
│   └── loader
│
└── x86_64/
    ├── proot
    └── loader
```

### Required metadata

- Version.
- Git commit.
- ABI.
- Android minimum.
- Compiler.
- NDK.
- SHA-256.
- Features.

Published paths must include `arm64-v8a/proot`, `arm64-v8a/loader`, `x86_64/proot`, and `x86_64/loader`.

Create the first usable version as `LinuxDroid_proot v0.x`.

---

## Phase 6 — LinuxDroid runtime architecture audit

This replaces the old Phase 1. Now that PRoot is external, audit LinuxDroid with this assumption:

> LinuxDroid does not own PRoot source code. It consumes a PRoot runtime.

Audit:

- [ ] `NativeBridge`.
- [ ] `ProotRuntimeBackend`.
- [ ] `ProotCommandBuilder`.
- [ ] `RuntimeValidator`.
- [ ] `RootfsBootstrapper`.
- [ ] `DistributionInstaller`.
- [ ] `EnvironmentStorage`.
- [ ] PTY.
- [ ] Asset packaging.

The audit must distinguish the legacy bundled path from the target external-artifact path.

---

## Phase 7 — Runtime Assets Manager

LinuxDroid now consumes the PRoot release through a dedicated manager:

```text
LinuxDroid_proot release
          ↓
RuntimeAssetsManager
          ↓
proot
loader
bootstrap if required
```

Create `RuntimeAssetsManager` with responsibility for:

- [ ] Defining the PRoot runtime version.
- [ ] Detecting the device ABI.
- [ ] Resolving the correct release.
- [ ] Installing/extracting executables.
- [ ] Setting executable permissions.
- [ ] Validating ELF.
- [ ] Validating architecture.
- [ ] Validating checksum.
- [ ] Detecting corruption.
- [ ] Supporting atomic installation.
- [ ] Supporting runtime upgrades.
- [ ] Supporting rollback.

---

## Phase 8 — Remove PRoot executable from JNI architecture

### Target packaging

```text
Android APK
│
├── lib/
│   └── genuine JNI libraries
│
└── assets/
    └── proot/
        └── arm64-v8a/
            ├── proot
            └── loader
```

- [ ] Remove the PRoot executable from `jniLibs`.
- [ ] Remove native-library loading hacks.
- [ ] Remove `System.loadLibrary("proot")` if it is used for executable discovery.
- [ ] Package the versioned PRoot artifact.
- [ ] Verify the APK.
- [ ] Verify runtime extraction.

`liblinuxdroid_bridge.so` and other genuine JNI libraries remain Android native libraries. PRoot and its loader are executable runtime assets, not JNI libraries.

---

## Phase 9 — Rootfs isolation

**Goal:** Make PRoot and the rootfs independent systems.

```text
RootfsManager ──────┐
                    │
                    ▼
             RuntimeLaunchPlan
                    ▲
                    │
LinuxDroid_proot ───┘
```

`RootfsManager`:

- [ ] Downloads the rootfs.
- [ ] Verifies the rootfs.
- [ ] Extracts the rootfs.
- [ ] Validates the rootfs.
- [ ] Manages rootfs lifecycle.

It does **not**:

- launch PRoot;
- construct PRoot arguments;
- configure ptrace;
- know PRoot internals.

---

## Phase 10 — Rootfs validation

- [ ] `/bin`.
- [ ] `/usr`.
- [ ] `/etc`.
- [ ] Shell.
- [ ] Dynamic loader.
- [ ] Libraries.
- [ ] Symlinks.
- [ ] Permissions.
- [ ] `/tmp`.
- [ ] `/home`.
- [ ] Architecture.
- [ ] Distribution metadata.

---

## Phase 11 — RuntimeLaunchPlan

`RuntimeLaunchPlan` remains the central LinuxDroid runtime abstraction:

```text
RuntimeLaunchPlan
├── prootVersion
├── prootExecutable
├── loader
├── rootfs
├── architecture
├── identity
├── cwd
├── environment
├── bindings
├── command
├── arguments
├── terminalMode
└── graphicsMode
```

**Important:**

```text
LinuxDroid_proot
       ↓
RuntimeAssetsManager
       ↓
RuntimeLaunchPlan.prootExecutable
```

`prootExecutable` does not come from LinuxDroid's native source tree.

---

## Phase 12 — GuestEnvironmentBuilder

- [ ] `PATH`.
- [ ] `HOME`.
- [ ] `USER`.
- [ ] `SHELL`.
- [ ] `TERM`.
- [ ] Locale.
- [ ] `TMPDIR`.
- [ ] `XDG_RUNTIME_DIR`.
- [ ] Android host variable filtering.
- [ ] User overrides.

---

## Phase 13 — BindingResolver

Create:

```text
BindingResolver
├── EssentialBindingProvider
├── RuntimeBindingProvider
├── AndroidStorageBindingProvider
└── GraphicsBindingProvider
```

Bindings include:

- `/proc`
- `/dev`
- `/sys`
- `/tmp`
- `/dev/shm`
- Android storage
- Wayland

---

## Phase 14 — ProotCommandBuilder

Keep this as a pure builder:

```text
RuntimeLaunchPlan
        ↓
ProotCommandBuilder
        ↓
List<String>
```

It does **not**:

- install files;
- execute PRoot;
- modify the filesystem;
- start a PTY;
- manage the rootfs.

It may encode only the documented `LinuxDroid_proot` CLI contract.

---

## Phase 15 — RuntimeLauncher

```text
RuntimeLauncher
├── ProcessLauncher
└── PtyLauncher
```

Both launchers consume exactly the same `RuntimeLaunchPlan`.

---

## Phase 16 — Normal process execution

```text
LinuxDroid
   ↓
RuntimeLaunchPlan
   ↓
ProotCommandBuilder
   ↓
LinuxDroid_proot/proot
   ↓
guest rootfs
   ↓
Linux program
```

- [ ] `/bin/true`.
- [ ] `/bin/echo`.
- [ ] `/bin/sh`.
- [ ] Dynamic executable.
- [ ] Exit codes.
- [ ] Standard output.
- [ ] Standard error.

---

## Phase 17 — Interactive shell / PTY

```text
RuntimeLaunchPlan
       ↓
PtyLauncher
       ↓
LinuxDroid_proot/proot
       ↓
/bin/sh
       ↓
PTY
```

- [ ] stdin.
- [ ] stdout.
- [ ] stderr.
- [ ] Resize.
- [ ] Signals.
- [ ] Terminal lifecycle.
- [ ] Shell exit.
- [ ] Restart.

---

## Phase 18 — LinuxDroid ↔ LinuxDroid_proot contract

Define exactly what LinuxDroid expects from PRoot. The contract should cover:

```text
LinuxDroid_proot contract
│
├── executable
├── loader
├── supported ABI
├── CLI semantics
├── exit-code semantics
├── signal behavior
├── feature flags
└── version
```

LinuxDroid must never depend on undocumented PRoot internals. The contract must be versioned alongside the release metadata and validated by both repositories.

---

## Phase 19 — Runtime state machine

```text
CREATED
   ↓
PROOT_READY
   ↓
ROOTFS_READY
   ↓
RUNTIME_READY
   ↓
STARTING
   ↓
RUNNING
   ↓
STOPPING
   ↓
STOPPED
```

Failure from any state transitions to `FAILED`.

```text
ANY STATE
   ↓
FAILED
```

---

## Phase 20 — Distribution abstraction

```text
DistributionManager
├── Debian
├── Ubuntu
├── Arch
└── Alpine
```

PRoot remains distribution-independent.

---

## Phase 21 — Rootfs configuration cleanup

Move runtime-specific configuration out of static rootfs images. In general, construct these at runtime:

- `PATH`
- `HOME`
- `TERM`
- `DISPLAY`
- `WAYLAND_DISPLAY`
- `XDG_RUNTIME_DIR`

---

## Phase 22 — Android storage

```text
AndroidStorageBindingProvider
        ↓
Binding
        ↓
PRoot
        ↓
guest path
```

- [ ] Read.
- [ ] Write.
- [ ] Directories.
- [ ] Large files.
- [ ] Permission failures.

---

## Phase 23 — Wayland

Only begin graphics integration after process execution, PTY, and the runtime contract are stable.

```text
LinuxDroid
    ↓
GraphicsSession
    ↓
WaylandBridge
    ↓
BindingResolver
    ↓
LinuxDroid_proot
    ↓
Linux application
```

Keep graphics completely optional.

---

## Phase 24 — Desktop session

```text
DesktopManager
       ↓
GraphicsSession
       ↓
Wayland
       ↓
Linux applications
```

---

## Phase 25 — PRoot differential maintenance

This is a cross-repository maintenance phase. Whenever upstream PRoot or Termux changes:

```text
Upstream
   │
   ├── PRoot
   └── proot-distro
          │
          ▼
LinuxDroid_proot
          │
          ▼
LinuxDroid
```

Review changes rather than merging them automatically. Classify each change:

- [ ] Bug fix.
- [ ] Android compatibility.
- [ ] Security.
- [ ] Performance.
- [ ] Architecture.
- [ ] Distribution feature.
- [ ] Not applicable.

Native fixes are made in `LinuxDroid_proot`, released and tested there, and then adopted by LinuxDroid by updating its runtime dependency.

---

## Phase 26 — Legacy LinuxDroid cleanup

Only remove the old architecture after the replacement path has passed the relevant integration gates.

- [ ] Old PRoot resolver.
- [ ] Old PRoot JNI packaging.
- [ ] Old command builder.
- [ ] Old execution path.
- [ ] Old PTY path.
- [ ] Duplicate environment logic.
- [ ] Duplicate validation.
- [ ] Dead native bridge functions.
- [ ] `native/proot` source and build integration, once no longer needed.

---

## Phase 27 — Full integration testing

```text
LinuxDroid
    ↓
RuntimeAssetsManager
    ↓
LinuxDroid_proot
    ↓
PRoot
    ↓
loader
    ↓
rootfs
    ↓
Linux
```

- [ ] Installation.
- [ ] Update.
- [ ] Rollback.
- [ ] Rootfs.
- [ ] Shell.
- [ ] Dynamic ELF.
- [ ] Static ELF.
- [ ] Filesystem.
- [ ] `/proc`.
- [ ] `/dev`.
- [ ] `/sys`.
- [ ] Storage.
- [ ] Process.
- [ ] Signals.
- [ ] PTY.
- [ ] Package manager.
- [ ] Wayland.

---

## Phase 28 — Performance

Measure before optimizing:

- [ ] PRoot startup.
- [ ] Shell startup.
- [ ] Process startup.
- [ ] Filesystem overhead.
- [ ] PTY latency.
- [ ] CPU.
- [ ] Memory.
- [ ] Rootfs initialization.

---

## Phase 29 — Recovery / resilience

- [ ] Interrupted PRoot installation.
- [ ] Corrupted PRoot binary.
- [ ] Incompatible PRoot version.
- [ ] Failed upgrade.
- [ ] Rollback.
- [ ] Corrupted rootfs.
- [ ] Interrupted rootfs extraction.
- [ ] PRoot crash.
- [ ] Guest process crash.
- [ ] PTY disconnect.

---

## Phase 30 — Final architecture verification

### Final dependency graph

```text
GitHub
                           │
             ┌─────────────┴─────────────┐
             │                           │
             ▼                           ▼
    LinuxDroid_proot                 LinuxDroid
             │                           │
             │                           │
       Native engine              Android application
             │                           │
       ┌─────┴─────┐                     │
       │           │                     │
    PRoot        Loader                  │
       │           │                     │
       └─────┬─────┘                     │
             │                           │
       versioned release                 │
             │                           │
             └──────────────► RuntimeAssetsManager
                                      │
                                      ▼
                              RuntimeLaunchPlan
                                      │
                           ┌──────────┴──────────┐
                           ▼                     ▼
                    ProcessLauncher         PtyLauncher
                           │                     │
                           └──────────┬──────────┘
                                      ▼
                              LinuxDroid_proot
                                      │
                                      ▼
                                  Linux rootfs
                                      │
                                      ▼
                                  Linux apps
                                      │
                                      ▼
                                   Wayland
```

### Final execution order

The actual migration sequence is:

```text
01  LinuxDroid baseline
        ↓
02  LinuxDroid_proot baseline
        ↓
03  Android/ARM64 PRoot fixes
        ↓
04  PRoot native tests
        ↓
05  PRoot diagnostics
        ↓
06  PRoot release artifacts
        ↓
07  LinuxDroid architecture audit
        ↓
08  RuntimeAssetsManager
        ↓
09  Remove PRoot-from-JNI architecture
        ↓
10  Rootfs isolation
        ↓
11  Rootfs validation
        ↓
12  RuntimeLaunchPlan
        ↓
13  GuestEnvironmentBuilder
        ↓
14  BindingResolver
        ↓
15  ProotCommandBuilder
        ↓
16  RuntimeLauncher
        ↓
17  Normal execution
        ↓
18  PTY
        ↓
19  LinuxDroid ↔ PRoot contract
        ↓
20  Runtime state
        ↓
21  Distribution abstraction
        ↓
22  Rootfs cleanup
        ↓
23  Android storage
        ↓
24  Wayland
        ↓
25  Desktop
        ↓
26  Differential maintenance
        ↓
27  Legacy cleanup
        ↓
28  Integration testing
        ↓
29  Performance
        ↓
30  Recovery + final audit
```

## Critical architectural rule

> From this point onward, LinuxDroid is a consumer of LinuxDroid_proot, not the owner of the PRoot implementation.

If ptrace, seccomp, ARM64, loader, syscall interception, or another native problem needs fixing, fix it in `LinuxDroid_proot` first, publish and test a new runtime artifact, then update LinuxDroid's dependency. This keeps the two projects clean and makes the PRoot fork independently maintainable.
