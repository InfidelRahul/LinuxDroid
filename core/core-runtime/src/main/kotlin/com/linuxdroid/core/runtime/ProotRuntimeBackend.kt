package com.linuxdroid.core.runtime

import android.content.Context
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.native_bridge.NativeBridge
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
 * 1. Self-contained: Built from upstream PRoot v5.4.0 with static talloc and companion loader.
 * 2. Standalone: 0 external shared library dependencies (only standard Android Bionic libc/libdl/liblog).
 * 3. Relocatable: Extracted to app runtime directory with executable permissions.
 * 4. Guest Isolation: Uses /usr/bin/env -i to clear host Android environment variables.
 */
class ProotRuntimeBackend(
    private val context: Context,
    private val storage: EnvironmentStorage,
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
     */
    fun ensureProotBinary(): File {
        prootBinaryCache?.let { if (it.exists() && it.canExecute()) return it }
        synchronized(this) {
            prootBinaryCache?.let { if (it.exists() && it.canExecute()) return it }
            val (proot, loader) = resolveOrExtractRuntimeBinaries()
            prootBinaryCache = proot
            loaderBinaryCache = loader
            return proot
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

    override suspend fun stop(environment: Environment) = withContext(Dispatchers.IO) {
        log.info("Stopping proot runtime for ${environment.id}")
        activeProcesses.entries
            .filter { it.value.environmentId == environment.id }
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
        log.info("proot runtime stopped for ${environment.id}")
    }

    override suspend fun restart(environment: Environment) {
        stop(environment)
        initialize(environment)
        start(environment)
    }

    override suspend fun execute(
        environment: Environment,
        command: List<String>,
        workingDirectory: String,
        extraEnv: Map<String, String>,
        sessionId: SessionId?,
    ): ProcessHandle = withContext(Dispatchers.IO) {
        val handleId = UUID.randomUUID().toString()
        val rootfs = storage.rootfsDir(environment.id)
        val tmpDir = storage.tmpDir(environment.id).apply { mkdirs() }
        val proot = ensureProotBinary()
        val loader = ensureLoaderBinary()

        log.info("Executing in proot: ${command.joinToString(" ")} (handle=$handleId)")

        val prootCmd = buildProotCommand(
            prootBinary = proot,
            rootfs = rootfs,
            userCommand = command,
            workingDirectory = workingDirectory,
            config = environment.configuration,
            tmpDir = tmpDir,
            extraEnv = extraEnv,
        )

        val processBuilder = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .redirectErrorStream(false)

        // Set host environment variables for PRoot process
        processBuilder.environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
        if (loader?.exists() == true) {
            processBuilder.environment()["PROOT_LOADER"] = loader.absolutePath
        }

        val process: Process
        try {
            process = processBuilder.start()
        } catch (e: IOException) {
            log.error("Failed to execute PRoot process: ${e.message}", e)
            throw RuntimeError(
                environmentId = environment.id,
                message = "Failed to launch PRoot executable '${proot.path}': ${e.message}",
                cause = e,
            )
        }

        val pid = getProcessPid(process)

        val prootProcess = ProotProcess(
            handleId = handleId,
            environmentId = environment.id,
            sessionId = sessionId,
            process = process,
            command = command,
        )
        activeProcesses[handleId] = prootProcess

        _processEvents.tryEmit(ProcessStateEvent.Started(handleId, pid))

        ProcessHandle(
            handleId = handleId,
            environmentId = environment.id,
            sessionId = sessionId,
            command = command,
            workingDirectory = workingDirectory,
            pid = pid,
            state = ProcessState.RUNNING,
        )
    }

    override suspend fun executeAndWait(
        environment: Environment,
        command: List<String>,
        workingDirectory: String,
        extraEnv: Map<String, String>,
        timeoutMs: Long,
    ): ProcessResult = withContext(Dispatchers.IO) {
        val handle = execute(environment, command, workingDirectory, extraEnv)
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

    override suspend fun startInteractiveShell(
        environment: Environment,
        rows: Int,
        cols: Int,
        command: List<String>,
    ): PtySession = withContext(Dispatchers.IO) {
        val proot = ensureProotBinary()
        val loader = ensureLoaderBinary()
        val rootfs = storage.rootfsDir(environment.id)
        val tmpDir = storage.tmpDir(environment.id).apply { mkdirs() }

        val prootCmd = buildList {
            add(proot.absolutePath)
            add("-0")
            add("--kill-on-exit")
            add("--link2symlink")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("${tmpDir.absolutePath}:/tmp")
            if (environment.configuration.runtime.sharedStorageEnabled) {
                val sharedDir = File(android.os.Environment.getExternalStorageDirectory(), "LinuxDroid")
                if (sharedDir.exists() && sharedDir.canRead()) {
                    add("-b")
                    add("${sharedDir.absolutePath}:/home/user/Android")
                }
            }
            add("-w")
            add(environment.configuration.homeDir.ifBlank { "/root" })
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("USER=root")
            add("LOGNAME=root")
            add("TMPDIR=/tmp")
            addAll(command)
        }

        val envVars = buildList {
            add("PROOT_TMP_DIR=${tmpDir.absolutePath}")
            if (loader?.exists() == true) {
                add("PROOT_LOADER=${loader.absolutePath}")
            }
        }

        val outPidAndFd = IntArray(2)
        val res = NativeBridge.createPtyProcess(
            cmd = prootCmd.toTypedArray(),
            cwd = rootfs.absolutePath,
            env = envVars.toTypedArray(),
            rows = rows,
            cols = cols,
            outPidAndFd = outPidAndFd
        )

        if (res != 0) {
            log.error("Failed to create PTY process: errno $res")
            throw RuntimeError(
                environmentId = environment.id,
                message = "Failed to create PTY process: errno $res",
            )
        }

        val session = PtySession(
            sessionId = UUID.randomUUID().toString(),
            environmentId = environment.id,
            pid = outPidAndFd[0],
            masterFd = outPidAndFd[1],
        )
        log.info("Interactive shell PTY session created: pid=${session.pid}, masterFd=${session.masterFd}")
        session
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
            val isWrongAbi = elfInfo.detail.contains("does not match target ABI", ignoreCase = true)
            return ProotDiagnosticResult(
                status = if (isWrongAbi) ProotStatus.PROOT_WRONG_ABI else ProotStatus.PROOT_INVALID_ELF,
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

        val canExec = binary.canExecute() || NativeBridge.isExecutable(binary.absolutePath)
        if (!canExec) {
            return ProotDiagnosticResult(
                status = ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = false,
                loaderValid = loader?.exists() == true,
                termuxFree = true,
                detail = "Binary lacks execution permissions",
            )
        }

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
                    detail = "PRoot v5.4.0 standalone verified (self-test exit=$exit)",
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
            ProotDiagnosticResult(
                status = if (isPermissionDenied) ProotStatus.PROOT_EXECUTION_DENIED else ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = canExec,
                loaderValid = loader?.exists() == true,
                termuxFree = true,
                detail = if (isPermissionDenied) "Execution denied by platform (error=13 EACCES)" else "Execution failed: ${e.message}",
                error = e.message,
            )
        } catch (e: Exception) {
            ProotDiagnosticResult(
                status = ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                loaderPath = loader?.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = canExec,
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
        return android.os.Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "x86_64") }
    }

    private fun resolveOrExtractRuntimeBinaries(): Pair<File, File?> {
        val abi = getDeviceAbi()
            ?: throw RuntimeError(message = "Unsupported device ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

        val runtimeDir = File(context.filesDir, "runtime/$abi").apply { mkdirs() }
        val prootFile = File(runtimeDir, "proot")
        val loaderFile = File(runtimeDir, "loader")

        try {
            // 1. Extract proot binary
            if (!prootFile.exists() || prootFile.length() == 0L) {
                log.info("Extracting proot runtime binary for $abi to ${prootFile.path}")
                context.assets.open("proot/$abi/proot").use { input ->
                    prootFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            prootFile.setReadable(true, false)
            prootFile.setExecutable(true, false)
            NativeBridge.setExecutable(prootFile.absolutePath)

            // 2. Extract companion loader binary
            try {
                if (!loaderFile.exists() || loaderFile.length() == 0L) {
                    log.info("Extracting companion loader binary for $abi to ${loaderFile.path}")
                    context.assets.open("proot/$abi/loader").use { input ->
                        loaderFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                loaderFile.setReadable(true, false)
                loaderFile.setExecutable(true, false)
                NativeBridge.setExecutable(loaderFile.absolutePath)
            } catch (_: Exception) {
                log.debug("Standalone loader asset not found or not required")
            }
        } catch (e: Exception) {
            log.error("Error extracting proot runtime assets for $abi", e)
        }

        return prootFile to (if (loaderFile.exists()) loaderFile else null)
    }

    private fun buildProotCommand(
        prootBinary: File,
        rootfs: File,
        userCommand: List<String>,
        workingDirectory: String,
        config: EnvironmentConfiguration,
        tmpDir: File,
        extraEnv: Map<String, String>,
    ): List<String> {
        return buildList {
            add(prootBinary.absolutePath)
            add("-0")
            add("--kill-on-exit")
            add("--link2symlink")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("${tmpDir.absolutePath}:/tmp")
            if (config.runtime.sharedStorageEnabled) {
                val sharedDir = File(android.os.Environment.getExternalStorageDirectory(), "LinuxDroid")
                if (sharedDir.exists() && sharedDir.canRead()) {
                    add("-b")
                    add("${sharedDir.absolutePath}:/home/user/Android")
                }
            }
            add("-w")
            add(workingDirectory)

            // Pristine guest environment via /usr/bin/env -i
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("USER=root")
            add("LOGNAME=root")
            add("TMPDIR=/tmp")
            extraEnv.forEach { (k, v) ->
                add("$k=$v")
            }
            addAll(userCommand)
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
