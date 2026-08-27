package com.linuxdroid.core.filesystem

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.FilesystemError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the on-disk layout for a Linux environment.
 *
 * Layout:
 * ```
 * <base>/
 *   <env-id>/
 *     rootfs/          <- Linux filesystem root (persistent)
 *     metadata/        <- Environment metadata files
 *     runtime-state/   <- Transient runtime state (may be recreated)
 *     tmp/             <- Temporary files (may be cleaned at startup)
 * ```
 *
 * IMPORTANT: rootfs/ is NEVER deleted by LinuxDroid.
 * It is the persistent source of truth.
 */
class EnvironmentStorage(
    /** Base directory for all environments (e.g. app's filesDir/environments). */
    private val baseDir: File,
) {
    private val log = LinuxDroidLogger(LogSubsystem.FILESYSTEM)

    /** Returns the root directory for the given environment. */
    fun environmentDir(id: EnvironmentId): File = File(baseDir, id.value)

    /** Returns the rootfs directory. This is the Linux filesystem root. */
    fun rootfsDir(id: EnvironmentId): File = File(environmentDir(id), "rootfs")

    /** Returns the metadata directory. */
    fun metadataDir(id: EnvironmentId): File = File(environmentDir(id), "metadata")

    /** Returns the runtime-state directory (transient). */
    fun runtimeStateDir(id: EnvironmentId): File = File(environmentDir(id), "runtime-state")

    /** Returns the tmp directory. */
    fun tmpDir(id: EnvironmentId): File = File(environmentDir(id), "tmp")

    /**
     * Creates the directory structure for a new environment.
     * Does NOT create or touch rootfs/.
     */
    suspend fun initializeEnvironmentDirs(id: EnvironmentId) = withContext(Dispatchers.IO) {
        log.info("Initializing environment directories for $id")
        listOf(metadataDir(id), runtimeStateDir(id), tmpDir(id)).forEach { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                throw FilesystemError(dir.path, "Failed to create directory")
            }
        }
    }

    /**
     * Verifies that the rootfs directory exists and contains a minimal Linux structure.
     * Does NOT check every path — just enough to confirm the rootfs is present.
     */
    suspend fun verifyRootfs(id: EnvironmentId): Boolean = withContext(Dispatchers.IO) {
        val rootfs = rootfsDir(id)
        if (!rootfs.isDirectory) {
            log.warn("Rootfs directory does not exist: ${rootfs.path}")
            return@withContext false
        }
        val markers = listOf("bin", "etc", "usr")
        val missing = markers.filter { !File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            log.warn("Rootfs missing expected directories: $missing")
            return@withContext false
        }
        true
    }

    /**
     * Returns the total size of the rootfs in bytes.
     */
    suspend fun rootfsSize(id: EnvironmentId): Long = withContext(Dispatchers.IO) {
        rootfsDir(id).walkTopDown().sumOf { it.length() }
    }

    /**
     * Cleans transient runtime state directory.
     * NEVER touches rootfs/.
     */
    suspend fun cleanRuntimeState(id: EnvironmentId) = withContext(Dispatchers.IO) {
        log.debug("Cleaning runtime state for $id")
        val stateDir = runtimeStateDir(id)
        stateDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Deletes the entire environment directory on disk.
     * Called strictly on explicit user deletion request.
     */
    suspend fun deleteEnvironment(id: EnvironmentId) = withContext(Dispatchers.IO) {
        log.info("Deleting environment storage for $id")
        environmentDir(id).deleteRecursively()
    }

    /**
     * Returns true if an environment directory exists.
     */
    fun environmentExists(id: EnvironmentId): Boolean = environmentDir(id).isDirectory
}
