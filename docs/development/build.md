# LinuxDroid — Build & Toolchain Guide

## 1. Prerequisites
- **Android SDK:** API 36 (`compileSdk = 36`, `minSdk = 36`, `targetSdk = 36`)
- **Android NDK:** Version `29.0.14206865`
- **CMake:** Version `3.22.1`
- **JDK:** OpenJDK 21 (Temurin / SDKMAN)
- **Gradle:** 8.12 (via Gradle Wrapper `./gradlew`)

## 2. Runtime Dependencies
LinuxDroid bundles its runtime components and native engines directly in the application build. The core execution engine is built on PRoot with Android Bionic syscall compatibility patches. Build artifacts are integrated into `app/src/main/jniLibs/arm64-v8a/` and `app/src/main/assets/`.

## 3. Build Commands
Set environment paths and assemble the APK:

```bash
# Set Android SDK and NDK environment variables
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/29.0.14206865

# Run unit tests across all modules
./gradlew testDebugUnitTest --no-daemon

# Compile and package Debug APK
./gradlew :app:assembleDebug --no-daemon

# Compile and package Release APK
./gradlew :app:assembleRelease --no-daemon
```

### Build Outputs
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release.apk`
- **Native Test Binaries**: `native/bridge/build/intermediates/cxx/...`

