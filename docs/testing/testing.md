# LinuxDroid — Testing Guide

## Test Structure

```
tests/
  unit/         — Pure Kotlin unit tests (no Android runtime)
  integration/  — Android integration tests (require emulator or device)
  native/       — Native C++ unit tests
  device/       — On-device tests (require real ARM64 device)
```

## Running Tests

### Unit Tests (host JVM)
```bash
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.12+1.1-tem
./gradlew test
```

### Run specific module tests
```bash
./gradlew :core:core-model:test
```

### Android instrumented tests (requires device or emulator)
```bash
./gradlew connectedAndroidTest
```

## Phase 4 Tests: State Machine

The state machine tests in `EnvironmentStateMachineTest` verify:

| Test | Validates |
|------|-----------|
| CREATED → INSTALLING | Valid initial transition |
| INSTALLING → READY | Valid completion |
| READY → STARTING | Valid start |
| STARTING → RUNNING | Valid activation |
| RUNNING → STOPPING | Valid graceful stop |
| STOPPING → STOPPED | Valid clean stop |
| STOPPED → STARTING | Valid restart |
| RUNNING → FAILED | Valid failure |
| FAILED → RECOVERING | Valid recovery attempt |
| RECOVERING → READY | Valid recovery completion |
| CREATED → RUNNING | INVALID — rejects |
| RUNNING → INSTALLING | INVALID — rejects |
| STOPPED → READY | INVALID — rejects |
| READY → RUNNING | INVALID — must go through STARTING |
| EnvironmentId immutable | ID never changes on transition |
| canStart() | Only READY and STOPPED |
| canStop() | Only RUNNING and STARTING |
| failure message cleared | After FAILED is resolved |
| EnvironmentId format | Validates alphanumeric with dash/underscore |

## Persistence Test (Manual — Phase 8)

```bash
# 1. Create environment
# 2. Start environment
# 3. Create test file inside Linux:
#    echo "LinuxDroid persistence test" > /home/user/test-persistence.txt
# 4. Install a package:
#    apt install -y nano
# 5. Modify configuration:
#    echo "alias ll='ls -la'" >> /home/user/.bashrc
# 6. Stop environment
# 7. Close LinuxDroid app
# 8. Reopen LinuxDroid app
# 9. Start environment
# 10. Verify:
#     cat /home/user/test-persistence.txt  # Must exist
#     which nano                            # Must be found
#     cat /home/user/.bashrc | grep alias  # Must be there
```

## Shared Storage Test (Manual — Phase 5)

```bash
# Android → Linux:
# 1. Create file on Android: /storage/emulated/0/LinuxDroid/hello.txt
# 2. Read from Linux: cat /home/user/Android/hello.txt

# Linux → Android:
# 1. Create from Linux: echo "from linux" > /home/user/Android/from-linux.txt
# 2. Read from Android: /storage/emulated/0/LinuxDroid/from-linux.txt

# Revoke storage authorization:
# 1. Revoke MANAGE_EXTERNAL_STORAGE in Android Settings
# 2. Verify: Linux session continues running
# 3. Verify: /home/user/Android/ becomes unavailable (expected)
# 4. Verify: /home/user/ and all other dirs intact
# 5. Verify: packages still installed
```

## Failure Tests (Manual — Phase 23)

| Test | Expected Behavior |
|------|-------------------|
| Kill proot process | Session enters FAILED state; rootfs intact |
| Kill compositor | Session enters FAILED; recovers if configured |
| Android Activity destroy | Session continues in foreground service |
| Android app restart | Session resumes; rootfs intact |
| OOM (insufficient memory) | Session may stop; rootfs never deleted |
| Storage full | Graceful error; rootfs not corrupted |
