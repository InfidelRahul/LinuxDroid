package com.linuxdroid.core.display

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.native_bridge.NativeBridge

/**
 * Controller for the native Wayland/libweston GUI host lifecycle.
 *
 * Establishes the lifecycle boundary between Android UI screens/services
 * and the native GuiHost through NativeBridge.
 *
 * All start/stop operations are idempotent and run the Wayland compositor
 * event loop on a dedicated native worker thread without blocking Android UI.
 */
class GuiHostController(
    private val bridge: NativeBridge = NativeBridge
) {
    private val log = LinuxDroidLogger(LogSubsystem.DISPLAY)

    enum class State(val value: Int) {
        STOPPED(0),
        STARTING(1),
        RUNNING(2),
        STOPPING(3);

        companion object {
            fun fromValue(value: Int): State = entries.firstOrNull { it.value == value } ?: STOPPED
        }
    }

    /**
     * Starts the native GUI host.
     * Idempotent: safe to call repeatedly.
     * Returns true if the host is RUNNING, false on initialization failure.
     */
    fun start(): Boolean {
        log.info("Requesting native GUI host start (current state=${getState()})")
        val result = bridge.guiStart()
        log.info("Native GUI host start completed: result=$result (state=${getState()})")
        return result
    }

    /**
     * Stops the native GUI host.
     * Idempotent: safe to call repeatedly when stopped.
     * Returns true if successfully stopped.
     */
    fun stop(): Boolean {
        log.info("Requesting native GUI host stop (current state=${getState()})")
        val result = bridge.guiStop()
        log.info("Native GUI host stop completed: result=$result (state=${getState()})")
        return result
    }

    /**
     * Returns true if the native GUI host is in RUNNING state.
     */
    fun isRunning(): Boolean = bridge.guiIsRunning()

    /**
     * Returns the current lifecycle state of the native GUI host.
     */
    fun getState(): State = State.fromValue(bridge.guiGetState())
}

