package com.linuxdroid.core.diagnostics

import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.storage.AndroidStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DiagnosticsManager collects and reports status of all major subsystems.
 *
 * Call [generateReport] to get a snapshot diagnostic view of the environment.
 */
class DiagnosticsManager(
    private val storage: EnvironmentStorage,
    private val runtimeBackend: RuntimeBackend,
    private val storageManager: AndroidStorageManager,
) {
    private val log = LinuxDroidLogger(LogSubsystem.DIAGNOSTICS)

    suspend fun generateReport(environment: Environment, session: Session? = null): DiagnosticsReport {
        log.info("Generating diagnostics for ${environment.id}")
        return withContext(Dispatchers.IO) {
            DiagnosticsReport(
                environmentId = environment.id,
                sessionId = session?.id,
                runtime = checkRuntime(environment),
                filesystem = checkFilesystem(environment),
                linuxUserspace = checkLinuxUserspace(environment),
                wayland = checkWayland(session),
                xwayland = checkXwayland(session),
                gpu = checkGpu(),
                audio = checkAudio(),
                network = checkNetwork(),
                sharedStorage = checkSharedStorage(),
                resources = checkResources(),
            )
        }
    }

    private suspend fun checkRuntime(environment: Environment): DiagnosticCheck {
        return try {
            val healthy = runtimeBackend.healthCheck(environment)
            if (healthy) {
                DiagnosticCheck(
                    name = "Runtime (proot)",
                    status = DiagnosticStatus.OK,
                    detail = "proot runtime available",
                )
            } else {
                DiagnosticCheck(
                    name = "Runtime (proot)",
                    status = DiagnosticStatus.ERROR,
                    detail = "proot binary not available or not executable",
                    recommendation = "Ensure proot binary is bundled in assets/proot/<abi>/proot",
                )
            }
        } catch (e: Exception) {
            DiagnosticCheck(
                name = "Runtime (proot)",
                status = DiagnosticStatus.ERROR,
                detail = "Health check exception: ${e.message}",
            )
        }
    }

    private suspend fun checkFilesystem(environment: Environment): DiagnosticCheck {
        return try {
            val rootfsOk = storage.verifyRootfs(environment.id)
            if (rootfsOk) {
                val sizeMb = storage.rootfsSize(environment.id) / 1_048_576
                DiagnosticCheck(
                    name = "Filesystem",
                    status = DiagnosticStatus.OK,
                    detail = "Rootfs present (~${sizeMb}MB)",
                )
            } else {
                DiagnosticCheck(
                    name = "Filesystem",
                    status = DiagnosticStatus.ERROR,
                    detail = "Rootfs missing or incomplete at ${storage.rootfsDir(environment.id).path}",
                    recommendation = "Reinstall the Linux environment",
                )
            }
        } catch (e: Exception) {
            DiagnosticCheck(
                name = "Filesystem",
                status = DiagnosticStatus.ERROR,
                detail = "Exception: ${e.message}",
            )
        }
    }

    private suspend fun checkLinuxUserspace(environment: Environment): DiagnosticCheck {
        if (environment.state != EnvironmentState.RUNNING) {
            return DiagnosticCheck(
                name = "Linux Userspace",
                status = DiagnosticStatus.NOT_APPLICABLE,
                detail = "Environment not running (state: ${environment.state})",
            )
        }
        return try {
            val result = runtimeBackend.executeAndWait(
                environment = environment,
                command = listOf("/bin/sh", "-c", "echo 'LINUXDROID_OK'"),
                timeoutMs = 10_000,
            )
            if (result.exitCode == 0 && result.stdout.contains("LINUXDROID_OK")) {
                DiagnosticCheck(
                    name = "Linux Userspace",
                    status = DiagnosticStatus.OK,
                    detail = "/bin/sh responds correctly",
                )
            } else {
                DiagnosticCheck(
                    name = "Linux Userspace",
                    status = DiagnosticStatus.ERROR,
                    detail = "Shell test failed: exit=${result.exitCode} stdout='${result.stdout}'",
                )
            }
        } catch (e: Exception) {
            DiagnosticCheck(
                name = "Linux Userspace",
                status = DiagnosticStatus.ERROR,
                detail = "Exception: ${e.message}",
            )
        }
    }

    private fun checkWayland(session: Session?): DiagnosticCheck {
        val waylandRunning = session?.waylandSocket != null
        return DiagnosticCheck(
            name = "Wayland",
            status = if (waylandRunning) DiagnosticStatus.OK else DiagnosticStatus.NOT_APPLICABLE,
            detail = if (waylandRunning) "Compositor running (${session?.waylandSocket})" else "Not started",
        )
    }

    private fun checkXwayland(session: Session?): DiagnosticCheck {
        val xwaylandRunning = session?.display != null
        return DiagnosticCheck(
            name = "XWayland",
            status = if (xwaylandRunning) DiagnosticStatus.OK else DiagnosticStatus.NOT_APPLICABLE,
            detail = if (xwaylandRunning) "Running (${session?.display})" else "Not started",
        )
    }

    private fun checkGpu(): DiagnosticCheck {
        // Placeholder: GPU detection requires native implementation
        return DiagnosticCheck(
            name = "GPU",
            status = DiagnosticStatus.UNKNOWN,
            detail = "GPU capability detection not yet implemented",
        )
    }

    private fun checkAudio(): DiagnosticCheck {
        return DiagnosticCheck(
            name = "Audio",
            status = DiagnosticStatus.UNKNOWN,
            detail = "Audio subsystem not yet implemented",
        )
    }

    private fun checkNetwork(): DiagnosticCheck {
        return DiagnosticCheck(
            name = "Network",
            status = DiagnosticStatus.UNKNOWN,
            detail = "Network diagnostics not yet implemented",
        )
    }

    private fun checkSharedStorage(): DiagnosticCheck {
        return if (storageManager.isAuthorized()) {
            DiagnosticCheck(
                name = "Shared Storage",
                status = DiagnosticStatus.OK,
                detail = "Authorized: ${storageManager.sharedDirectory.path}",
            )
        } else {
            DiagnosticCheck(
                name = "Shared Storage",
                status = DiagnosticStatus.WARNING,
                detail = "Not authorized",
                recommendation = "Grant storage permission in Settings",
            )
        }
    }

    private fun checkResources(): DiagnosticCheck {
        return DiagnosticCheck(
            name = "Resources",
            status = DiagnosticStatus.UNKNOWN,
            detail = "Resource monitoring not yet implemented",
        )
    }
}
