package com.linuxdroid.core.runtime

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
 * executable artifact, so the same launch plan drives both execution modes.
 *
 * No filesystem/binary discovery happens here: the PRoot executable path is
 * supplied by the caller (resolved through [RuntimeAssetsManager]).
 */
class RuntimeLauncher(
    private val commandBuilder: RuntimeCommandBuilder = ProotCommandBuilder(),
) {

    /**
     * Launches a PRoot process in a normal (non-PTY) subprocess.
     *
     * @return the launched [Process].
     * @throws java.io.IOException if the process cannot be started.
     */
    fun launchProcess(
        spec: RuntimeSpec,
        proot: File,
        loader: File?,
        rootfs: File,
        tmpDir: File,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): Process {
        val prootCmd = commandBuilder.build(spec, proot)
        val processBuilder = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .redirectErrorStream(false)

        val env = processBuilder.environment()
        // Sanitize environment: isolate from Android host process environment completely
        env.clear()
        env.putAll(buildIsolatedEnvironment(spec, loader, tmpDir, logFile))

        return processBuilder.start()
    }

    /**
     * Launches a PRoot process inside a pseudo-terminal (PTY).
     *
     * @return a [PtyLaunchHandle] carrying the guest PID and master fd.
     * @throws RuntimeError if PTY creation fails.
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
        val prootCmd = commandBuilder.build(spec, proot)
        val envMap = buildIsolatedEnvironment(spec, loader, tmpDir, logFile)
        val envVars = envMap.map { (k, v) -> "$k=$v" }

        val outPidAndFd = IntArray(2)
        val res = NativeBridge.createPtyProcess(
            cmd = prootCmd.toTypedArray(),
            cwd = rootfs.absolutePath,
            env = envVars.toTypedArray(),
            rows = rows,
            cols = cols,
            outPidAndFd = outPidAndFd,
        )
        if (res != 0) {
            throw RuntimeError(
                environmentId = spec.environmentId,
                message = "Failed to create PTY process: errno $res",
            )
        }
        return PtyLaunchHandle(outPidAndFd[0], outPidAndFd[1])
    }

    /**
     * Constructs a pristine, isolated environment for the guest PRoot instance,
     * stripping all host Bionic / Android variables while retaining guest-specific configuration.
     */
    fun buildIsolatedEnvironment(
        spec: RuntimeSpec,
        loader: File?,
        tmpDir: File,
        logFile: File? = spec.logFilePath?.let { File(it) },
    ): Map<String, String> = buildMap {
        put("PROOT_TMP_DIR", tmpDir.absolutePath)
        if (loader?.exists() == true) {
            put("PROOT_LOADER", loader.absolutePath)
        }
        val targetLog = logFile?.absolutePath ?: spec.logFilePath
        if (targetLog != null) {
            File(targetLog).parentFile?.mkdirs()
            put("PROOT_LOG_FILE", targetLog)
        }
        if (!spec.environmentVariables.containsKey("PROOT_VERBOSE")) {
            put("PROOT_VERBOSE", "9")
        }
        // Disable PRoot seccomp BPF accelerator by default on Android to avoid a
        // BPF filter killing modern glibc/musl dynamic linker syscalls with
        // SIGSYS (signal 31). Overridable by an explicitly provided env var.
        if (!spec.environmentVariables.containsKey("PROOT_NO_SECCOMP")) {
            put("PROOT_NO_SECCOMP", "1")
        }
        spec.environmentVariables.forEach { (k, v) ->
            if (k != "LD_PRELOAD" && k != "LD_LIBRARY_PATH") {
                put(k, v)
            }
        }
    }
}
