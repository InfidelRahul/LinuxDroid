# LinuxDroid — PRoot Runtime Backend

## 1. Mechanism
`ProotRuntimeBackend` leverages PRoot (`ptrace`-based system call translation) to create a rootless chroot directly inside Android app private storage:
- **Executable & Libraries:** `proot`, `libtalloc.so.2`, and `libandroid-shmem.so` are extracted from APK assets to `${filesDir}/proot/<abi>/`.
- **System Mounts:**
  - `-b /dev` (Android device nodes)
  - `-b /proc` (Process filesystem)
  - `-b /sys` (System hardware details)
  - `-b /system` (Android base system)
  - `-b /vendor` (Hardware libraries)
  - `-b ${sharedDir}:/home/user/Android` (Shared storage bridge)
- **Flag Configuration:** `--link2symlink`, `--root-id`, `--kill-on-exit`.
- **Dynamic Linker:** Sets `LD_LIBRARY_PATH` and `PROOT_TMP_DIR`.

