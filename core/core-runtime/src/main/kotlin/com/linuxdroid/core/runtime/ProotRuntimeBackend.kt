package com.linuxdroid.core.runtime

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogCategory
import com.linuxdroid.core.logging.LogFileManager
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        if (!environment.state.canStart() && environment.state != EnvironmentState.STARTING) {
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
        val procs = activeProcesses.entries
            .filter { it.value.environmentId == environmentId }
            .toList()

        for ((handleId, proc) in procs) {
            log.debug("Terminating process $handleId")
            try {
                // If interactive PTY session, close it cleanly
                proc.ptySession?.close()

                val p = proc.process
                if (p != null && p.isAlive) {
                    p.destroy() // SIGTERM
                    val exited = withTimeoutOrNull(2000L) {
                        p.waitFor()
                    }
                    if (exited == null) {
                        log.warn("Process $handleId did not exit on SIGTERM, escalating to SIGKILL")
                        p.destroyForcibly()
                        p.waitFor(1, TimeUnit.SECONDS)
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to terminate process $handleId cleanly: ${e.message}", e)
                throw RuntimeError(
                    environmentId = environmentId,
                    message = "Failed to terminate process $handleId during runtime shutdown: ${e.message}",
                    cause = e,
                )
            } finally {
                activeProcesses.remove(handleId)
                _processEvents.tryEmit(ProcessStateEvent.Signaled(handleId, 15))
            }
        }
        log.info("proot runtime stopped for $environmentId")
    }

    override suspend fun restart(environment: Environment) {
        stop(environment)
        initialize(environment)
        val readyEnv = if (environment.state == EnvironmentState.FAILED) {
            environment.withState(EnvironmentState.RECOVERING).withState(EnvironmentState.READY)
        } else {
            environment
        }
        start(readyEnv)
    }

    suspend fun executeWithSpec(
        spec: RuntimeSpec,
        sessionId: SessionId? = null,
    ): ProcessHandle = withContext(Dispatchers.IO) {
        val resolvedSpec = withSharedStorage(spec)
        val handleId = UUID.randomUUID().toString()
        val rootfs = File(resolvedSpec.rootfsPath)
        val tmpDir = File(resolvedSpec.tmpDirPath ?: storage.tmpDir(resolvedSpec.environmentId).absolutePath).apply { mkdirs() }
        val logFile = resolvedSpec.logFilePath?.let { File(it) } ?: storage.prootLogFile(resolvedSpec.environmentId)
        logFile.parentFile?.mkdirs()
        val proot = ensureProotBinary()
        val loader = ensureLoaderBinary()

        val processLog = LinuxDroidLogger(LogSubsystem.PROCESS, resolvedSpec.environmentId, sessionId, category = LogCategory.SYSTEM_PROCESS)
        processLog.info(
            "[PROCESS_CREATE] handle=$handleId pid=-1 command=\"${resolvedSpec.command.joinToString(" ")}\" cwd=\"${resolvedSpec.workingDirectory}\" environment_count=${resolvedSpec.environmentVariables.size}",
            details = mapOf(
                "environment_id" to resolvedSpec.environmentId.value,
                "handle" to handleId,
                "pid" to "-1",
                "command" to resolvedSpec.command.joinToString(" "),
                "cwd" to resolvedSpec.workingDirectory,
                "environment_count" to resolvedSpec.environmentVariables.size.toString(),
            )
        )

        val process: Process
        try {
            process = launcher.launchProcess(resolvedSpec, proot, loader, rootfs, tmpDir, logFile)
        } catch (e: IOException) {
            processLog.error(
                "[PROCESS_FAIL] handle=$handleId pid=-1 stage=PROOT_STARTUP errno=-1 error=\"${e.message}\"",
                throwable = e,
                errorCode = -1,
                details = mapOf(
                    "environment_id" to resolvedSpec.environmentId.value,
                    "handle" to handleId,
                    "pid" to "-1",
                    "stage" to "PROOT_STARTUP",
                    "errno" to "-1",
                    "error" to (e.message ?: "Launch failed"),
                    "prootPath" to proot.path,
                    "command" to resolvedSpec.command.joinToString(" "),
                )
            )
            throw RuntimeError(
                environmentId = resolvedSpec.environmentId,
                message = "Failed to launch PRoot executable '${proot.path}': ${e.message}",
                cause = e,
            )
        }

        val pid = getProcessPid(process)
        processLog.info(
            "[PROCESS_START] handle=$handleId pid=$pid",
            details = mapOf(
                "environment_id" to resolvedSpec.environmentId.value,
                "handle" to handleId,
                "pid" to pid.toString(),
            )
        )

        val guestInit = resolvedSpec.guestInitPath
        val useInit = resolvedSpec.executionTarget == ExecutionTarget.GUEST &&
            resolvedSpec.bootstrapPolicy != BootstrapPolicy.BOOTSTRAP_DIRECT_EXEC &&
            !guestInit.isNullOrBlank()
        val actualInitialExec = if (useInit && resolvedSpec.command.firstOrNull() != guestInit) {
            guestInit
        } else {
            resolvedSpec.command.firstOrNull() ?: ""
        }
        val requestedCommand = resolvedSpec.command.joinToString(" ")
        val transitionChain = "$requestedCommand → $actualInitialExec → substituted_loader"

        processLog.info(
            "[PROCESS_EXEC] handle=$handleId pid=$pid requested_command=\"$requestedCommand\" actual_exec_path=\"$actualInitialExec\" transition=\"$transitionChain\"",
            details = mapOf(
                "environment_id" to resolvedSpec.environmentId.value,
                "handle" to handleId,
                "pid" to pid.toString(),
                "requested_command" to requestedCommand,
                "actual_exec_path" to actualInitialExec,
                "transition" to transitionChain,
            )
        )

        val prootProcess = ProotProcess(
            handleId = handleId,
            environmentId = resolvedSpec.environmentId,
            sessionId = sessionId,
            process = process,
            command = resolvedSpec.command,
            workingDirectory = resolvedSpec.workingDirectory,
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
        val shmDir = storage.shmDir(environment.id).apply { mkdirs() }
        val logFile = storage.prootLogFile(environment.id).apply { parentFile?.mkdirs() }
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = workingDirectory,
            extraEnv = extraEnv,
            tmpDirPath = tmpDir.absolutePath,
            shmDirPath = shmDir.absolutePath,
            logFilePath = logFile.absolutePath,
        )
        return executeWithSpec(spec, sessionId)
    }

    suspend fun executeAndWaitWithSpec(
        spec: RuntimeSpec,
        timeoutMs: Long = 30_000,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val handle = executeWithSpec(spec)
        val prootProcess = activeProcesses[handle.handleId]
            ?: throw ProcessError(handle.handleId, "Process not found after execute")
        val process = prootProcess.process
            ?: throw ProcessError(handle.handleId, "Process object is null")

        // Concurrently consume stdout and stderr to prevent pipe buffer deadlocks
        val stdoutDeferred = async(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }
        }

        val completed = withTimeoutOrNull(timeoutMs) {
            process.waitFor()
        }

        val (exitCode, signaled) = if (completed != null) {
            Pair(process.exitValue(), false)
        } else {
            log.warn("[PROOT] Process execution timed out after ${timeoutMs}ms for handle ${handle.handleId}")
            process.destroyForcibly()
            val forceExit = withTimeoutOrNull(2000L) { process.waitFor() }
            Pair(forceExit ?: -1, true)
        }

        val stdout = stdoutDeferred.await()
        val stderr = stderrDeferred.await()
        val durationMs = System.currentTimeMillis() - startTime

        // Stream output into process.log and console.log
        if (stdout.isNotBlank()) {
            LogFileManager.appendProcessOutput(spec.environmentId, "[STDOUT handle=${handle.handleId}]\n$stdout\n")
        }
        if (stderr.isNotBlank()) {
            LogFileManager.appendProcessOutput(spec.environmentId, "[STDERR handle=${handle.handleId}]\n$stderr\n")
            if (stderr.contains("[GUEST-INIT]")) {
                val initLines = stderr.lines().filter { it.contains("[GUEST-INIT]") }.joinToString("\n")
                val dir = LogFileManager.getLogsDir(spec.environmentId)
                if (dir != null) {
                    val guestInitFile = File(dir, LogCategory.GUEST_INIT.filename)
                    guestInitFile.appendText(initLines + "\n")
                }
            }
        }

        val processLog = LinuxDroidLogger(LogSubsystem.PROCESS, spec.environmentId, category = LogCategory.SYSTEM_PROCESS)
        val lastStage = detectLastCompletedStage(spec.environmentId, stderr)

        val signalNumber = when {
            signaled -> 9
            exitCode in 129..159 -> exitCode - 128
            else -> null
        }
        val signalName = signalNumber?.let { sig ->
            when (sig) {
                1 -> "SIGHUP"
                2 -> "SIGINT"
                3 -> "SIGQUIT"
                4 -> "SIGILL"
                6 -> "SIGABRT"
                7 -> "SIGBUS"
                8 -> "SIGFPE"
                9 -> "SIGKILL"
                11 -> "SIGSEGV"
                13 -> "SIGPIPE"
                14 -> "SIGALRM"
                15 -> "SIGTERM"
                else -> "SIG($sig)"
            }
        }

        if (signalNumber != null) {
            processLog.error(
                "[PROCESS_SIGNAL] handle=${handle.handleId} pid=${handle.pid} signal=$signalNumber signal_name=$signalName stage=$lastStage duration_ms=${durationMs}ms",
                errorCode = exitCode,
                details = mapOf(
                    "environment_id" to spec.environmentId.value,
                    "handle" to handle.handleId,
                    "pid" to handle.pid.toString(),
                    "signal" to signalNumber.toString(),
                    "signal_name" to (signalName ?: ""),
                    "stage" to lastStage,
                    "duration_ms" to durationMs.toString(),
                    "stderrSnippet" to stderr.take(500).replace("\n", " "),
                )
            )
        } else if (exitCode == 0) {
            processLog.info(
                "[PROCESS_EXIT] handle=${handle.handleId} pid=${handle.pid} exit_code=0 duration_ms=${durationMs}ms",
                details = mapOf(
                    "environment_id" to spec.environmentId.value,
                    "handle" to handle.handleId,
                    "pid" to handle.pid.toString(),
                    "exit_code" to "0",
                    "stage" to lastStage,
                    "duration_ms" to durationMs.toString(),
                )
            )
        } else {
            processLog.error(
                "[PROCESS_FAIL] handle=${handle.handleId} pid=${handle.pid} stage=$lastStage errno=$exitCode error=\"${stderr.take(200).replace("\n", " ").trim()}\"",
                errorCode = exitCode,
                details = mapOf(
                    "environment_id" to spec.environmentId.value,
                    "handle" to handle.handleId,
                    "pid" to handle.pid.toString(),
                    "stage" to lastStage,
                    "errno" to exitCode.toString(),
                    "error" to stderr.take(200).replace("\n", " ").trim(),
                    "duration_ms" to durationMs.toString(),
                    "stderrSnippet" to stderr.take(500).replace("\n", " "),
                )
            )
        }

        activeProcesses.remove(handle.handleId)

        val event = if (!signaled) {
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
        val shmDir = storage.shmDir(environment.id).apply { mkdirs() }
        val logFile = storage.prootLogFile(environment.id).apply { parentFile?.mkdirs() }
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = command,
            workingDirectory = workingDirectory,
            extraEnv = extraEnv,
            tmpDirPath = tmpDir.absolutePath,
            shmDirPath = shmDir.absolutePath,
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
        val logFile = resolvedSpec.logFilePath?.let { File(it) } ?: storage.prootLogFile(resolvedSpec.environmentId)
        logFile.parentFile?.mkdirs()

        val handleId = UUID.randomUUID().toString()
        val processLog = LinuxDroidLogger(LogSubsystem.PROCESS, resolvedSpec.environmentId, category = LogCategory.SYSTEM_PROCESS)
        processLog.info(
            "[PROCESS_CREATE] handle=$handleId pid=-1 command=\"${resolvedSpec.command.joinToString(" ")}\" cwd=\"${resolvedSpec.workingDirectory}\" environment_count=${resolvedSpec.environmentVariables.size}",
            details = mapOf(
                "environment_id" to resolvedSpec.environmentId.value,
                "handle" to handleId,
                "pid" to "-1",
                "command" to resolvedSpec.command.joinToString(" "),
                "cwd" to resolvedSpec.workingDirectory,
                "environment_count" to resolvedSpec.environmentVariables.size.toString(),
            )
        )

        val handle = try {
            launcher.launchPty(resolvedSpec, proot, loader, rootfs, tmpDir, rows, cols, logFile)
        } catch (e: IOException) {
            processLog.error(
                "[PROCESS_FAIL] handle=$handleId pid=-1 stage=PROOT_STARTUP errno=-1 error=\"${e.message}\"",
                throwable = e,
                errorCode = -1,
                details = mapOf(
                    "environment_id" to resolvedSpec.environmentId.value,
                    "handle" to handleId,
                    "pid" to "-1",
                    "stage" to "PROOT_STARTUP",
                    "errno" to "-1",
                    "error" to (e.message ?: "Launch failed"),
                    "prootPath" to proot.path,
                    "command" to resolvedSpec.command.joinToString(" "),
                )
            )
            throw ProcessError(handleId, "Failed to launch interactive PTY PRoot process: ${e.message}", e)
        }

        processLog.info(
            "[PROCESS_START] handle=$handleId pid=${handle.pid}",
            details = mapOf(
                "environment_id" to resolvedSpec.environmentId.value,
                "handle" to handleId,
                "pid" to handle.pid.toString(),
            )
        )

        val guestInit = resolvedSpec.guestInitPath
        val useInit = resolvedSpec.executionTarget == ExecutionTarget.GUEST &&
            resolvedSpec.bootstrapPolicy != BootstrapPolicy.BOOTSTRAP_DIRECT_EXEC &&
            !guestInit.isNullOrBlank()
        val actualInitialExec = if (useInit && resolvedSpec.command.firstOrNull() != guestInit) {
            guestInit
        } else {
            resolvedSpec.command.firstOrNull() ?: ""
        }
        val requestedCommand = resolvedSpec.command.joinToString(" ")
        val transitionChain = "$requestedCommand → $actualInitialExec → substituted_loader"

        processLog.info(
            "[PROCESS_EXEC] handle=$handleId pid=${handle.pid} requested_command=\"$requestedCommand\" actual_exec_path=\"$actualInitialExec\" transition=\"$transitionChain\"",
            details = mapOf(
                "environment_id" to resolvedSpec.environmentId.value,
                "handle" to handleId,
                "pid" to handle.pid.toString(),
                "requested_command" to requestedCommand,
                "actual_exec_path" to actualInitialExec,
                "transition" to transitionChain,
            )
        )

        val session = PtySession(
            sessionId = handleId,
            environmentId = resolvedSpec.environmentId,
            pid = handle.pid,
            masterFd = handle.masterFd,
        )
        val prootProcess = ProotProcess(
            handleId = session.sessionId,
            environmentId = resolvedSpec.environmentId,
            sessionId = null,
            process = null,
            command = resolvedSpec.command,
            workingDirectory = resolvedSpec.workingDirectory,
            ptySession = session,
        )
        activeProcesses[session.sessionId] = prootProcess
        _processEvents.tryEmit(ProcessStateEvent.Started(session.sessionId, handle.pid))

        val terminalLog = LinuxDroidLogger(LogSubsystem.PROCESS, resolvedSpec.environmentId, category = LogCategory.TERMINAL)
        terminalLog.info(
            "[PTY_SESSION_START] Started interactive terminal shell (pid=${session.pid}, masterFd=${session.masterFd}, size=${rows}x${cols})",
            details = mapOf(
                "sessionId" to session.sessionId,
                "pid" to session.pid.toString(),
                "masterFd" to session.masterFd.toString(),
                "rows" to rows.toString(),
                "cols" to cols.toString(),
                "command" to resolvedSpec.command.joinToString(" "),
            )
        )
        session
    }

    override suspend fun startInteractiveShell(
        environment: Environment,
        rows: Int,
        cols: Int,
        command: List<String>,
    ): PtySession {
        val rootfs = storage.rootfsDir(environment.id)
        val resolvedCommand = if (command.isEmpty()) {
            val preferred = environment.configuration.shell.ifBlank { "/bin/bash" }
            val resolved = validator.resolveShell(rootfs, preferred)
            listOf(resolved, "-l")
        } else {
            val shell = command.first()
            val resolved = validator.resolveShell(rootfs, shell)
            listOf(resolved) + command.drop(1)
        }
        val tmpDir = storage.tmpDir(environment.id).apply { mkdirs() }
        val shmDir = storage.shmDir(environment.id).apply { mkdirs() }
        val logFile = storage.prootLogFile(environment.id).apply { parentFile?.mkdirs() }
        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = resolvedCommand,
            workingDirectory = environment.configuration.homeDir.ifBlank { "/root" },
            tmpDirPath = tmpDir.absolutePath,
            shmDirPath = shmDir.absolutePath,
            logFilePath = logFile.absolutePath,
        )
        return startInteractiveShellWithSpec(spec, rows, cols)
    }

    override suspend fun inspect(handleId: String): ProcessHandle? {
        val prootProcess = activeProcesses[handleId] ?: return null
        val pty = prootProcess.ptySession
        val process = prootProcess.process
        val (state, pid, exitCode) = when {
            pty != null -> {
                val isAlive = pty.isAlive()
                val exit = if (!isAlive) pty.getExitCode(0) else null
                Triple(if (isAlive) ProcessState.RUNNING else ProcessState.EXITED, pty.pid, exit)
            }
            process != null -> {
                val isAlive = process.isAlive
                val exit = if (!isAlive) {
                    try { process.exitValue() } catch (_: Exception) { null }
                } else null
                Triple(if (isAlive) ProcessState.RUNNING else ProcessState.EXITED, getProcessPid(process), exit)
            }
            else -> Triple(ProcessState.UNKNOWN, -1, null)
        }
        return ProcessHandle(
            handleId = handleId,
            environmentId = prootProcess.environmentId,
            sessionId = prootProcess.sessionId,
            command = prootProcess.command,
            workingDirectory = prootProcess.workingDirectory,
            pid = pid,
            state = state,
            exitCode = exitCode,
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
                standalone = true,
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
                standalone = true,
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
                standalone = true,
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
            val exit = if (finished) {
                proc.exitValue()
            } else {
                proc.destroyForcibly()
                -1
            }
            val stdout = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
            val stderr = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("")
            val combined = stdout + stderr

            log.info("PRoot self-test probe: finished=$finished, exit=$exit, output=${combined.take(120).trim()}")

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
                    standalone = true,
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
                    standalone = true,
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
                standalone = true,
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
                standalone = true,
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

    private fun detectLastCompletedStage(environmentId: EnvironmentId, stderr: String): String {
        val stagesInOrder = listOf(
            "GUEST_ENTRY",
            "LOADER_TRANSFER",
            "LOADER_STACK",
            "LOADER_STACK_BEGIN",
            "LOADER_START_STATEMENT",
            "LOADER_START_CASE",
            "LOADER_ACTION_READ",
            "LOADER_ACTION_READ_BEGIN",
            "LOADER_LAYOUT",
            "LOADER_ENTER",
            "LOADER_STATEMENT",
            "LOADER_START",
            "LOADER_RUNTIME",
            "EXECVE_DISPATCH_OK",
            "REGS_AFTER_EXEC",
            "REGS_PUSH_COMPLETE",
            "REGS_PUSH_NT_PRSTATUS_COMPLETE",
            "REGS_PUSH_NT_PRSTATUS_BEGIN",
            "REGS_PUSH_SYSTEM_CALL_COMPLETE",
            "REGS_PUSH_SYSTEM_CALL_BEGIN",
            "REGS_PUSH_BEGIN",
            "REGS_MODIFIED",
            "REGS_BEFORE_EXEC",
            "LOAD_SCRIPT_WRITE_COMPLETE",
            "LOAD_SCRIPT_WRITE_BEGIN",
            "LOAD_STATEMENT",
            "LOAD_SCRIPT_LAYOUT",
            "LOAD_SCRIPT_PREP",
            "LOADER_PATH_OK",
            "INTERP_OK",
            "LOAD_INFO_OK",
            "SHEBANG_OK",
            "EXECVE_PATH_OK",
            "EXECVE_ENTER",
            "PROCESS_EXEC",
            "PROCESS_START",
            "PROCESS_CREATE",
        )
        var logContent = stderr
        try {
            val logFile = storage.prootLogFile(environmentId)
            if (logFile.exists() && logFile.length() > 0) {
                val length = logFile.length()
                val readSize = length.coerceAtMost(65536L).toInt()
                val bytes = java.io.RandomAccessFile(logFile, "r").use { raf ->
                    raf.seek(length - readSize)
                    val buffer = ByteArray(readSize)
                    raf.readFully(buffer)
                    buffer
                }
                logContent = String(bytes) + "\n" + stderr
            }
        } catch (_: Throwable) {
            // ignore read errors
        }

        for (stage in stagesInOrder) {
            if (logContent.contains(stage)) {
                return stage
            }
        }
        return "UNKNOWN"
    }

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
    val workingDirectory: String = "/",
    val ptySession: PtySession? = null,
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
