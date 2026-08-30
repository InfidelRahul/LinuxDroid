# LinuxDroid → LinuxDroid_proot Migration Progress

> Working record of the PRoot ownership migration. The canonical plan is
> [docs/migration-plan.md](migration-plan.md). This file tracks *what was done*,
> *what was verified*, and *what remains blocked* by the execution environment.

**Last updated:** 2026-08-30

---

## Environment constraints (execute this in the same sandbox as the work)

The migration agent ran inside a sandbox with the following properties:

| Capability | Available |
|------------|-----------|
| `gcc`, `make`, `git`, `gh` | ✅ |
| Network to `github.com` | ✅ |
| Java / JDK | ❌ (no `java`, no JDK provider) |
| Android SDK / NDK | ❌ (`local.properties` points to `/workspaces/android-sdk`, absent) |
| `adb` / physical or emulated device | ❌ |
| Gradle (even offline) | ❌ (requires JDK + Android SDK) |

Consequence:

* `./gradlew assembleDebug / test` **cannot run** here (no JDK, no Android SDK).
* The Android ARM64 cross-build of LinuxDroid_proot **cannot run** here (no NDK).
* On-device / PTY / integration tests **cannot run** here (no device).

The LinuxDroid_proot **host** build (`make proot`, `make loader`, `make selftest`,
`make test`-style suites) **can and did run**, and is the verification gate used
for the external engine below.

---

## Baselines (Phase A / Phase 0)

| Repository | Commit | Branch | Remote |
|------------|--------|--------|--------|
| **InfidelRahul/LinuxDroid** | `1ffffa0731d8bc21b4d50173813bda090125b9f3` | `arena/01a051a5-linuxdroid` | https://github.com/InfidelRahul/LinuxDroid.git |
| **InfidelRahul/LinuxDroid_proot** | `29aaf193bfe0fbc932f9d50a0acf745993726e56` | `master` | https://github.com/InfidelRahul/LinuxDroid_proot.git |

LinuxDroid is a consumer of LinuxDroid_proot. LinuxDroid_proot `lib/uthash` is
registered as a git submodule at `e493aa90…` (present in the clone).

---

## LinuxDroid_proot verification (host) — Phase C (partial)

| Check | Result |
|-------|--------|
| `make proot` | ✅ (desktop host, `build/host/proot`) |
| `make loader` | ✅ (`build/host/loader.bin`) |
| `make selftest` | ✅ |
| `build/host/proot --version` | ✅ prints `-29aaf193`, `process_vm = yes, seccomp_filter = yes` |
| `build/host/linuxdroid-selftest` | ✅ `result: PASS` — 13 pass / 0 fail / 0 skip |
| ELF architecture of `build/host/proot` | ✅ x86_64 (host) |
| `make release` | ✅ produced `build/release/MANIFEST.txt` |

**Recorded host release identity (C11, host ABI only):**

```
LinuxDroid-PRoot dev
commit: 29aaf19
arch:   host (x86_64)
cc:     gcc
built:  2026-08-30
sha256:
  proot:   236d2c5455b492d238153f94d1e3dc34a988af2f3b29e0bf764a34a03154231a
  loader:  d09da5cbe8919e76439550de972a7b9677375744c76c02f233baa99561fb43e4
min-android: 16+
```

> **BLOCKED:** `make android-arm64` (C01–C04) requires the Android NDK, which is
> not available in this sandbox. The ARM64 `proot`/`loader` binary ELF
> verification (C03/C04) and on-device execution (C05, C07–C10) cannot be
> performed here. They must run in CI / on a device before Phase K cleanup.

---

## What was implemented in LinuxDroid (source-level)

The runtime now consumes PRoot through a dedicated asset layer, matching the
target architecture:

```
LinuxDroid_proot release
        │
        ▼
RuntimeAssetsManager   (new — owns PRoot artifact lifecycle)
        │
        ▼
ProotRuntimeBackend    (delegates resolution; process/PTY unchanged)
        │
        ▼
ProotCommandBuilder    (pure RuntimeSpec -> argv; no filesystem discovery)
```

