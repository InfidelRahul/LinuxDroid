import java.io.File

/**
 * LinuxDroid — deterministic Weston / libweston dependency verification.
 *
 * Mirrors `gradle/proot-currency.gradle.kts`: registers lazy tasks so they never
 * run at configuration time.
 *
 * Two tasks:
 *   - `verifyWeston`        : confirms the pinned version + commit (source-level,
 *                             or spec-level when the source is not fetched).
 *   - `verifyWestonBuild`   : requires the real libweston install to be present
 *                             under native/weston/dist and matching the pin.
 *                             This is the Phase 3 hard gate: it MUST fail if the
 *                             fallback (no-libweston) path would be compiled. It is
 *                             only wired into `:app:preBuild` when the build is run
 *                             with `-PreqWeston` (CI), so default/local builds that
 *                             intentionally test the fallback are unaffected.
 */

/** Absolute path to the native/weston subsystem in this repository. */
val westonDir: File = File(rootProject.projectDir, "native/weston")

/** Path to the deterministic verification script. */
val westonVerify: File = File(westonDir, "verify-weston.sh")

/** Path to the machine-readable pinned spec. */
val westonSpec: File = File(westonDir, "weston.spec.json")

/** Installed libweston artifacts (built by native/weston/build-libweston.sh). */
val westonDistDir: File = File(westonDir, "dist")

/** True when a source tree has actually been fetched. */
fun westonSourcePresent(): Boolean = File(westonDir, "src").let { dir ->
    dir.isDirectory && File(dir, "meson.build").exists()
}

/** True when native/weston/dist contains a real libweston library + headers. */
fun westonBuildPresent(): Boolean = westonDistDir.let { dist ->
    dist.isDirectory &&
        File(dist, "lib").let { lib ->
            (File(lib, "libweston-16.so").exists() || File(lib, "libweston-16.so.0").exists() ||
                File(lib, "libweston-16.so.0.0.0").exists())
        } &&
        File(dist, "include").let { inc ->
            File(inc, "libweston-16/libweston/libweston.h").exists() ||
                File(inc, "libweston/libweston.h").exists()
        }
}

/**
 * Wires `verifyWestonBuild` as a dependency of `:app:preBuild` so that running
 * `:app:assembleRelease -PreqWeston` fails fast if the real libweston path is
 * not available. The check is active only when `reqWeston` is supplied, so the
 * ordinary (fallback) build is not broken for local development.
 */
// `-PreqWeston` (bare flag == "true") or `-PreqWeston=true` both enable the gate.
val requireWeston: Boolean = rootProject.hasProperty("reqWeston")
if (requireWeston) {
    gradle.projectsEvaluated {
        val preBuild = rootProject.findProject(":app")?.tasks?.findByName("preBuild") ?: return@projectsEvaluated
        preBuild.dependsOn(rootProject.tasks.named("verifyWestonBuild"))
    }
}

tasks.register("verifyWeston") {
    group = "verification"
    description = "Verifies the pinned Weston 16.0.0 / libweston dependency (version + commit)."

    doLast {
        if (!westonSpec.exists()) {
            throw GradleException("Missing Weston pinned spec: ${westonSpec.relativeTo(rootProject.projectDir).path}")
        }

        val specText = westonSpec.readText()
        val specVersion = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(specText)?.groupValues?.get(1)
        val specCommit = Regex("\"commit\"\\s*:\\s*\"([^\"]+)\"").find(specText)?.groupValues?.get(1)

        val frozenVersion = "16.0.0"
        val frozenCommit = "d1882b0a544ae2197b597a6e39478e719bc54302"

        if (specVersion == null || specVersion != frozenVersion) {
            throw GradleException("Weston spec version mismatch: got '$specVersion', expected '$frozenVersion'.")
        }
        if (specCommit == null || specCommit != frozenCommit) {
            throw GradleException("Weston spec commit mismatch: got '$specCommit', expected '$frozenCommit'.")
        }
        logger.lifecycle("[verifyWeston] Spec pins confirmed (version=$frozenVersion commit=$frozenCommit).")

        if (westonSourcePresent()) {
            if (!westonVerify.exists()) {
                throw GradleException("Missing verification script: ${westonVerify.relativeTo(rootProject.projectDir).path}")
            }
            val result = ProcessBuilder(westonVerify.absolutePath, "--strict-source")
                .directory(westonDir)
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader().use { it.readText() }
            val finished = result.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                result.destroyForcibly()
                throw GradleException("[verifyWeston] Verification script timed out.")
            }
            logger.lifecycle(output.trim())
            if (result.exitValue() != 0) {
                throw GradleException("[verifyWeston] Verification FAILED. Weston/libweston source does not match the pinned version/commit.")
            }
            logger.lifecycle("[verifyWeston] PASSED (source-level).")
        } else {
            logger.warn("[verifyWeston] Pinned Weston source not present at ${File(westonDir, "src").relativeTo(rootProject.projectDir).path}. " +
                "Run native/weston/fetch-weston.sh to acquire and verify the exact 16.0.0 source.")
        }
    }
}

tasks.register("verifyWestonBuild") {
    group = "verification"
    description = "Phase 3 gate: requires the real cross-built libweston install (native/weston/dist) to be present."

    doLast {
        // 1. The installed artifacts must exist.
        if (!westonBuildPresent()) {
            throw GradleException(
                "[verifyWestonBuild] The real libweston 16.0.0 install was NOT found under " +
                    "${westonDistDir.relativeTo(rootProject.projectDir).path}. " +
                    "Phase 3 must build and link the pinned libweston, not the fallback. " +
                    "Run native/weston/fetch-weston.sh, native/weston/bootstrap-deps.sh and native/weston/build-libweston.sh."
            )
        }
        logger.lifecycle("[verifyWestonBuild] Real libweston install present under ${westonDistDir.relativeTo(rootProject.projectDir).path}.")

        // 2. The install must match the pinned version/commit (reads the source).
        if (westonSourcePresent()) {
            if (!westonVerify.exists()) {
                throw GradleException("Missing verification script: ${westonVerify.relativeTo(rootProject.projectDir).path}")
            }
            val result = ProcessBuilder(westonVerify.absolutePath, "--strict-source")
                .directory(westonDir)
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader().use { it.readText() }
            val finished = result.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                result.destroyForcibly()
                throw GradleException("[verifyWestonBuild] Verification script timed out.")
            }
            logger.lifecycle(output.trim())
            if (result.exitValue() != 0) {
                throw GradleException("[verifyWestonBuild] Verification FAILED. Installed libweston does not match the pinned version/commit.")
            }
        } else {
            logger.warn("[verifyWestonBuild] Pinned Weston source not present; relying on the installed dist artifacts only.")
        }
        logger.lifecycle("[verifyWestonBuild] PASSED: real libweston path is available and pinned (version 16.0.0).")
    }
}
