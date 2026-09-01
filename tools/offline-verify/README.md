# Offline verification harness

Sandbox-only harness used to **compile and execute** the pure-Kotlin
`:core:core-gui` sources and unit tests without Gradle, the Android SDK, or
Maven Central — all of which are unreachable from the build sandbox.

This is a verification aid, **not** part of the product build. The Gradle build
remains the authoritative one; nothing here is referenced by any module.

## Why it exists

`services.gradle.org`, `repo1.maven.org` and `maven.google.com` are all
blocked in the sandbox, so `./gradlew test` cannot run. `:core:core-gui` is
deliberately free of `android.*`, so its sources only need the Kotlin stdlib
plus coroutines — both of which ship inside the `kotlin-compiler` npm package.
The remaining third-party symbols are replaced by the tiny stubs here.

## Contents

- `stubs/` — minimal `android.util.Log`, `timber.log.Timber` and
  `kotlinx.serialization` annotations, needed to typecheck `:core:core-model`,
  `:core:core-logging` and `:core:core-filesystem`.
- `tstubs/` — minimal JUnit4 annotations, `TemporaryFolder`, a
  Truth-compatible assertion surface, and a `runTest` shim. Semantics match the
  real libraries so **test bodies are used unmodified**.
- `Runner.kt` — reflection-based runner that discovers `*Test` classes, honours
  `@Before`, and executes every `@Test`.

## Usage

```sh
export JAVA_HOME=/path/to/jdk17
KC=/path/to/kotlin-compiler/bin/kotlinc
L=/path/to/kotlin-compiler/lib
CO=$L/kotlinx-coroutines-core-jvm.jar

# main sources
$KC -nowarn -cp "$CO" -d out/main \
  tools/offline-verify/stubs/*.kt \
  core/core-model/src/main/kotlin/com/linuxdroid/core/model/*.kt \
  core/core-logging/src/main/kotlin/com/linuxdroid/core/logging/*.kt \
  core/core-filesystem/src/main/kotlin/com/linuxdroid/core/filesystem/*.kt \
  core/core-gui/src/main/kotlin/com/linuxdroid/core/gui/*.kt

# tests
$KC -nowarn -cp "$CO:out/main" -d out/test \
  tools/offline-verify/tstubs/*.kt \
  core/core-gui/src/test/kotlin/com/linuxdroid/core/gui/*.kt

# run
$KC -nowarn -d out/runner tools/offline-verify/Runner.kt
$JAVA_HOME/bin/java \
  -cp "$L/kotlin-stdlib.jar:$CO:out/main:out/test:out/runner" \
  RunnerKt out/test
```

## Native and Android-side verification

- `native-stubs/` — minimal NDK headers (`android/native_window.h`,
  `android/log.h`, `jni.h`) so the C++ display bridge can be compiled and
  run off-device, plus `display_bridge_checks.cpp`, which exercises the real
  `DisplayBridge::presentFrame` against a fake `ANativeWindow` (channel swap,
  alpha forcing, padded strides, clipping, every rejection path).

  ```sh
  g++ -std=c++17 -Wall -Wextra -Itools/offline-verify/native-stubs \
      -Inative/bridge/src/main/cpp \
      -o /tmp/checks tools/offline-verify/native-stubs/display_bridge_checks.cpp
  /tmp/checks
  ```

- `wayland-stubs/` — minimal `wayland-client.h` and the Weston output-capture
  protocol header, enough to syntax-check `linux/capture/linuxdroid-capture.c`
  where no Wayland development packages exist.

  ```sh
  gcc -std=c11 -fsyntax-only -Wall -Wextra \
      -Itools/offline-verify/wayland-stubs linux/capture/linuxdroid-capture.c
  ```

- `astubs/` — `android.view.Surface` and a `NativeBridge` stand-in, so
  `:core:core-display` (`AndroidFrameSink`, `AndroidDisplayTransport`) and the
  `HostGraphics` contract can be compiled and tested off-device.

## Coverage and limits

- `:core:core-gui` main + tests: **149 tests, all passing**.
- `:core:core-display` (`AndroidFrameSink`): **10 tests, all passing**.
- Native `DisplayBridge`: **12 checks, all passing**.
- Does **not** cover `:core:core-session` or `:app` — those need MockK and the
  Android SDK, neither of which is reachable offline. They remain verified only
  by the Gradle build.
- The stubs are *not* the real libraries. They prove types, logic and memory
  layout, not real `ANativeWindow` or real Weston behaviour.