### Files

| File | Change | Rationale |
|------|--------|-----------|
| `core/core-runtime/src/main/kotlin/com/linuxdroid/core/runtime/RuntimeAssetsManager.kt` | **new** | ABI selection, asset identity (`proot`/`loader`), private install path, existence/executable/ELF/checksum validation, atomic `.part` → rename install, version compatibility (E01–E13, D01–D05). |
| `core/core-runtime/src/main/kotlin/com/linuxdroid/core/runtime/ProotRuntimeBackend.kt` | refactor | `ensureProotBinary()` / `ensureLoaderBinary()` / `getProotBinaryPath()` now resolve through `RuntimeAssetsManager`; removed backend-owned `resolveOrExtractRuntimeBinaries()` (H02–H06). Process launch, PTY, env handling unchanged (H07–H09). Shared-storage discovery moved out of the command builder into `withSharedStorage()` (preserves behavior). |
| `core/core-runtime/src/main/kotlin/com/linuxdroid/core/runtime/ProotCommandBuilder.kt` | refactor | Removed `android.os.Environment` shared-storage discovery. Builder is now strictly `RuntimeSpec` → argv (G01–G07). `executableOverride` supplies the already-resolved PRoot path. |
| `app/src/main/kotlin/com/linuxdroid/app/di/AppModule.kt` | refactor | Provides `RuntimeAssetsManager` as a singleton and injects it into `ProotRuntimeBackend`. |
| `core/core-runtime/src/test/.../RuntimeSpecAndCommandBuilderTest.kt` | extend | New test proving the builder performs no Android storage discovery (G02/G08). |
| `core/core-runtime/src/test/.../RuntimeAssetsManagerTest.kt` | **new** | Unit tests for manifest parsing, version compatibility, semantic-version ordering, and SHA-256 (Context-free paths). |
| `core/core-runtime/.../RuntimeLauncher.kt` | **new** | Separates launch mechanics (`launchProcess` / `launchPty`) from backend orchestration (I01–I05). |
| `core/core-runtime/.../ProotRuntimeBackend.kt` | refactor | Launch now delegated to `RuntimeLauncher`; removed backend-owned command construction (`commandBuilder`) and `NativeBridge` PTY usage (I02, I05). |
| `core/core-runtime/src/test/.../RuntimeLauncherTest.kt` | **new** | Process-launch test using a host binary (defensive guard on availability). |
| `docs/migration-progress.md` | **new** | This record. |

---

## Task status summary (migration spec IDs)

### VERIFIED (verified here)

| Task | Result |
|------|--------|
| A01–A04 Repository discovery & baselines | PASS (recorded above) |
| A05 LinuxDroid baseline build | **BLOCKED** (no JDK/SDK) — see below |
| A06 LinuxDroid_proot baseline (host) | PASS (`make proot`, `loader`, `selftest`) |
| B01–B08 Locate embedded PRoot | PASS (source audit) |
| C06 LinuxDroid_proot self-test (host) | PASS (23→13 results: 13 pass, 0 fail) |
| C11 Release identity (host ABI) | PASS (recorded above) |
| D01–D05 Artifact identity/paths/metadata/version | PASS (implemented in `RuntimeAssetsManager`) |
| E01–E13 Runtime asset layer | PASS (implemented) |
| F01–F05 Runtime model (RuntimeSpec) | PASS (already supports `customProotPath`/`customLoaderPath`; rootfs/bindings independent) |
| G01–G08 ProotCommandBuilder | PASS (implemented + test added) |
| H01–H10 ProotRuntimeBackend | PASS (implemented; compile verification blocked) |
| R01–R07 Source audit (embedded PRoot references) | PARTIAL — see below |

### BLOCKED (cannot verify in this environment)

