# LinuxDroid — Build & Toolchain Guide

## 1. Prerequisites
- **Android SDK:** API 36 (`compileSdk = 36`, `minSdk = 36`, `targetSdk = 36`)
- **Android NDK:** Version `29.0.14206865`
- **CMake:** Version `3.22.1`
- **JDK:** OpenJDK 21 (Temurin / SDKMAN)
- **Gradle:** 8.12 (via Gradle Wrapper `./gradlew`)

## 2. Runtime prerequisite

The target LinuxDroid build consumes a versioned `proot` and `loader` release from [LinuxDroid_proot](https://github.com/InfidelRahul/LinuxDroid_proot). Build and test that repository independently before integrating an artifact into the APK. Its release metadata must identify the source commit, ABI, Android minimum, toolchain, SHA-256 values, and features.

The current Gradle build still contains the legacy `native/proot` module so the baseline can be reproduced during migration. That module and its `jniLibs` packaging are not the target dependency model and must not receive native compatibility fixes. Follow [Updated Final Migration Plan](../migration-plan.md) for the gates that replace this path with `RuntimeAssetsManager` and APK `assets/proot/<abi>/`.

## 3. Build Commands
Set environment paths and assemble APK:
```bash
export ANDROID_SDK_ROOT=/workspaces/android-sdk
export ANDROID_HOME=/workspaces/android-sdk
export JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.12+1.1-tem

# Run all unit tests
./gradlew test --no-daemon

# Assemble debug APK
./gradlew assembleDebug --no-daemon
```

Output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`
