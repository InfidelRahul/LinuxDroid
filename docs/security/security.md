# LinuxDroid — Security Model

## 1. Zero Root / Zero Privilege Escalation
LinuxDroid is completely rootless:
- Never executes `su`, `sudo` (outside fake root id inside proot), or root exploits.
- Never mounts arbitrary external storage.
- Never accesses Android internal private directories of other apps.

## 2. Hardening Measures
- **Path Validation:** `PathValidator.requireInsideBase()` prevents escape out of the rootfs or sandbox.
- **Sanitized Arguments:** `PackageManager` regex-validates package names before executing `apt-get`.
- **Command Injection Guard:** All process executions use strict `List<String>` argument tokenization rather than raw unescaped shell strings.
- **Scoped Shared Storage:** Only `/storage/emulated/0/LinuxDroid/` is exposed.