| Task | Reason |
|------|--------|
| A05 LinuxDroid debug build | No JDK / Android SDK |
| C01–C04 Android ARM64 proot + loader build & ELF check | No NDK |
| C05, C07–C10 On-device / functional / seccomp / signal / loader tests | No device |
| J01–J09 External PRoot integration on device | No device |
| K01–K10 Remove embedded PRoot build | Gated on J01–J09 passing on device; removing now would break the runtime (critical stop condition #9) |
| L01–L04 Loader migration removal | Same gate as K |
| M01–M04 Bootstrap review | Requires experimental device runs |
| N01–N06 PTY verification | Requires device |
| O01–O06 Failure-handling verification | Requires device; source is implemented in `RuntimeAssetsManager` |
| P01–P06 Version management | Source implemented (`requiredProotVersion`, `isVersionCompatible`, atomic install); on-device upgrade/rollback verification blocked |
| Q01–Q07 Regression test suite | No JDK/SDK/device |

### DONE (added this pass)

| Task | Result |
|------|--------|
| I01–I06 `RuntimeLauncher` separation | VERIFIED (source). Launch moved out of `ProotRuntimeBackend` into a new `RuntimeLauncher` with `launchProcess` + `launchPty`, consuming the same `RuntimeSpec` + resolved PRoot path. Process launch tested via a host binary; PTY path requires a device. |

---

## Source audit against the final architecture (Phase R)

| Check | Status |
|-------|--------|
| `libproot.so` as an active PRoot source | The **real** path resolution now goes through `RuntimeAssetsManager`; the only remaining `nativeLibraryDir` reference is a diagnostic log line. Active resolution no longer treats `libproot.so` as canonical. Legacy fallback retained only until on-device verification (H02). |
| `:native:proot` Gradle dependency | **Still present** (frozen baseline). Removal is gated on Phase J passing on device (Phase K). |
| `native/proot` source | **Still present** (frozen baseline). Removal is gated on Phase K. |
| PRoot CMake targets | **Still present** (frozen baseline). |
| PRoot extraction owned by only RuntimeAssetsManager | ✅ Backend no longer extracts; `RuntimeAssetsManager` owns installation. |
| PRoot path discovery owned by asset layer | ✅ `RuntimeAssetsManager` resolves the installed executable. |
| Duplicated PRoot implementations | One maintained implementation lives in `LinuxDroid_proot`. The in-tree `native/proot` is frozen baseline pending K-phase deletion. |

---

## Definition of Done — status

| Condition | Status |
|-----------|--------|
| LinuxDroid_proot owns PRoot | ✅ |
| LinuxDroid consumes PRoot | ✅ (source: `RuntimeAssetsManager`) |
| LinuxDroid does not compile PRoot | ⏳ (K-phase pending; still builds bundled baseline) |
| PRoot as executable runtime asset (not fake JNI) | ✅ (asset layer; legacy `libproot.so` is fallback only) |
| Loader versioned artifact model | ✅ (asset layer) |
| `/bin/true`, `/bin/echo`, `/bin/sh`, dynamic ELF, fs, `/proc`, `/dev`, PTY, signals | ⏳ Requires on-device verification (J/N phases) |
| LinuxDroid_proot native tests pass | ✅ (host: 13/13) |
| LinuxDroid existing runtime tests pass | ⏳ Requires gradle (no JDK/SDK) |
| No active embedded PRoot remains | ⏳ K-phase pending |
| PRoot version / commit / ABI / checksum identifiable | ✅ (metadata + `RuntimeAssetMetadata`) |
| Failed PRoot update does not destroy previous runtime | ✅ (atomic `.part` → rename + checksum before promotion) |

---

## Recommended next steps (in order)

1. Run CI / a device-enabled environment to execute `make android-arm64`,
   push `proot`/`loader` to a device, and run `linuxdroid-selftest` (>14 →
   `result: PASS`) on **arm64-v8a**.
2. With a JDK + Android SDK, run `./gradlew :core:core-runtime:test` and the
   rest of the unit suite to confirm the new Kotlin compiles and the runtime
   tests pass.
3. After Phase J on-device, perform Phase K (remove `:native:proot` from Gradle,
   delete `native/proot`, drop the `jniLibs/libproot.so` bundle, and verify the
   APK no longer packages it).
4. Then complete Phase L (loader), M (bootstrap review), N (PTY), O (failure
   handling), P (upgrade/rollback), and Q (full regression).
