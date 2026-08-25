# LinuxDroid — Security Architecture

## Principles

1. **Zero privilege escalation**: LinuxDroid never acquires root. No su, no exploit.
2. **Rootless operation**: proot provides fake root via ptrace — the app process remains unprivileged.
3. **Filesystem isolation**: Each environment's rootfs is inside the app's private storage.
4. **Controlled external storage**: The shared directory requires explicit Android storage authorization.
5. **Single JNI entry point**: All native calls go through `NativeBridge.kt` only.
6. **Path validation**: All filesystem paths validated before use.
7. **Command injection prevention**: Commands always passed as `List<String>`, never through a shell string.

## Path Validation

All paths are validated through `PathValidator` before use:

```kotlin
// Prevents traversal:
PathValidator.requireInsideBase("/etc/../../../data", "/rootfs")
// → throws PathTraversalError

// EnvironmentId validation:
EnvironmentId("../../etc") // throws IllegalArgumentException
EnvironmentId("valid-id")  // ok
```

## Android Permissions Used

| Permission | Reason |
|-----------|--------|
| INTERNET | Linux network access |
| ACCESS_NETWORK_STATE | Monitor connectivity |
| FOREGROUND_SERVICE | Keep Linux session alive |
| FOREGROUND_SERVICE_SPECIAL_USE | Session foreground service |
| WAKE_LOCK | Keep CPU active during Linux operations |
| READ/WRITE_EXTERNAL_STORAGE | Shared directory (legacy) |
| MANAGE_EXTERNAL_STORAGE | Shared directory (API 30+, user must grant) |
| POST_NOTIFICATIONS | Session status notifications |
| RECORD_AUDIO | Linux audio input |
| VIBRATE | Haptic feedback for input |

## NOT Required / NOT Used

- No `SYSTEM_ALERT_WINDOW`
- No `DEVICE_ADMIN`
- No `INSTALL_PACKAGES`
- No root check that escalates privilege
- No kernel exploit
- No `/proc/sysrq-trigger`
- No `ptrace` of other apps (proot only ptrace's its own children)

## JNI Security

The native bridge only implements:
- File attribute queries (`isExecutable`, `setExecutable`)
- Signal sending (`sendSignal`) — to processes owned by the app
- Memory query (`getAvailableMemoryBytes`) — reads `/proc/meminfo` only
- ABI detection — compile-time constant

The native bridge does NOT:
- Execute arbitrary commands
- Read arbitrary files
- Write to locations outside the app sandbox
- Escalate privileges

## Shared Storage

The shared directory (`/storage/emulated/0/LinuxDroid/`) is the ONLY
external storage location used by LinuxDroid.

Security guarantees:
- Access requires explicit Android `MANAGE_EXTERNAL_STORAGE` grant
- The path is hardcoded (no user-configurable arbitrary external path)
- Linux sees it only at `/home/user/Android/` via proot bind mount
- Revocation is handled gracefully (Linux rootfs remains intact)
- Files are NEVER deleted automatically when authorization is revoked

## What LinuxDroid CANNOT Do

Because proot only intercepts its own children's syscalls:
- Cannot access other apps' data
- Cannot read system protected files
- Cannot escalate to real root
- Cannot load kernel modules
- Cannot modify Android system settings
