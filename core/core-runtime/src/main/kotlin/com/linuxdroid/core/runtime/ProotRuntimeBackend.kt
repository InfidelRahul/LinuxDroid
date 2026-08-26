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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Rootless Linux runtime backend using proot.
 *
 * proot intercepts syscalls via ptrace and rewrites filesystem paths,
 * allowing a Linux rootfs to operate without root privileges.
 *
 * No root required. No kernel modules required.
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
     * Returns the validated executable proot binary, extracting it from assets if needed.
     */
    private fun ensureProotBinary(): File {
        prootBinaryCache?.let { if (it.exists()) return it }
        synchronized(this) {
            prootBinaryCache?.let { if (it.exists()) return it }
            val extracted = extractProotBinary()
            prootBinaryCache = extracted
            return extracted
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
        ensureProotBinary()
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

        val prootDir = proot.parentFile?.absolutePath ?: File(context.filesDir, "proot").absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        processBuilder.environment()["LD_LIBRARY_PATH"] = "$prootDir:$nativeLibDir"
        processBuilder.environment()["PROOT_TMP_DIR"] = storage.tmpDir(environment.id).absolutePath

        buildEnvironmentVariables(extraEnv).forEach { (k, v) ->
            processBuilder.environment()[k] = v
        }

        val process = processBuilder.start()
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
            val binary = ensureProotBinary()
            if (!binary.exists() || !binary.isFile) {
                log.warn("Health check: proot binary missing at ${binary.path}")
                return@withContext false
            }

            // Verify binary execution
            val prootDir = binary.parentFile?.absolutePath ?: ""
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(binary.absolutePath, "--help")
            pb.environment()["LD_LIBRARY_PATH"] = "$prootDir:$nativeLibDir"
            val proc = pb.start()
            val finished = proc.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            val exit = proc.exitValue()
            // proot --help exits with 0 or 1 depending on version
            val output = proc.inputStream.bufferedReader().readText() + proc.errorStream.bufferedReader().readText()
            val isProot = output.contains("PRoot", ignoreCase = true) || output.contains("proot", ignoreCase = true) || exit == 0 || exit == 1
            log.info("Proot healthCheck verified: isProot=$isProot (exit=$exit)")
            isProot
        } catch (e: Exception) {
            log.warn("Health check failed", e)
            false
        }
    }

    override suspend fun cleanup(environment: Environment) {
        stop(environment)
        storage.cleanRuntimeState(environment.id)
    }

    // ─── Private helpers ────────────────────────────────────────────────────────────

    private fun extractProotBinary(): File {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "x86_64") }
            ?: throw RuntimeError(message = "Unsupported device ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

        val destDir = File(context.filesDir, "runtime/$abi")
        destDir.mkdirs()
        val destFile = File(destDir, "proot")

        try {
            val assetFiles = context.assets.list("proot/$abi") ?: arrayOf("proot", "libtalloc.so.2", "libandroid-shmem.so")
            for (filename in assetFiles) {
                val target = File(destDir, filename)
                if (!target.exists() || target.length() == 0L) {
                    log.info("Extracting proot asset: $filename for ABI: $abi")
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
