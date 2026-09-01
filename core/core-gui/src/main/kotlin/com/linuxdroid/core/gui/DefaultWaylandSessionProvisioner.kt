package com.linuxdroid.core.gui

import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates a per-environment Wayland runtime directory under the existing
 * environment storage layout and derives the complete session environment.
 *
 * Layout (reusing [EnvironmentStorage], no new storage mechanism):
 * ```
 * <environment>/runtime-state/wayland/   <- host XDG_RUNTIME_DIR (mode 0700)
 * ```
 * bound into the guest at [guestRuntimeDir]. `/tmp` is never used as the
 * runtime directory, and the socket name is chosen from what is actually free
 * in that directory rather than assuming `wayland-0`.
 */
class DefaultWaylandSessionProvisioner(
    private val storage: EnvironmentStorage,
    private val guiLogFactory: (EnvironmentId) -> GuiLog,
    /** Guest-visible mount point of the runtime directory. */
    private val guestRuntimeDir: String = DEFAULT_GUEST_RUNTIME_DIR,
    private val preferredSocketName: String = "wayland-0",
    private val maxSocketCandidates: Int = 32,
) : WaylandSessionProvisioner {

    override suspend fun provision(
        environmentId: EnvironmentId,
        sessionId: SessionId,
    ): WaylandSessionInfo = withContext(Dispatchers.IO) {
        val log = guiLogFactory(environmentId)

        val hostRuntimeDir = hostRuntimeDir(environmentId)
        createRuntimeDir(hostRuntimeDir)
        log.info(
            GuiLogCategory.WAYLAND,
            "wayland runtime directory provisioned: host=${hostRuntimeDir.path} guest=$guestRuntimeDir mode=0700",
        )

        val socketName = allocateSocketName(hostRuntimeDir)
            ?: throw guiError(
                GuiFailureKind.SESSION_SETUP_FAILED,
                "no free Wayland socket name available",
                "dir=${hostRuntimeDir.path} tried=$preferredSocketName..$maxSocketCandidates",
                log,
                GuiLogCategory.WAYLAND,
            )

        val hostLogDir = File(storage.logsDir(environmentId), "gui").apply { mkdirs() }

        val info = WaylandSessionInfo(
            environmentId = environmentId,
            sessionId = sessionId,
            runtimeDir = guestRuntimeDir,
            hostRuntimeDir = hostRuntimeDir.absolutePath,
            socketName = socketName,
            socketPath = "$guestRuntimeDir/$socketName",
            hostSocketPath = File(hostRuntimeDir, socketName).absolutePath,
            logDir = GUEST_LOG_DIR,
            environment = sessionEnvironment(guestRuntimeDir, socketName),
        )
        log.info(
            GuiLogCategory.WAYLAND,
            "wayland session environment prepared: socket=$socketName XDG_RUNTIME_DIR=$guestRuntimeDir",
        )
        info
    }

    override suspend fun release(session: WaylandSessionInfo) = withContext(Dispatchers.IO) {
        val log = guiLogFactory(session.environmentId)
        val dir = File(session.hostRuntimeDir)
        if (!dir.isDirectory) {
            log.info(GuiLogCategory.WAYLAND, "wayland runtime directory already absent: ${dir.path}")
            return@withContext
        }
        // Remove transient session artefacts only. The rootfs is never touched.
        val removed = dir.listFiles().orEmpty().count { file ->
            val transient = file.name == session.socketName ||
                file.name.startsWith("${session.socketName}.") ||
                file.name.startsWith("wayland-") && file.name.endsWith(".lock")
            transient && file.delete()
        }
        log.info(
            GuiLogCategory.WAYLAND,
            "wayland runtime state cleaned: dir=${dir.path} removed=$removed socket=${session.socketName}",
        )
    }

    /** Host directory backing the guest XDG_RUNTIME_DIR. */
    fun hostRuntimeDir(environmentId: EnvironmentId): File =
        File(storage.runtimeStateDir(environmentId), "wayland")

    private fun createRuntimeDir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw GuiError("Cannot create Wayland runtime directory: ${dir.path}")
        }
        // XDG requires the runtime dir to be private to its owner.
        dir.setReadable(false, false)
        dir.setWritable(false, false)
        dir.setExecutable(false, false)
        val ok = dir.setReadable(true, true) &&
            dir.setWritable(true, true) &&
            dir.setExecutable(true, true)
        if (!ok || !dir.canWrite()) {
            throw GuiError(
                "Wayland runtime directory is not usable (0700 required): ${dir.path} " +
                    "readable=${dir.canRead()} writable=${dir.canWrite()}",
            )
        }
    }

    /**
     * Picks a socket name that is not already present in the runtime directory,
     * starting from [preferredSocketName]. Never blindly assumes `wayland-0`.
     */
    private fun allocateSocketName(hostRuntimeDir: File): String? {
        if (isFree(hostRuntimeDir, preferredSocketName)) return preferredSocketName
        for (index in 0 until maxSocketCandidates) {
            val candidate = "wayland-$index"
            if (isFree(hostRuntimeDir, candidate)) return candidate
        }
        return null
    }

    private fun isFree(dir: File, name: String): Boolean =
        !File(dir, name).exists() && !File(dir, "$name.lock").exists()

    private fun sessionEnvironment(runtimeDir: String, socketName: String): Map<String, String> = mapOf(
        "XDG_RUNTIME_DIR" to runtimeDir,
        "WAYLAND_DISPLAY" to socketName,
        "XDG_SESSION_TYPE" to "wayland",
        // Toolkit hints so Wayland clients do not fall back to X11.
        "GDK_BACKEND" to "wayland",
        "QT_QPA_PLATFORM" to "wayland",
        "SDL_VIDEODRIVER" to "wayland",
        "CLUTTER_BACKEND" to "wayland",
        "MOZ_ENABLE_WAYLAND" to "1",
    )

    private fun guiError(
        kind: GuiFailureKind,
        message: String,
        detail: String,
        log: GuiLog,
        category: GuiLogCategory,
    ): GuiError {
        val failure = GuiFailure(kind = kind, message = message, detail = detail)
        log.failure(category, failure)
        return GuiError(failure.describe())
    }

    companion object {
        const val DEFAULT_GUEST_RUNTIME_DIR = "/run/linuxdroid"
        const val GUEST_LOG_DIR = "/run/linuxdroid/logs"
    }
}
