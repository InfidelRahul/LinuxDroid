# LinuxDroid — Testing & Verification Guide

## 1. Automated Test Suites
Run all module unit tests:
```bash
./gradlew test
```
Tested components include:
- `EnvironmentStateMachineTest`: 15 state transition invariance tests.
- `PathValidatorTest`: Path traversal and escape attempt rejection.
- `AndroidStorageManagerTest`: Shared storage verification and revocation handling.
- `ApplicationManagerTest`: FreeDesktop `.desktop` entry parsing and `NoDisplay` filtering.
- `HostCapabilitiesTest`: Host graphics metrics and storage capability verification.

## 2. Real-Device Verification Flow
1. Install debug APK on ARM64 Android device.
2. Launch app and create Debian environment via `+` button.
3. Observe real-time rootfs download and bootstrap unpacking.
4. Launch Terminal and execute `/bin/sh` commands (`uname -a`, `cat /etc/os-release`).
5. Verify `/home/user/Android/` shared directory read/write.
6. Verify persistence across process force-stop and relaunch.
