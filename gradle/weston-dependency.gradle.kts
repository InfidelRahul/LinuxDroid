import java.io.File

/**
 * LinuxDroid — deterministic Weston / libweston source verification.
 *
 * Mirrors `gradle/proot-currency.gradle.kts`: registers lazy tasks so they never
 * run at configuration time.
 *
 * Weston is tracked from the `main` branch of the InfidelRahul/weston
 * development mirror (native/weston), so there is NO fixed version/commit pin.
 * The actual commit is resolved at fetch time and recorded in
 * native/weston/src/.weston_commit; verification asserts the recorded commit
 * equals the git HEAD of the checkout, that the source is the mirror, and that
 * it declares a valid Weston version + libweston_major. The authoritative check
 * lives in native/weston/verify-weston.sh — the Gradle tasks simply run it so
 * the build graph fails fast when the source is not correct.
 *
 * Two tasks:
 *   - `verifyWeston`        : confirms the resolved mirror-main source matches
 *                             the recorded commit (source-level, or spec-level
 *                             when the source is not fetched).
 *   - `verifyWestonBuild`   : requires the real libweston install to be present
 *                             under native/weston/dist and matching the resolved
 *                             source. This is the Phase 3 hard gate: it MUST
 *                             fail if the fallback (no-libweston) path would be
 *                             compiled. It is only wired into `:app:preBuild`
 *                             when the build is run with `-PreqWeston` (CI), so
 *                             default/local builds that intentionally test the
 *                             fallback are unaffected.
 */

/** Absolute path to the native/weston subsystem in this repository. */
val westonDir: File = File(rootProject.projectDir, "native/weston")

/** Path to the deterministic verification script. */
val westonVerify: File = File(westonDir, "verify-weston.sh")

/** Path to the machine-readable source spec. */
val westonSpec: File = File(westonDir, "weston.spec.json")

/** Installed libweston artifacts (built by native/weston/build-libweston.sh). */
val westonDistDir: File = File(westonDir, "dist")

/** True when native/weston/dist contains a real libweston library + headers. */
fun westonBuildPresent(): Boolean = westonDistDir.let { dist ->
    dist.isDirectory &&
        File(dist, "lib").let { lib ->
            File(lib, "libweston-17.so").exists() || File(lib, "libweston-17.so.0").exists() ||
                File(lib, "libweston-16.so").exists() || File(lib, "libweston-16.so.0").exists() ||
                File(lib, "libweston.so").exists() ||
                lib.listFiles()?.any { it.name.endsWith(".so") && it.name.startsWith("libweston") } == true
        } &&
        File(dist, "include").let { inc ->
            File(inc, "libweston-17/libweston/libweston.h").exists() ||
                File(inc, "libweston-16/libweston/libweston.h").exists() ||
                File(inc, "libweston/libweston.h").exists()
        }
}

/**
 * Runs the deterministic verification script and throws on failure. Returns the
 * script's combined output so the caller can log it (logger is not a script
 * property dependable inside a top-level function).
 */
fun runVerify(strict: Boolean): String {
    if (!westonVerify.exists()) {
        throw GradleException("Missing verification script: ${westonVerify.relativeTo(rootProject.projectDir).path}")
    }
    val args = if (strict) arrayOf(westonVerify.absolutePath, "--strict-source", "--strict-deps")
               else arrayOf(westonVerify.absolutePath)
    val result = ProcessBuilder(*args)
        .directory(westonDir)
        .redirectErrorStream(true)
        .start()
    val output = result.inputStream.bufferedReader().use { it.readText() }
    val finished = result.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        result.destroyForcibly()
        throw GradleException("[verifyWeston] Verification script timed out.")
    }
    if (result.exitValue() != 0) {
        throw GradleException("[verifyWeston] Verification FAILED. Weston/libweston source does not match the resolved mirror-main commit.")
    }
    return output
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
    description = "Verifies the resolved mirror-main Weston/libweston source (recorded commit == git HEAD)."

    doLast {
        if (!westonSpec.exists()) {
            throw GradleException("Missing Weston source spec: ${westonSpec.relativeTo(rootProject.projectDir).path}")
        }
        val output = runVerify(strict = false)
        logger.lifecycle(output.trim())
        logger.lifecycle("[verifyWeston] PASSED (source).")
    }
}

tasks.register("verifyWestonBuild") {
    group = "verification"
    description = "Phase 3 gate: requires the real cross-built libweston install (native/weston/dist) to be present and matching the resolved source."

    doLast {
        // 1. The installed artifacts must exist.
        if (!westonBuildPresent()) {
            throw GradleException(
                "[verifyWestonBuild] The real libweston install was NOT found under " +
                    "${westonDistDir.relativeTo(rootProject.projectDir).path}. " +
                    "Phase 3 must build and link the resolved libweston, not the fallback. " +
                    "Run native/weston/fetch-weston.sh, native/weston/bootstrap-deps.sh and native/weston/build-libweston.sh."
            )
        }
        logger.lifecycle("[verifyWestonBuild] Real libweston install present under ${westonDistDir.relativeTo(rootProject.projectDir).path}.")

        // 2. The install must match the resolved mirror-main source/commit.
        val output = runVerify(strict = true)
        logger.lifecycle(output.trim())
        logger.lifecycle("[verifyWestonBuild] PASSED: real libweston path is available and matches the resolved mirror-main source.")
    }
}
