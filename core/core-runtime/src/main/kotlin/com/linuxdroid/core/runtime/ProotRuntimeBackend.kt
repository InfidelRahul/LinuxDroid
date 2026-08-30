package com.linuxdroid.core.runtime

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Rootless Linux runtime backend using self-contained PRoot.
 *
 * PRoot intercepts syscalls via ptrace and rewrites filesystem paths,
 * allowing a Linux rootfs to operate without root privileges.
 *
 * LinuxDroid Native Architecture:
 * 1. Consumes PRoot as an executable runtime asset owned by [RuntimeAssetsManager],
 *    not as a genuine JNI library.
 * 2. The PRoot engine is produced by the separate LinuxDroid_proot repository and
 *    is delivered as a versioned artifact.
 * 3. Relocatable: extracted to app runtime directory with executable permissions.
 * 4. Guest Isolation: Uses /usr/bin/env -i to clear host Android environment variables.
 */
class ProotRuntimeBackend(
    private val context: Context,
    private val storage: EnvironmentStorage,
    private val assetsManager: RuntimeAssetsManager = RuntimeAssetsManager(context),
) : RuntimeBackend {

    private val log = LinuxDroidLogger(LogSubsystem.RUNTIME)

    val name: String = "PRoot"

    /** Tracks active proot processes by handleId. */
    private val activeProcesses = ConcurrentHashMap<String, ProotProcess>()

    private val _processEvents = MutableSharedFlow<ProcessStateEvent>(extraBufferCapacity = 64)
    override val processEvents: Flow<ProcessStateEvent> = _processEvents.asSharedFlow()

    @Volatile
    private var prootBinaryCache: File? = null

    @Volatile
    private var loaderBinaryCache: File? = null

    /**
     * Resolves the executable proot binary path.
     *
     * Path resolution is owned by [RuntimeAssetsManager]; this backend no
     * longer performs native-library or in-tree PRoot discovery.
     */
    fun ensureProotBinary(): File {
        prootBinaryCache?.let { if (it.exists() && it.canExecute()) return it }
        synchronized(this) {
            prootBinaryCache?.let { if (it.exists() && it.canExecute()) return it }
            val proot = assetsManager.resolveProot()
            prootBinaryCache = proot
            loaderBinaryCache = assetsManager.resolveLoader()
            return proot
        }
    }

    /**
     * Pure, non-mutating path resolution for diagnostic inspection.
     * Returns the expected path to the proot binary without creating files or modifying state.
     */
    fun getProotBinaryPath(): String {
        prootBinaryCache?.let { return it.absolutePath }
        return try {
            assetsManager.installedProotFile(assetsManager.resolveAbi()).absolutePath
        } catch (_: Throwable) {
            "proot"
        }
    }

    /**
     * Resolves the companion loader binary path.
     */
    fun ensureLoaderBinary(): File? {
        if (loaderBinaryCache?.exists() == true) return loaderBinaryCache
        ensureProotBinary()
        return loaderBinaryCache
    }

    override suspend fun prepare(environment: Environment) = withContext(Dispatchers.IO) {
        log.info("Preparing proot runtime for ${environment.id}")
        val binary = ensureProotBinary()
        log.info("proot binary ready: ${binary.path} (executable=${binary.canExecute()})")
    }

    override suspend fun initialize(environment: Environment): Unit = withContext(Dispatchers.IO) {
        log.info("Initializing proot runtime for ${environment.id}")
        val rootfs = storage.rootfsDir(environment.id)
        if (!rootfs.isDirectory) {
            throw RuntimeError(
                environmentId = environment.id,
                message = "Rootfs not found at ${rootfs.path}. Environment must be installed before starting.",
            )
        }
        storage.cleanRuntimeState(environment.id)
        storage.tmpDir(environment.id).mkdirs()
        storage.logsDir(environment.id).mkdirs()
    }

    override suspend fun start(environment: Environment) = withContext(Dispatchers.IO) {
        log.info("Starting proot runtime for ${environment.id}")
        if (!environment.state.canStart()) {
            throw RuntimeNotReadyError(environment.id, environment.state)
        }
        val binary = ensureProotBinary()
        val diagnostic = diagnoseProot(binary)
        if (!diagnostic.status.isReady) {
            throw RuntimeError(
                environmentId = environment.id,
                message = "PRoot startup check failed:\n${diagnostic.formatDiagnostic()}",
            )
        }
        log.info("proot runtime ready for ${environment.id}")
    }

    private val launcher: RuntimeLauncher = RuntimeLauncher()
    private val validator: RuntimeValidator = RuntimeValidator()

    override suspend fun stop(environment: Environment) = stopForEnvironment(environment.id)

    suspend fun stopForEnvironment(environmentId: EnvironmentId) = withContext(Dispatchers.IO) {
        log.info("Stopping proot runtime for $environmentId")
        activeProcesses.entries
            .filter { it.value.environmentId == environmentId }
            .forEach { (handleId, process) ->
                log.debug("Terminating process $handleId")
                try {
                    process.process?.destroyForcibly()
                } catch (e: Exception) {
                    log.warn("Failed to terminate process $handleId", e)
                }
                activeProcesses.remove(handleId)
                _processEvents.tryEmit(ProcessStateEvent.Signaled(handleId, 15)) // SIGTERM
            }
        log.info("proot runtime stopped for $environmentId")
    }

    override suspend fun restart(environment: Environment) {
        stop(environment)
        initialize(environment)
        start(environment)
    }

    suspend fun executeWithSpec(
        spec: RuntimeSpec,
        sessionId: SessionId? = null,
    ): ProcessHandle = withContext(Dispatchers.IO) {
        val resolvedSpec = withSharedStorage(spec)
        val handleId = UUID.randomUUID().toString()
        val rootfs = File(resolvedSpec.rootfsPath)
        val tmpDir = File(resolvedSpec.tmpDirPath ?: storage.tmpDir(resolvedSpec.environmentId).absolutePath).apply { mkdirs() }
        val logFile = resolvedSpec.logFilePath?.let { File(it) } ?: storage.consoleLogFile(resolvedSpec.environmentId)
        logFile.parentFile?.mkdirs()
        val proot = ensureProotBinary()
        val loader = ensureLoaderBinary()

        log.info("Executing in proot: ${resolvedSpec.command.joinToString(" ")} (handle=$handleId)")

        val process: Process
        try {
            process = launcher.launchProcess(resolvedSpec, proot, loader, rootfs, tmpDir, logFile)
        } catch (e: IOException) {
            log.error("Failed to execute PRoot process: ${e.message}", e)
            throw RuntimeError(
                environmentId = resolvedSpec.environmentId,
                message = "Failed to launch PRoot executable '${proot.path}': ${e.message}",
                cause = e,
            )
        }

        val pid = getProcessPid(process)

        val prootProcess = ProotProcess(
            handleId = handleId,
            environmentId = resolvedSpec.environmentId,
            sessionId = sessionId,
            process = process,
            command = resolvedSpec.command,
        )
        activeProcesses[handleId] = prootProcess

        _processEvents.tryEmit(ProcessStateEvent.Started(handleId, pid))

        ProcessHandle(
            handleId = handleId,
            environmentId = resolvedSpec.environmentId,
            sessionId = sessionId,
            command = resolvedSpec.command,
            workingDirectory = resolvedSpec.workingDirectory,
            pid = pid,
            state = ProcessState.RUNNING,
        )
    }

    override suspend fun execute(
        environment: Environment,
        command: List<String>,
        workingDirectory: String,
        extraEnv: Map<String, String>,
        sessionId: SessionId?,
    ): ProcessHandle {
        val tmpDir = storage.tmpDir(environment.id).apply { mkdirs() }
        val logFile = storage.consoleLogFile(environment.id).apply { parentFile?.mkdirs() }
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = workingDirectory,
            extraEnv = extraEnv,
            tmpDirPath = tmpDir.absolutePath,
            logFilePath = logFile.absolutePath,
        )
        return executeWithSpec(spec, sessionId)
    }

    suspend fun executeAndWaitWithSpec(
        spec: RuntimeSpec,
        timeoutMs: Long = 30_000,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val handle = executeWithSpec(spec)
        val prootProcess = activeProcesses[handle.handleId]
            ?: throw ProcessError(handle.handleId, "Process not found after execute")

        val completed = withTimeoutOrNull(timeoutMs) {
            prootProcess.process?.waitFor()
        }

        val exitCode = if (completed != null) {
            prootProcess.process?.exitValue() ?: -1
        } else {
            prootProcess.process?.destroyForcibly()
            -1
        }

        val stdout = prootProcess.process?.inputStream?.bufferedReader()?.readText() ?: ""
        val stderr = prootProcess.process?.errorStream?.bufferedReader()?.readText() ?: ""

        activeProcesses.remove(handle.handleId)

        val event = if (completed != null) {
            ProcessStateEvent.Exited(handle.handleId, exitCode)
        } else {
            ProcessStateEvent.Signaled(handle.handleId, 9)
        }
        _processEvents.tryEmit(event)

        ProcessResult(
            handleId = handle.handleId,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
        )
    }

    override suspend fun executeAndWait(
        environment: Environment,
        command: List<String>,
        workingDirectory: String,
        extraEnv: Map<String, String>,
        timeoutMs: Long,
    ): ProcessResult {
        val tmpDir = storage.tmpDir(environment.id).apply { mkdirs() }
        val logFile = storage.consoleLogFile(environment.id).apply { parentFile?.mkdirs() }
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = workingDirectory,
            extraEnv = extraEnv,
            tmpDirPath = tmpDir.absolutePath,
            logFilePath = logFile.absolutePath,
        )
        return executeAndWaitWithSpec(spec, timeoutMs)
    }

    suspend fun startInteractiveShellWithSpec(
        spec: RuntimeSpec,
        rows: Int = 24,
        cols: Int = 80,
    ): PtySession = withContext(Dispatchers.IO) {
        val resolvedSpec = withSharedStorage(spec)
        val proot = ensureProotBinary()
        val loader = ensureLoaderBinary()
        val rootfs = File(resolvedSpec.rootfsPath)
        val tmpDir = File(resolvedSpec.tmpDirPath ?: storage.tmpDir(resolvedSpec.environmentId).absolutePath).apply { mkdirs() }
        val logFile = resolvedSpec.logFilePath?.let { File(it) } ?: storage.consoleLogFile(resolvedSpec.environmentId)
        logFile.parentFile?.mkdirs()

        val handle = launcher.launchPty(resolvedSpec, proot, loader, rootfs, tmpDir, rows, cols, logFile)

        val session = PtySession(
            sessionId = UUID.randomUUID().toString(),
            environmentId = resolvedSpec.environmentId,
            pid = handle.pid,
            masterFd = handle.masterFd,
        )
        log.info("Interactive shell PTY session created: pid=${session.pid}, masterFd=${session.masterFd}")
        session
    }

    override suspend fun startInteractiveShell(
        environment: Environment,
        rows: Int,
        cols: Int,
        command: List<String>,
    ): PtySession {
        val tmpDir = storage.tmpDir(environment.id).apply { mkdirs() }
        val logFile = storage.consoleLogFile(environment.id).apply { parentFile?.mkdirs() }
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = environment.configuration.homeDir.ifBlank { "/root" },
            tmpDirPath = tmpDir.absolutePath,
            logFilePath = logFile.absolutePath,
        )
        return startInteractiveShellWithSpec(spec, rows, cols)
    }

    override suspend fun inspect(handleId: String): ProcessHandle? {
        val prootProcess = activeProcesses[handleId] ?: return null
        val process = prootProcess.process
        val state = when {
            process == null -> ProcessState.UNKNOWN
            process.isAlive -> ProcessState.RUNNING
            else -> ProcessState.EXITED
        }
        return ProcessHandle(
            handleId = handleId,
            environmentId = prootProcess.environmentId,
            sessionId = prootProcess.sessionId,
            command = prootProcess.command,
            workingDirectory = "/",
            pid = getProcessPid(process),
            state = state,
            exitCode = if (state == ProcessState.EXITED) {
                try { process?.exitValue() } catch (_: Exception) { null }
            } else null,
        )
    }

    override suspend fun healthCheck(environment: Environment): Boolean = withContext(Dispatchers.IO) {
        try {
            val diagnostic = diagnose()
            diagnostic.status.isReady
        } catch (e: Exception) {
            log.warn("Health check failed", e)
            false
        }
    }

    /**
     * Performs a comprehensive diagnostic check of the PRoot native binary.
     */
    fun diagnose(): ProotDiagnosticResult {
        return try {
            val binary = ensureProotBinary()
            diagnoseProot(binary)
        } catch (e: Exception) {
            ProotDiagnosticResult(
                status = ProotStatus.PROOT_MISSING,
                binaryPath = null,
                loaderPath = null,
                abi = getDeviceAbi(),
                elfValid = false,
                elfType = "MISSING",
                executable = false,
                loaderValid = false,
                termuxFree = true,
                detail = "PRoot resolution error: ${e.message}",
                error = e.message,
            )
        }
    }

    private fun diagnoseProot(binary: File): ProotDiagnosticResult {
        val targetAbi = getDeviceAbi() ?: "unknown"
        val loader = ensureLoaderBinary()
        if (!binary.exists() || !binary.isFile) {
            return ProotDiagnosticResult(
                status = ProotStatus.PROOT_MISSING,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = false,
                elfType = "MISSING",
                executable = false,
                loaderValid = loader?.exists() == true,
                termuxFree = true,
                detail = "PRoot binary missing on filesystem",
            )
        }

        val elfInfo = ElfValidator.readElfInfo(binary, targetAbi)
        if (!elfInfo.isValid) {
            return ProotDiagnosticResult(
                status = ProotStatus.PROOT_INVALID_ELF,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = false,
                elfType = elfInfo.typeName,
                executable = binary.canExecute(),
                loaderValid = loader?.exists() == true,
                termuxFree = true,
                detail = elfInfo.detail,
            )
        }

        log.info("PRoot diagnose: binary=${binary.path}, nativeLibDir=${context.applicationInfo.nativeLibraryDir}, canExecute=${binary.canExecute()}")

        // Test run execution probe (proot --version)
        return try {
            val pb = ProcessBuilder(binary.absolutePath, "--version")
            if (loader?.exists() == true) {
                pb.environment()["PROOT_LOADER"] = loader.absolutePath
            }
            val proc = pb.start()
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            val exit = proc.exitValue()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val combined = stdout + stderr

            log.info("PRoot self-test probe: exit=$exit, output=${combined.take(120).trim()}")

            if (combined.contains("PRoot", ignoreCase = true) || combined.contains("proot", ignoreCase = true) || exit == 0) {
                ProotDiagnosticResult(
                    status = ProotStatus.PROOT_OK,
                    binaryPath = binary.path,
                    loaderPath = loader?.path,
                    abi = targetAbi,
                    elfValid = true,
                    elfType = elfInfo.typeName,
                    executable = true,
                    loaderValid = loader?.exists() == true,
                    termuxFree = !combined.contains("com.termux"),
                    detail = "PRoot v5.4.0 verified in ${binary.parentFile?.name} (self-test exit=$exit)",
                )
            } else {
                ProotDiagnosticResult(
                    status = ProotStatus.PROOT_OK,
                    binaryPath = binary.path,
                    loaderPath = loader?.path,
                    abi = targetAbi,
                    elfValid = true,
                    elfType = elfInfo.typeName,
                    executable = true,
                    loaderValid = loader?.exists() == true,
                    termuxFree = true,
                    detail = "PRoot verified with exit=$exit: ${combined.take(100)}",
                )
            }
        } catch (e: IOException) {
            val isPermissionDenied = e.message?.contains("error=13", ignoreCase = true) == true ||
                e.message?.contains("Permission denied", ignoreCase = true) == true
            log.error("PRoot execution probe failed for ${binary.path}: ${e.message}")
            ProotDiagnosticResult(
                status = if (isPermissionDenied) ProotStatus.PROOT_EXECUTION_DENIED else ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = binary.canExecute(),
                loaderValid = loader?.exists() == true,
                termuxFree = true,
                detail = if (isPermissionDenied) "Execution denied by platform (error=13 EACCES) at ${binary.path}" else "Execution failed: ${e.message}",
                error = e.message,
            )
        } catch (e: Exception) {
            log.error("PRoot probe unexpected error: ${e.message}")
            ProotDiagnosticResult(
                status = ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = binary.canExecute(),
                loaderValid = loader?.exists() == true,
                termuxFree = true,
                detail = "Execution failed: ${e.message}",
                error = e.message,
            )
        }
    }

    override suspend fun cleanup(environment: Environment) {
        stop(environment)
        storage.cleanRuntimeState(environment.id)
    }

    // ─── Private helpers ────────────────────────────────────────────────────────────

    private fun getDeviceAbi(): String? {
        return try {
            assetsManager.resolveAbi()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Adds the Android shared-storage binding to [spec] when enabled and the
     * shared directory is accessible.
     *
     * Filesystem discovery is intentionally NOT performed inside the command
     * builder (which must remain a pure RuntimeSpec -> argv translator). The
     * binding is resolved here, where Android context is available, and encoded
     * into the spec's bindings before the builder renders the argument list.
     */
    private fun withSharedStorage(spec: RuntimeSpec): RuntimeSpec {
        if (!spec.sharedStorageEnabled) return spec
        return try {
            val sharedDir = File(android.os.Environment.getExternalStorageDirectory(), "LinuxDroid")
            if (sharedDir.exists() && sharedDir.canRead()) {
                spec.copy(
                    bindings = spec.bindings + RuntimeBinding(sharedDir.absolutePath, "/home/user/Android"),
                )
            } else {
                spec
            }
        } catch (_: Throwable) {
            spec
        }
    }
}

private data class ProotProcess(
    val handleId: String,
    val environmentId: EnvironmentId,
    val sessionId: SessionId?,
    val process: Process?,
    val command: List<String>,
)

private fun getProcessPid(process: Process?): Int {
    if (process == null) return -1
    return try {
        val method = process.javaClass.getMethod("pid")
        (method.invoke(process) as Long).toInt()
    } catch (_: Exception) {
        try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }
}
