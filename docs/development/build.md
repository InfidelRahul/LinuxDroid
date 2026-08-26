# LinuxDroid — Build & Toolchain Guide

## 1. Prerequisites
- **Android SDK:** API 35 (`compileSdk = 35`, `minSdk = 28`, `targetSdk = 35`)
- **Android NDK:** Version `27.2.12479018`
- **CMake:** Version `3.22.1`
- **JDK:** OpenJDK 21 (Temurin / SDKMAN)
- **Gradle:** 8.12 (via Gradle Wrapper `./gradlew`)

## 2. Build Commands
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
