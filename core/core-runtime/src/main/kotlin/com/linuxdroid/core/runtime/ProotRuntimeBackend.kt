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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Rootless Linux runtime backend using proot.
 *
 * proot intercepts syscalls via ptrace and rewrites filesystem paths,
 * allowing a Linux rootfs to operate without root privileges.
 *
 * No root required. No kernel modules required.
 *
 * Architecture:
 * ```
 * Kotlin ProotRuntimeBackend
 *     ↓
 * Native bridge (JNI)
 *     ↓
 * proot binary (arm64 or x86_64)
 *     ↓
 * Linux rootfs (persistent)
 *     ↓
 * /bin/sh, Linux processes
 * ```
 *
 * IMPORTANT: This class does NOT delete the rootfs under any circumstances.
 */
class ProotRuntimeBackend(
    private val context: Context,
    private val storage: EnvironmentStorage,
) : RuntimeBackend {

    private val log = LinuxDroidLogger(LogSubsystem.RUNTIME)

    /** Tracks active proot processes by handleId. */
    private val activeProcesses = ConcurrentHashMap<String, ProotProcess>()

    private val _processEvents = MutableSharedFlow<ProcessStateEvent>(extraBufferCapacity = 64)
    override val processEvents: Flow<ProcessStateEvent> = _processEvents.asSharedFlow()

    /** Path to the proot binary extracted to app files dir. */
    private lateinit var prootBinary: File

    override suspend fun prepare(environment: Environment) = withContext(Dispatchers.IO) {
        log.info("Preparing proot runtime for ${environment.id}")
        prootBinary = extractProotBinary()
        log.info("proot binary: ${prootBinary.path} (exists=${prootBinary.exists()})")
    }

    override suspend fun initialize(environment: Environment): Unit = withContext(Dispatchers.IO) {
        log.info("Initializing proot runtime for ${environment.id}")
        // Verify rootfs exists — never recreate it here
        val rootfs = storage.rootfsDir(environment.id)
        if (!rootfs.isDirectory) {
            throw RuntimeError(
                environmentId = environment.id,
                message = "Rootfs not found at ${rootfs.path}. " +
                    "Environment must be installed before starting.",
            )
        }
        storage.cleanRuntimeState(environment.id)
    }

    override suspend fun start(environment: Environment) = withContext(Dispatchers.IO) {
        log.info("Starting proot runtime for ${environment.id}")
        // Validate environment is ready
        if (!environment.state.canStart()) {
            throw RuntimeNotReadyError(environment.id, environment.state)
        }
        // proot does not require a persistent daemon — it is launched per-command.
        // Individual processes are started via execute().
        log.info("proot runtime ready for ${environment.id}")
    }

    override suspend fun stop(environment: Environment) = withContext(Dispatchers.IO) {
        log.info("Stopping proot runtime for ${environment.id}")
        // Terminate all active processes for this environment
        activeProcesses.entries
            .filter { it.value.environmentId == environment.id }
            .forEach { (handleId, process) ->
                log.debug("Terminating process $handleId (PID ${getProcessPid(process.process)})")
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

        log.info("Executing in proot: ${command.joinToString(" ")} (handle=$handleId)")

        val prootCmd = buildProotCommand(
            rootfs = rootfs,
            userCommand = command,
            workingDirectory = workingDirectory,
            extraEnv = extraEnv,
            config = environment.configuration,
        )

        val processBuilder = ProcessBuilder(prootCmd)
            .directory(rootfs)
            .redirectErrorStream(false)

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

    override suspend fun healthCheck(environment: Environment): Boolean {
        return try {
            val rootfs = storage.rootfsDir(environment.id)
            rootfs.isDirectory && ::prootBinary.isInitialized && prootBinary.exists()
        } catch (e: Exception) {
            log.warn("Health check failed", e)
            false
        }
    }

    override suspend fun cleanup(environment: Environment) {
        stop(environment)
        // Do NOT delete rootfs. Only clean transient state.
        storage.cleanRuntimeState(environment.id)
    }

    // ─── Private helpers ────────────────────────────────────────────────────────────

    /**
     * Extracts the proot binary from assets to app's files directory.
     * Returns the path to the executable binary.
     *
     * The proot binary is bundled in assets/proot/<abi>/proot
     */
    private fun extractProotBinary(): File {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "x86_64") }
            ?: throw RuntimeError(message = "Unsupported ABI: ${android.os.Build.SUPPORTED_ABIS.toList()}")

        val destDir = File(context.filesDir, "proot")
        destDir.mkdirs()
        val destFile = File(destDir, "proot")

        if (!destFile.exists()) {
            log.info("Extracting proot binary for ABI: $abi")
            try {
                context.assets.open("proot/$abi/proot").use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
            } catch (e: Exception) {
                log.warn("proot binary not found in assets for $abi - runtime will be limited", e)
                // Return path anyway; will be checked in healthCheck
            }
        }
        return destFile
    }

    /**
     * Builds the proot command line for executing [userCommand] inside [rootfs].
     *
     * proot flags:
     *   -r <rootfs>: set the root to rootfs
     *   -0: fake root (uid 0) — allows installation without real root
     *   -w <dir>: set working directory
     *   -b /dev: bind /dev from host (required for most apps)
     *   -b /proc: bind /proc from host
     *   -b /sys: bind /sys from host (read-only where possible)
     *   --link2symlink: emulate hard links with symlinks
     */
    private fun buildProotCommand(
        rootfs: File,
        userCommand: List<String>,
        workingDirectory: String,
        extraEnv: Map<String, String>,
        config: EnvironmentConfiguration,
    ): List<String> {
        val proot = prootBinary.absolutePath
        return buildList {
            add(proot)
            add("--rootfs=${rootfs.absolutePath}")
            add("--root-id")
            add("--cwd=$workingDirectory")
            add("--bind=/dev")
            add("--bind=/proc")
            add("--bind=/sys")
            add("--bind=/dev/urandom:/dev/random")
            add("--link2symlink")
            if (config.runtimeConfig.sharedStorageEnabled) {
                // Shared storage bind will be set up by StorageBridge
                // when available — not here, to keep separation of concerns
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
        // User-provided overrides (can override defaults)
        putAll(extraEnv)
    }
}

/** Internal tracking of a running proot process. */
private data class ProotProcess(
    val handleId: String,
    val environmentId: EnvironmentId,
    val sessionId: SessionId?,
    val process: Process?,
    val command: List<String>,
)

/** Extension to get EnvironmentConfiguration.runtimeConfig safely. */
private val EnvironmentConfiguration.runtimeConfig: RuntimeConfig
    get() = this.runtime

/**
 * Safely gets the PID of a Java Process using reflection.
 *
 * Uses reflection to access the PID field which is stored differently
 * on Android vs. JVM. Avoids `Process.pid()` which is API 26+/Java 9+
 * and may not be available in all build environments.
 */
private fun getProcessPid(process: Process?): Int {
    if (process == null) return -1
    return try {
        // Java 9+ / Android API 26+: use pid() method
        val method = process.javaClass.getMethod("pid")
        (method.invoke(process) as Long).toInt()
    } catch (_: Exception) {
        try {
            // Android internal: UNIXProcess has a 'pid' field
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }
}
