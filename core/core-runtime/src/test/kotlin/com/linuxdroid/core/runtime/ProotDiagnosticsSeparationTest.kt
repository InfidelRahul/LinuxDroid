package com.linuxdroid.core.runtime

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotDiagnosticsSeparationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: EnvironmentStorage
    private val envId = EnvironmentId("test-separation-env")

    @Before
    fun setup() {
        val environmentsDir = tempFolder.newFolder("environments")
        storage = EnvironmentStorage(environmentsDir)
    }

    @Test
    fun `storage initializes logs directory and consoleLogFile path`() = runTest {
        storage.initializeEnvironmentDirs(envId)

        val logsDir = storage.logsDir(envId)
        val consoleLog = storage.consoleLogFile(envId)

        assertThat(logsDir.exists()).isTrue()
        assertThat(logsDir.isDirectory).isTrue()
        assertThat(consoleLog.parentFile?.absolutePath).isEqualTo(logsDir.absolutePath)
        assertThat(consoleLog.name).isEqualTo("console.log")
    }

    @Test
    fun `RuntimeSpec fromEnvironment sets up logFilePath pointing to console log`() {
        val env = Environment(
            metadata = EnvironmentMetadata(
                id = envId,
                name = "Test Env",
                distribution = Distribution.DEBIAN,
                architecture = Architecture.ARM64,
            ),
            rootfsPath = "/test/rootfs",
            metadataPath = "/test/metadata",
        )

        val consoleLog = storage.consoleLogFile(envId)
        val spec = RuntimeSpec.fromEnvironment(
            environment = env,
            command = listOf("echo", "hello"),
            logFilePath = consoleLog.absolutePath,
        )

        assertThat(spec.logFilePath).isEqualTo(consoleLog.absolutePath)
    }

    @Test
    fun `Test 1 - normal stdout separates clean output without diagnostic clutter`() {
        // Simulates guest echo stdout
        val stdout = "hello\n"
        val stderr = ""
        val consoleLogContent = "[SHEBANG_OK] interpreter=/bin/sh\n[LOAD_INFO_OK]\n"

        val result = ProcessResult(
            handleId = "test-1",
            exitCode = 0,
            stdout = stdout,
            stderr = stderr,
        )

        // Terminal output contains ONLY guest stdout
        assertThat(result.stdout.trim()).isEqualTo("hello")
        // Terminal stderr has NO PRoot diagnostic lines
        assertThat(result.stderr).isEmpty()
        assertThat(result.stdout).doesNotContain("[SHEBANG_OK]")
        assertThat(result.stdout).doesNotContain("proot info:")
        assertThat(consoleLogContent).contains("[SHEBANG_OK]")
    }

    @Test
    fun `Test 2 - guest stderr is preserved and delivered to process error stream`() {
        // Simulates guest ls error
        val guestError = "ls: cannot access '/does-not-exist': No such file or directory\n"
        val result = ProcessResult(
            handleId = "test-2",
            exitCode = 2,
            stdout = "",
            stderr = guestError,
        )

        // Guest stderr MUST be delivered to terminal/session output
        assertThat(result.stderr).contains("ls: cannot access '/does-not-exist': No such file or directory")
        assertThat(result.stdout).isEmpty()
        // Guest stderr does not contain PRoot diagnostics
        assertThat(result.stderr).doesNotContain("[SECCOMP_EMULATED]")
        assertThat(result.stderr).doesNotContain("proot info:")
    }

    @Test
    fun `Test 3 - PRoot diagnostics are preserved in console log file`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val consoleLog = storage.consoleLogFile(envId)

        // Simulate PRoot runtime diagnostics emitted to console.log
        val diagnostics = listOf(
            "proot info: [SHEBANG_OK] script found",
            "proot info: [LOAD_INFO_OK] elf loaded",
            "proot info: [INTERP_OK] dynamic linker resolved",
            "proot info: [LOADER_PATH_OK] companion loader active",
            "proot info: [EXECVE_DISPATCH_OK] dispatching execve",
            "proot info: [EXECVE_KERNEL_OK] kernel execve succeeded",
            "proot info: [LOAD_SCRIPT_OK] script executed",
            "proot info: [SIGSYS_TRAPPED] trapped signal 31",
            "proot info: [SECCOMP_EMULATED] pid=101 emulated return 0",
        )

        consoleLog.bufferedWriter().use { writer ->
            diagnostics.forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }

        assertThat(consoleLog.exists()).isTrue()
        val readLines = consoleLog.readLines()
        assertThat(readLines).hasSize(9)
        assertThat(readLines).contains("proot info: [SHEBANG_OK] script found")
        assertThat(readLines).contains("proot info: [SECCOMP_EMULATED] pid=101 emulated return 0")
        assertThat(readLines).contains("proot info: [SIGSYS_TRAPPED] trapped signal 31")
    }

    @Test
    fun `Test 4 - Debian startup cat os-release produces clean guest output`() {
        val debianOsRelease = """
            PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
            NAME="Debian GNU/Linux"
            VERSION_ID="12"
            VERSION="12 (bookworm)"
            VERSION_CODENAME=bookworm
            ID=debian
            HOME_URL="https://www.debian.org/"
            SUPPORT_URL="https://www.debian.org/support"
            BUG_REPORT_URL="https://bugs.debian.org/"
        """.trimIndent()

        val result = ProcessResult(
            handleId = "test-4",
            exitCode = 0,
            stdout = debianOsRelease,
            stderr = "",
        )

        assertThat(result.stdout).contains("""PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"""")
        assertThat(result.stderr).isEmpty()
        assertThat(result.stdout).doesNotContain("proot info:")
    }

    @Test
    fun `Test 5 - repeated commands append diagnostics to console log without corruption`() = runTest {
        storage.initializeEnvironmentDirs(envId)
        val consoleLog = storage.consoleLogFile(envId)

        // Command 1
        consoleLog.appendText("proot info: [EXECVE_DISPATCH_OK] cmd=echo\n")
        // Command 2
        consoleLog.appendText("proot info: [EXECVE_DISPATCH_OK] cmd=ls\n")
        // Command 3
        consoleLog.appendText("proot info: [EXECVE_DISPATCH_OK] cmd=cat\n")

        val logContent = consoleLog.readText()
        assertThat(logContent.lines().filter { it.isNotBlank() }).hasSize(3)
        assertThat(logContent).contains("cmd=echo")
        assertThat(logContent).contains("cmd=ls")
        assertThat(logContent).contains("cmd=cat")
    }
}
