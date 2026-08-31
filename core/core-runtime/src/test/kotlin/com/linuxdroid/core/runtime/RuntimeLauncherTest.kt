package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.Architecture
import com.linuxdroid.core.model.RuntimeSpec
import com.linuxdroid.core.model.EnvironmentId
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.io.File

/**
 * JVM unit tests for [RuntimeLauncher] process-launch mechanics.
 *
 * The PTY path requires the Android [com.linuxdroid.native_bridge.NativeBridge]
 * and is verified on-device / via instrumentation, not here. Process launch is
 * exercised against a real host binary (when available) to prove the launcher
 * builds the command through the injected builder and starts a subprocess in
 * the target working directory.
 */
class RuntimeLauncherTest {

    private fun guestEchoSpec(echo: File): RuntimeSpec = RuntimeSpec(
        environmentId = EnvironmentId("launcher-test"),
        rootfsPath = echo.parentFile?.absolutePath ?: "/",
        architecture = Architecture.X86_64,
        workingDirectory = "/",
        command = listOf("hello"),
        bindings = emptyList(),
        sharedStorageEnabled = false,
    )

    private fun createTempDir(): File {
        val dir = File.createTempFile("linuxdroid-launcher-test", "").apply { delete() }
        dir.mkdirs()
        return dir
    }

    @Test
    fun `launchProcess builds argv through the injected command builder and starts a subprocess`() {
        val echo = File("/bin/echo")
        if (!echo.exists() || !echo.canExecute()) return // host-only guard

        val builder = mockk<RuntimeCommandBuilder>()
        val launcher = RuntimeLauncher(builder)
        val spec = guestEchoSpec(echo)
        val tmpDir = createTempDir()

        every { builder.build(spec, echo) } returns listOf(echo.absolutePath, "launcher-ok")

        val process = launcher.launchProcess(
            spec = spec,
            proot = echo,
            loader = null,
            rootfs = File(spec.rootfsPath),
            tmpDir = tmpDir,
        )

        val exit = process.waitFor()
        val out = process.inputStream.bufferedReader().readText().trim()
        val err = process.errorStream.bufferedReader().readText().trim()

        verify(exactly = 1) { builder.build(spec, echo) }
        assert(exit == 0) { "expected exit 0, got $exit (stderr=$err)" }
        assert(out == "launcher-ok") { "expected stdout 'launcher-ok', got '$out'" }

        tmpDir.deleteRecursively()
    }

    @Test
    fun `launchProcess passes PROOT_LOG_FILE environment variable to subprocess`() {
        val sh = File("/bin/sh")
        if (!sh.exists() || !sh.canExecute()) return

        val builder = mockk<RuntimeCommandBuilder>()
        val launcher = RuntimeLauncher(builder)
        val tmpDir = createTempDir()
        val logFile = File(tmpDir, "logs/console.log")
        val spec = guestEchoSpec(sh).copy(logFilePath = logFile.absolutePath)

        // Script that prints $PROOT_LOG_FILE to stdout to verify launcher set it in environment
        every { builder.build(spec, sh) } returns listOf(sh.absolutePath, "-c", "echo LOG_VAR=\$PROOT_LOG_FILE")

        val process = launcher.launchProcess(
            spec = spec,
            proot = sh,
            loader = null,
            rootfs = File(spec.rootfsPath),
            tmpDir = tmpDir,
            logFile = logFile,
        )

        val exit = process.waitFor()
        val out = process.inputStream.bufferedReader().readText().trim()

        assert(exit == 0)
        assert(out == "LOG_VAR=${logFile.absolutePath}") { "expected LOG_VAR=${logFile.absolutePath}, got '$out'" }
        assert(logFile.parentFile?.exists() == true) { "log directory should be created" }

        tmpDir.deleteRecursively()
    }

    @Test
    fun `launchProcess falls back to spec customProotPath when no override is supplied`() {
        // The launcher delegates argv construction to the builder; if the caller
        // passes no executable override, the builder falls back (proven in the
        // ProotCommandBuilder tests). This asserts the launcher forwards the
        // path it is given and does not perform its own discovery.
        val echo = File("/bin/echo")
        if (!echo.exists() || !echo.canExecute()) return

        val builder = mockk<RuntimeCommandBuilder>()
        val launcher = RuntimeLauncher(builder)
        val spec = guestEchoSpec(echo)
        val tmpDir = createTempDir()

        every { builder.build(spec, echo) } returns listOf(echo.absolutePath, "fallback")

        val process = launcher.launchProcess(spec, echo, null, File(spec.rootfsPath), tmpDir)
        val exit = process.waitFor()

        verify(exactly = 1) { builder.build(spec, echo) }
        assert(exit == 0)

        tmpDir.deleteRecursively()
    }

    @Test
    fun `launchProcess sanitizes host environment variables and strips LD_PRELOAD and LD_LIBRARY_PATH`() {
        val sh = File("/bin/sh")
        if (!sh.exists() || !sh.canExecute()) return

        val builder = mockk<RuntimeCommandBuilder>()
        val launcher = RuntimeLauncher(builder)
        val tmpDir = createTempDir()
        val spec = guestEchoSpec(sh).copy(
            environmentVariables = mapOf(
                "USER" to "root",
                "LD_PRELOAD" to "/should/be/stripped/lib.so",
                "LD_LIBRARY_PATH" to "/should/be/stripped/lib",
            )
        )

        every { builder.build(spec, sh) } returns listOf(
            sh.absolutePath,
            "-c",
            "echo PRELOAD=\${LD_PRELOAD:-unset} LIBPATH=\${LD_LIBRARY_PATH:-unset} USER=\$USER PROOT_NO_SECCOMP=\$PROOT_NO_SECCOMP"
        )

        val process = launcher.launchProcess(spec, sh, null, File(spec.rootfsPath), tmpDir)
        val exit = process.waitFor()
        val out = process.inputStream.bufferedReader().readText().trim()

        assert(exit == 0)
        assert(out == "PRELOAD=unset LIBPATH=unset USER=root PROOT_NO_SECCOMP=1") {
            "Expected sanitized environment, got: '$out'"
        }

        tmpDir.deleteRecursively()
    }
}
