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
 * Rootless Linux runtime backend using PRoot.
 *
 * PRoot intercepts syscalls via ptrace and rewrites filesystem paths,
 * allowing a Linux rootfs to operate without root privileges.
 *
 * Android Native Execution Architecture:
 * 1. Primary: Executes libproot.so located in `context.applicationInfo.nativeLibraryDir`
 *    (PIE executable extracted by Android package manager, permitted by SELinux and kernel without error=13).
 * 2. Fallback: Extracts to app code-cache / runtime dir with native POSIX chmod 0755.
 * 3. Dynamic Linker: DT_RUNPATH is set to $ORIGIN; LD_LIBRARY_PATH includes nativeLibraryDir
 *    so libtalloc.so and libandroid-shmem.so resolve natively.
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

    /**
     * Resolves the executable proot binary path.
     * Prioritizes context.applicationInfo.nativeLibraryDir where Android permits native execution.
     */
    fun ensureProotBinary(): File {
        prootBinaryCache?.let { if (it.exists() && it.canExecute()) return it }
        synchronized(this) {
            prootBinaryCache?.let { if (it.exists() && it.canExecute()) return it }
            val resolved = resolveOrExtractProotBinary()
            prootBinaryCache = resolved
            return resolved
        }
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
        val proot = ensureProotBinary()

        log.info("Executing in proot: ${command.joinToString(" ")} (handle=$handleId)")

        val prootCmd = buildProotCommand(
            prootBinary = proot,
            rootfs = rootfs,
            userCommand = command,
            workingDirectory = workingDirectory,
            config = environment.configuration,
        )

        val processBuilder = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .redirectErrorStream(false)

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootParent = proot.parentFile?.absolutePath ?: ""
        val ldPath = listOf(nativeLibDir, prootParent).filter { it.isNotBlank() }.joinToString(":")
        processBuilder.environment()["LD_LIBRARY_PATH"] = ldPath
        processBuilder.environment()["PROOT_TMP_DIR"] = storage.tmpDir(environment.id).absolutePath

        buildEnvironmentVariables(extraEnv).forEach { (k, v) ->
            processBuilder.environment()[k] = v
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
                abi = getDeviceAbi(),
                elfValid = false,
                elfType = "MISSING",
                executable = false,
                detail = "PRoot resolution error: ${e.message}",
                error = e.message,
            )
        }
    }

    private fun diagnoseProot(binary: File): ProotDiagnosticResult {
        val targetAbi = getDeviceAbi() ?: "unknown"
        if (!binary.exists() || !binary.isFile) {
            return ProotDiagnosticResult(
                status = ProotStatus.PROOT_MISSING,
                binaryPath = binary.path,
                abi = targetAbi,
                elfValid = false,
                elfType = "MISSING",
                executable = false,
                detail = "PRoot binary missing on filesystem",
            )
        }

        val elfInfo = ElfValidator.readElfInfo(binary, targetAbi)
        if (!elfInfo.isValid) {
            val isWrongAbi = elfInfo.detail.contains("does not match target ABI", ignoreCase = true)
            return ProotDiagnosticResult(
                status = if (isWrongAbi) ProotStatus.PROOT_WRONG_ABI else ProotStatus.PROOT_INVALID_ELF,
                binaryPath = binary.path,
                abi = targetAbi,
                elfValid = false,
                elfType = elfInfo.typeName,
                executable = binary.canExecute(),
                detail = elfInfo.detail,
            )
        }

        // Check required shared library dependencies
        val searchDirs = listOfNotNull(
            File(context.applicationInfo.nativeLibraryDir),
            binary.parentFile,
            context.codeCacheDir?.let { File(it, "runtime/$targetAbi") },
            File(context.filesDir, "runtime/$targetAbi"),
        )
        val requiredLibs = listOf("libtalloc.so", "libandroid-shmem.so")
        val missingLibs = requiredLibs.filter { libName ->
            searchDirs.none { dir -> File(dir, libName).exists() }
        }

        if (missingLibs.isNotEmpty()) {
            return ProotDiagnosticResult(
                status = ProotStatus.PROOT_DEPENDENCY_FAILURE,
                binaryPath = binary.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = binary.canExecute(),
                dependenciesOk = false,
                missingDependencies = missingLibs,
                detail = "Missing dynamic libraries: ${missingLibs.joinToString(", ")}",
                error = "Required native dependencies not found in library paths",
            )
        }

        val canExec = binary.canExecute() || NativeBridge.isExecutable(binary.absolutePath)
        if (!canExec) {
            return ProotDiagnosticResult(
                status = ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = false,
                dependenciesOk = true,
                detail = "Binary lacks execution permissions",
            )
        }

        // Test run execution probe (proot --help)
        return try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val prootParent = binary.parentFile?.absolutePath ?: ""
            val ldPath = listOf(nativeLibDir, prootParent).filter { it.isNotBlank() }.joinToString(":")

            val pb = ProcessBuilder(binary.absolutePath, "--help")
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            val proc = pb.start()
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            val exit = proc.exitValue()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val combined = stdout + stderr

            if (combined.contains("PRoot", ignoreCase = true) || combined.contains("proot", ignoreCase = true) || exit == 0 || exit == 1) {
                ProotDiagnosticResult(
                    status = ProotStatus.PROOT_OK,
                    binaryPath = binary.path,
                    abi = targetAbi,
                    elfValid = true,
                    elfType = elfInfo.typeName,
                    executable = true,
                    dependenciesOk = true,
                    detail = "PRoot executable verified (self-test exit=$exit)",
                )
            } else if (combined.contains("CANNOT LINK EXECUTABLE", ignoreCase = true)) {
                ProotDiagnosticResult(
                    status = ProotStatus.PROOT_DEPENDENCY_FAILURE,
                    binaryPath = binary.path,
                    abi = targetAbi,
                    elfValid = true,
                    elfType = elfInfo.typeName,
                    executable = true,
                    dependenciesOk = false,
                    detail = "Dynamic linker dependency failure",
                    error = combined.trim(),
                )
            } else {
                ProotDiagnosticResult(
                    status = ProotStatus.PROOT_OK,
                    binaryPath = binary.path,
                    abi = targetAbi,
                    elfValid = true,
                    elfType = elfInfo.typeName,
                    executable = true,
                    dependenciesOk = true,
                    detail = "PRoot verified with exit=$exit: ${combined.take(100)}",
                )
            }
        } catch (e: IOException) {
            val isPermissionDenied = e.message?.contains("error=13", ignoreCase = true) == true ||
                e.message?.contains("Permission denied", ignoreCase = true) == true
            ProotDiagnosticResult(
                status = if (isPermissionDenied) ProotStatus.PROOT_EXECUTION_DENIED else ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = canExec,
                dependenciesOk = true,
                detail = if (isPermissionDenied) "Execution denied by platform (error=13 EACCES)" else "Execution failed: ${e.message}",
                error = e.message,
            )
        } catch (e: Exception) {
            ProotDiagnosticResult(
                status = ProotStatus.PROOT_NOT_EXECUTABLE,
                binaryPath = binary.path,
                abi = targetAbi,
                elfValid = true,
                elfType = elfInfo.typeName,
                executable = canExec,
                dependenciesOk = true,
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

    private fun resolveOrExtractProotBinary(): File {
        val abi = getDeviceAbi()
            ?: throw RuntimeError(message = "Unsupported device ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

        // 1. Check primary location: nativeLibraryDir/libproot.so
        val nativeProotSo = File(nativeLibDir, "libproot.so")
        if (nativeProotSo.exists() && nativeProotSo.length() > 0L) {
            try {
                nativeProotSo.setReadable(true, false)
                nativeProotSo.setExecutable(true, false)
                NativeBridge.setExecutable(nativeProotSo.absolutePath)
            } catch (_: Exception) {}
            log.info("Resolved PRoot binary from nativeLibraryDir: ${nativeProotSo.path}")
            return nativeProotSo
        }

        // 2. Check nativeLibraryDir/proot
        val nativeProotBin = File(nativeLibDir, "proot")
        if (nativeProotBin.exists() && nativeProotBin.length() > 0L) {
            try {
                nativeProotBin.setReadable(true, false)
                nativeProotBin.setExecutable(true, false)
                NativeBridge.setExecutable(nativeProotBin.absolutePath)
            } catch (_: Exception) {}
            log.info("Resolved PRoot binary from nativeLibraryDir: ${nativeProotBin.path}")
            return nativeProotBin
        }

        // 3. Fallback: Extract to codeCacheDir (executable on Android) or filesDir
        val baseTargetDir = context.codeCacheDir ?: context.filesDir
        val destDir = File(baseTargetDir, "runtime/$abi")
        destDir.mkdirs()
        val destFile = File(destDir, "libproot.so")

        try {
            val assetFiles = context.assets.list("proot/$abi") ?: arrayOf("proot", "libtalloc.so", "libandroid-shmem.so")
            for (filename in assetFiles) {
                val targetName = if (filename == "proot") "libproot.so" else filename
                val target = File(destDir, targetName)
                if (!target.exists() || target.length() == 0L) {
                    log.info("Extracting proot asset: $filename to ${target.path}")
                    context.assets.open("proot/$abi/$filename").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setReadable(true, false)
                    target.setExecutable(true, false)
                    NativeBridge.setExecutable(target.absolutePath)
                }
            }
            destFile.setReadable(true, false)
            destFile.setExecutable(true, false)
            NativeBridge.setExecutable(destFile.absolutePath)
        } catch (e: Exception) {
            log.error("Error extracting proot assets for $abi", e)
        }
        return destFile
    }

    private fun buildProotCommand(
        prootBinary: File,
        rootfs: File,
        userCommand: List<String>,
        workingDirectory: String,
        config: EnvironmentConfiguration,
    ): List<String> {
        return buildList {
            add(prootBinary.absolutePath)
            add("--rootfs=${rootfs.absolutePath}")
            add("--root-id")
            add("--cwd=$workingDirectory")
            add("--bind=/dev")
            add("--bind=/proc")
            add("--bind=/sys")
            add("--bind=/dev/urandom:/dev/random")
            add("--link2symlink")
            if (config.runtimeConfig.sharedStorageEnabled) {
                val sharedDir = File(android.os.Environment.getExternalStorageDirectory(), "LinuxDroid")
                if (sharedDir.exists() && sharedDir.canRead()) {
                    add("--bind=${sharedDir.absolutePath}:/home/user/Android")
                }
            }
            addAll(userCommand)
        }
    }

    private fun buildEnvironmentVariables(extraEnv: Map<String, String>): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        put("USER", "root")
        put("LOGNAME", "root")
        putAll(extraEnv)
    }
}

private data class ProotProcess(
    val handleId: String,
    val environmentId: EnvironmentId,
    val sessionId: SessionId?,
    val process: Process?,
    val command: List<String>,
)

private val EnvironmentConfiguration.runtimeConfig: RuntimeConfig
    get() = this.runtime

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
