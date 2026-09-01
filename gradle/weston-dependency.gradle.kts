import java.io.File

/**
 * LinuxDroid — deterministic Weston / libweston dependency verification.
 *
 * This mirrors the existing `gradle/proot-currency.gradle.kts` pattern: it
 * registers a `verifyWeston` task in the `verification` group. The task is
 * lazy (registered, not executed at configuration time), so it never blocks
 * the normal app/CLI build unless explicitly invoked.
 *
 * It confirms the frozen architecture pins:
 *
 *   Weston version        = 16.0.0
 *   Pinned source revision = d1882b0a544ae2197b597a6e39478e719bc54302
 *
 * If the pinned source tree (native/weston/src) is present, verification runs
 * `native/weston/verify-weston.sh --strict-source`, which reads the version
 * and commit from the actual source. If the source is not present (e.g. on a
 * fresh checkout before `fetch-weston.sh`), the task validates the
 * authoritative spec pins and instructs the developer to fetch the source.
 */

/** Absolute path to the native/weston subsystem in this repository. */
val westonDir: File = File(rootProject.projectDir, "native/weston")

/** Path to the deterministic verification script. */
val westonVerify: File = File(westonDir, "verify-weston.sh")

/** Path to the machine-readable pinned spec. */
val westonSpec: File = File(westonDir, "weston.spec.json")

/** True when a source tree has actually been fetched. */
fun westonSourcePresent(): Boolean = File(westonDir, "src").let { dir ->
    dir.isDirectory && File(dir, "meson.build").exists()
}

tasks.register("verifyWeston") {
    group = "verification"
    description = "Verifies the pinned Weston 16.0.0 / libweston dependency (version + commit)."

    doLast {
        if (!westonSpec.exists()) {
            throw GradleException("Missing Weston pinned spec: ${westonSpec.relativeTo(rootProject.projectDir).path}")
        }

        // Validate the hard-coded frozen pins against the authoritative spec.
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

        // If the source is present, run the deterministic source-level check.
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
                "Run native/weston/fetch-weston.sh to acquire and verify the exact 16.0.0 source; the app builds regardless.")
        }
    }
}
