package com.linuxdroid.core.runtime

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.PrebootError
import com.linuxdroid.core.model.PrebootErrorCode
import com.linuxdroid.core.model.RuntimeError
import com.linuxdroid.core.model.RuntimeSpec
import com.linuxdroid.native_bridge.NativeBridge
import java.io.File

/**
 * Raw handle of a launched PTY process: the guest PID and the master file descriptor.
 */
data class PtyLaunchHandle(
    val pid: Int,
    val masterFd: Int,
)

/**
 * Launches the resolved PRoot executable for a [RuntimeSpec].
 *
 * This separates the launch mechanics (command construction, environment
 * assembly, and process/PTY creation) from [ProotRuntimeBackend] orchestration
 * (process tracking, event emission, lifecycle). Both the process launcher and
 * the PTY launcher consume the same [RuntimeSpec] and the already-resolved PRoot
 * executable artifact through the authoritative [HostPreboot] stage.
 */
class RuntimeLauncher(
    private val commandBuilder: RuntimeCommandBuilder = ProotCommandBuilder(),
    private val preboot: HostPreboot = HostPreboot(commandBuilder),
) {
    private val log = LinuxDroidLogger(LogSubsystem.RUNTIME)

    /**
     * Launches a PRoot process in a normal (non-PTY) subprocess after running Host Preboot.
     *
     * @return the launched [Process].
     * @throws java.io.IOException if the process cannot be started.
     * @throws PrebootError if host preboot validation fails.
     */
    fun launchProcess(
        spec: RuntimeSpec,
        proot: File,
        loader: File?,
        rootfs: File,
        tmpDir: File,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): Process {
        val plan = preboot.prepare(spec, proot, loader, rootfs, tmpDir, logFile)

        log.info("[PROOT] Launching process: ${plan.commandLine.joinToString(" ")}")
        val processBuilder = ProcessBuilder(plan.commandLine)
            .directory(plan.workingDirectory)
            .redirectErrorStream(false)

        val env = processBuilder.environment()
        env.clear()
        env.putAll(plan.environment)

        return processBuilder.start()
    }

    /**
     * Launches a PRoot process inside a pseudo-terminal (PTY) after running Host Preboot.
     *
     * @return a [PtyLaunchHandle] carrying the guest PID and master fd.
     * @throws PrebootError if host preboot or PTY creation fails.
     */
    fun launchPty(
        spec: RuntimeSpec,
        proot: File,
        loader: File?,
        rootfs: File,
        tmpDir: File,
        rows: Int,
        cols: Int,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): PtyLaunchHandle {
        val plan = preboot.prepare(spec, proot, loader, rootfs, tmpDir, logFile)

        log.info("[PROOT] Launching PTY process: ${plan.commandLine.joinToString(" ")}")
        val envVars = plan.environment.map { (k, v) -> "$k=$v" }

        val outPidAndFd = IntArray(2)
        val res = NativeBridge.createPtyProcess(
            cmd = plan.commandLine.toTypedArray(),
            cwd = plan.workingDirectory.absolutePath,
            env = envVars.toTypedArray(),
            rows = rows,
            cols = cols,
            outPidAndFd = outPidAndFd,
        )
        if (res != 0) {
            log.error("[PROOT] Failed to create PTY process: errno $res")
            throw PrebootError(
                code = PrebootErrorCode.PROOT_START_FAILED,
                environmentId = spec.environmentId,
                detail = "Failed to create PTY process: errno $res",
            )
        }
        return PtyLaunchHandle(outPidAndFd[0], outPidAndFd[1])
    }

    /**
     * Constructs a pristine, isolated environment for the guest PRoot instance,
     * delegating to [HostPreboot.assembleHostEnvironment].
     */
    fun buildIsolatedEnvironment(
        spec: RuntimeSpec,
        loader: File?,
        tmpDir: File,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): Map<String, String> = preboot.assembleHostEnvironment(spec, loader, tmpDir, logFile)
}
