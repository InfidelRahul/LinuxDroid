package com.linuxdroid.core.session

import com.linuxdroid.core.audio.AudioManager
import com.linuxdroid.core.display.DisplayManager
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.gpu.GpuManager
import com.linuxdroid.core.input.InputManager
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.network.NetworkManager
import com.linuxdroid.core.runtime.RuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller managing a graphical desktop session (Wayland compositor + Desktop environment).
 */
class DesktopSession(
    val sessionId: SessionId,
    val environment: Environment,
    private val runtimeManager: RuntimeManager,
    private val storage: EnvironmentStorage,
    private val displayManager: DisplayManager? = null,
    private val gpuManager: GpuManager? = null,
    private val inputManager: InputManager? = null,
    private val audioManager: AudioManager? = null,
    private val networkManager: NetworkManager? = null,
) {
    private val log = LinuxDroidLogger(LogSubsystem.SESSION, environment.id, sessionId)

    suspend fun start(): Session = withContext(Dispatchers.IO) {
        log.info("Starting desktop session for ${environment.id}")

        // 1. Initialize host subsystems
        gpuManager?.detect()
        audioManager?.start(
            sampleRate = if (environment.configuration.audio.latencyHintMs > 0) 48000 else 44100,
            channels = 2,
        )
        inputManager?.start()
        networkManager?.applyConfig(environment.configuration.network)
        displayManager?.applyConfig(environment.configuration.display)

        val waylandSocket = "wayland-0"
        val rootfsDir = storage.rootfsDir(environment.id)
        ensureGuiSessionEnvironment(rootfsDir)

        val spec = RuntimeSpec.fromEnvironment(
            environment = environment,
            command = listOf("/bin/sh", "/usr/local/bin/linuxdroid-session"),
            workingDirectory = "/home/user",
            extraEnv = mapOf(
                "WAYLAND_DISPLAY" to waylandSocket,
                "XDG_RUNTIME_DIR" to "/tmp",
                "DISPLAY" to ":0",
            ),
        )

        val procHandle = runtimeManager.execute(spec, sessionId)

        Session(
            id = sessionId,
            environmentId = environment.id,
            state = SessionState.RUNNING,
            waylandSocket = waylandSocket,
            display = if (environment.configuration.desktop.xwaylandEnabled) ":0" else null,
            compositorPid = procHandle.pid,
            runtimePid = procHandle.pid,
        )
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        log.info("Stopping desktop session")
        try {
            audioManager?.stop()
            inputManager?.stop()
            runtimeManager.stop(environment.id)
        } catch (e: Exception) {
            log.warn("Error during desktop session teardown", e)
        }
    }

    private fun ensureGuiSessionEnvironment(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
        val sessionScript = File(binDir, "linuxdroid-session")
        if (!sessionScript.exists()) {
            sessionScript.writeText(
                """
                #!/bin/sh
                export XDG_RUNTIME_DIR=/tmp
                export WAYLAND_DISPLAY=${'$'}{WAYLAND_DISPLAY:-wayland-0}
                exec dbus-launch --exit-with-session /bin/sh
                """.trimIndent()
            )
            sessionScript.setExecutable(true, false)
        }
    }
}
