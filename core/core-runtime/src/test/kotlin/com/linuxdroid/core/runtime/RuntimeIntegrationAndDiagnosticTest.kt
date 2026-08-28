package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.File

class RuntimeIntegrationAndDiagnosticTest {

    private val backend = mockk<ProotRuntimeBackend>(relaxed = true)
    private val validator = mockk<RuntimeValidator>(relaxed = true)
    private val commandBuilder = ProotCommandBuilder()
    private lateinit var runtimeManager: RuntimeManager

    private val sampleEnvironmentId = EnvironmentId("hardened-env")
    private val sampleSpec = RuntimeSpec(
        environmentId = sampleEnvironmentId,
        rootfsPath = "/data/data/com.linuxdroid.app/files/environments/hardened-env/rootfs",
        command = listOf("/bin/sh", "-c", "echo hello"),
        architecture = Architecture.ARM64,
        workingDirectory = "/root",
    )

    @Before
    fun setup() {
        runtimeManager = DefaultRuntimeManager(backend, validator, commandBuilder)
        every { backend.getProotBinaryPath() } returns "/data/app/libproot.so"
        coEvery { validator.validate(any()) } returns Unit
    }

    @Test
    fun `showRuntimeCommand produces deterministic execution arguments without side-effects`() {
        val cmdString = runtimeManager.showRuntimeCommand(sampleSpec)

        assertThat(cmdString).contains("-0")
        assertThat(cmdString).contains("--kill-on-exit")
        assertThat(cmdString).contains("-r ${sampleSpec.rootfsPath}")
        assertThat(cmdString).contains("-w /root")
        assertThat(cmdString).contains("/bin/sh -c echo hello")

        // Verify strictly side-effect free: no ensureProotBinary, no start, no execute
        verify(exactly = 0) { backend.ensureProotBinary() }
        coVerify(exactly = 0) { backend.start(any()) }
        coVerify(exactly = 0) { backend.execute(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `executeAndWait matrix tests through RuntimeManager`() = runTest {
        val testMatrix = listOf(
            listOf("/bin/sh", "-c", "exit 0") to "OK",
            listOf("/bin/echo", "hello world") to "hello world\n",
            listOf("/bin/ls", "-la", "/") to "bin\netc\nusr\n",
            listOf("/usr/bin/env") to "PATH=/bin:/usr/bin\nUSER=root\n",
            listOf("/bin/sh", "/tmp/test.sh") to "SCRIPT_SUCCESS\n",
        )

        for ((command, expectedOutput) in testMatrix) {
            val spec = sampleSpec.copy(command = command)
            coEvery { backend.executeAndWaitWithSpec(spec, any()) } returns ProcessResult(
                handleId = "h-${command.first()}",
                exitCode = 0,
                stdout = expectedOutput,
                stderr = "",
            )

            val result = runtimeManager.executeAndWait(spec)

            assertThat(result.exitCode).isEqualTo(0)
            assertThat(result.stdout).isEqualTo(expectedOutput)
            assertThat(result.stderr).isEmpty()
        }
    }

    @Test
    fun `executeAndWait preserves non-zero guest exit codes like exit 42`() = runTest {
        val exit42Spec = sampleSpec.copy(command = listOf("/bin/sh", "-c", "exit 42"))
        coEvery { backend.executeAndWaitWithSpec(exit42Spec, any()) } returns ProcessResult(
            handleId = "h-exit42",
            exitCode = 42,
            stdout = "",
            stderr = "",
        )

        val result = runtimeManager.executeAndWait(exit42Spec)

        assertThat(result.exitCode).isEqualTo(42)
        assertThat(result.stdout).isEmpty()
    }

    @Test
    fun `validation failure prevents runtime execution`() = runTest {
        coEvery { validator.validate(any()) } throws RuntimeError(
            environmentId = sampleEnvironmentId,
            message = "Rootfs directory does not exist",
        )

        try {
            runtimeManager.execute(sampleSpec)
            assertThat(false).isTrue() // Should not reach here
        } catch (e: RuntimeError) {
            assertThat(e.message).contains("Rootfs directory does not exist")
        }

        coVerify(exactly = 0) { backend.executeWithSpec(any(), any()) }
    }
}
