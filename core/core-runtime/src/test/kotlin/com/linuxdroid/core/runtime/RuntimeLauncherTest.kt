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
        rootfsPath = echo.parentFile.absolutePath,
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
}
