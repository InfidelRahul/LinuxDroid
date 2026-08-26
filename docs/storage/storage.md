# LinuxDroid — Storage Architecture & Shared Storage Bridge

## 1. Storage Isolation Principle
LinuxDroid strictly restricts arbitrary Android filesystem access. The only exposed external shared directory is:
`/storage/emulated/0/LinuxDroid/`

Inside the Linux userspace, this directory is bind-mounted at:
`/home/user/Android/`

## 2. Authorization & Safe Revocation
`AndroidStorageManager` monitors access permissions:
- When authorized: Bidirectional read/write access between Android file managers and Linux applications.
- When revoked: The mount point becomes safely unavailable. **The rootfs and installed packages remain completely intact.**

## 3. Path Traversal Guard
All user-supplied paths are verified by `PathValidator.requireInsideBase(path, basePath)` to prevent directory traversal escapes (`../`).
