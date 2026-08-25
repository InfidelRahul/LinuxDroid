# LinuxDroid — Build Guide

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17 or 21 (NOT 25+) |
| Android SDK | 35 |
| Android NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| Gradle | 8.12 (via wrapper) |

## Initial Setup

### 1. Install Android SDK and NDK

```bash
bash tools/bootstrap-sdk.sh
```

This installs the Android SDK to `/workspaces/android-sdk/`.

### 2. Set environment variables

```bash
export ANDROID_SDK_ROOT=/workspaces/android-sdk
export ANDROID_HOME=/workspaces/android-sdk
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.12+1.1-tem  # Java 21
```

Or add to `local.properties`:
```
sdk.dir=/workspaces/android-sdk
ndk.dir=/workspaces/android-sdk/ndk/27.2.12479018
```

## Building

### Build all modules
```bash
./gradlew assembleDebug
```

### Build specific module
```bash
./gradlew :core:core-model:assembleDebug
./gradlew :native:bridge:assembleDebug
```

### Build release APK
```bash
./gradlew assembleRelease
```

### Run unit tests
```bash
./gradlew test
```

### Run all checks
```bash
./gradlew check
```

## Important Notes

- Java 25 is NOT supported (Kotlin 2.0.x JavaVersion parser fails on 25.x)
- Use Java 17 or 21
- The NDK build targets arm64-v8a (primary) and x86_64 (emulator)
- proot binary must be bundled in `app/src/main/assets/proot/<abi>/proot`
  before the runtime will function on real devices
