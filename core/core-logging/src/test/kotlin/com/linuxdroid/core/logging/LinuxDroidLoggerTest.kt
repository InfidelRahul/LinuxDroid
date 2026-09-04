package com.linuxdroid.core.logging

import com.google.common.truth.Truth.assertThat
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.SessionId
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinuxDroidLoggerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var logsDir: File
    private val envId = EnvironmentId("test-logging-env")

    @Before
    fun setUp() {
        LogConfig.resetToDefaults()
        logsDir = tempFolder.newFolder("logs")
        LogFileManager.registerEnvironmentLogsDir(envId, logsDir)
    }

    @After
    fun tearDown() {
        LogConfig.resetToDefaults()
    }

    @Test
    fun `generate_log defaults to true and enables file writing`() {
        assertThat(LogConfig.generate_log).isTrue()
        assertThat(LogConfig.isLoggingEnabled).isTrue()

        val logger = LinuxDroidLogger(LogSubsystem.SESSION, envId, category = LogCategory.SESSION)
        logger.info("Session started successfully", details = mapOf("stage" to "INITIALIZING"))

        val sessionFile = File(logsDir, "session.log")
        assertThat(sessionFile.exists()).isTrue()
        val content = sessionFile.readText()
        assertThat(content).contains("Session started successfully")
        assertThat(content).contains("details={stage=INITIALIZING}")
        assertThat(content).contains("[SESSION/SESSION/env:test-logging-env]")
    }

    @Test
    fun `generate_log false completely disables file writing`() {
        LogConfig.generate_log = false
        assertThat(LogConfig.isLoggingEnabled).isFalse()

        val logger = LinuxDroidLogger(LogSubsystem.SESSION, envId, category = LogCategory.SESSION)
        logger.info("This should not be written")

        val sessionFile = File(logsDir, "session.log")
        assertThat(sessionFile.exists()).isFalse()

        // Also test terminal stream is bypassed
        LogFileManager.appendTerminalText(envId, "terminal output\n")
        val termFile = File(logsDir, "terminal.log")
        assertThat(termFile.exists()).isFalse()
    }

    @Test
    fun `categorized logging routes entries to specific log files`() {
        val sessionLogger = LinuxDroidLogger(LogSubsystem.SESSION, envId, category = LogCategory.SESSION)
        val prebootLogger = LinuxDroidLogger(LogSubsystem.RUNTIME, envId, category = LogCategory.PREBOOT)
        val guestInitLogger = LinuxDroidLogger(LogSubsystem.RUNTIME, envId, category = LogCategory.GUEST_INIT)
        val processLogger = LinuxDroidLogger(LogSubsystem.PROCESS, envId, category = LogCategory.SYSTEM_PROCESS)

        sessionLogger.info("Starting background session step 1")
        prebootLogger.info("Rootfs and PRoot binary verified")
        guestInitLogger.info("Executing /etc/linuxdroid/init.d/01-setup.sh")
        processLogger.info("Process spawned pid=123 cmd=uname -a")

        val sessionLog = File(logsDir, "session.log")
        val prebootLog = File(logsDir, "preboot.log")
        val guestInitLog = File(logsDir, "guest_init.log")
        val processLog = File(logsDir, "process.log")

        assertThat(sessionLog.exists()).isTrue()
        assertThat(sessionLog.readText()).contains("Starting background session step 1")

        assertThat(prebootLog.exists()).isTrue()
        assertThat(prebootLog.readText()).contains("Rootfs and PRoot binary verified")

        assertThat(guestInitLog.exists()).isTrue()
        assertThat(guestInitLog.readText()).contains("Executing /etc/linuxdroid/init.d/01-setup.sh")

        assertThat(processLog.exists()).isTrue()
        assertThat(processLog.readText()).contains("Process spawned pid=123 cmd=uname -a")
    }

    @Test
    fun `error logging captures error code, details map, and throwable stack trace`() {
        val logger = LinuxDroidLogger(LogSubsystem.PROCESS, envId, category = LogCategory.SYSTEM_PROCESS)
        val failureEx = IllegalStateException("Mock kernel syscall failure")

        logger.error(
            message = "Process crashed during execution",
            throwable = failureEx,
            errorCode = 139,
            details = mapOf("signal" to "SIGSEGV", "cmd" to "apt-get update")
        )

        val processLog = File(logsDir, "process.log")
        assertThat(processLog.exists()).isTrue()
        val content = processLog.readText()

        assertThat(content).contains("Process crashed during execution")
        assertThat(content).contains("/err:139")
        assertThat(content).contains("details={signal=SIGSEGV, cmd=apt-get update}")
        assertThat(content).contains("IllegalStateException: Mock kernel syscall failure")
        assertThat(content).contains("com.linuxdroid.core.logging.LinuxDroidLoggerTest")
    }

    @Test
    fun `terminal streaming captures full command installation output without ANSI noise`() {
        val aptOutput = "\u001B[2K\rReading package lists... Done\n" +
                "Building dependency tree... Done\n" +
                "The following NEW packages will be installed: nano\n" +
                "Setting up nano (7.2-1) ...\n"

        val bytes = aptOutput.toByteArray(Charsets.UTF_8)
        LogFileManager.appendTerminalBytes(envId, bytes, 0, bytes.size)

        val terminalLog = File(logsDir, "terminal.log")
        val consoleLog = File(logsDir, "console.log")

        assertThat(terminalLog.exists()).isTrue()
        assertThat(consoleLog.exists()).isTrue()

        val text = terminalLog.readText()
        assertThat(text).contains("Reading package lists... Done")
        assertThat(text).contains("The following NEW packages will be installed: nano")
        assertThat(text).contains("Setting up nano (7.2-1) ...")
        assertThat(text).doesNotContain("\u001B[2K")
    }
}
