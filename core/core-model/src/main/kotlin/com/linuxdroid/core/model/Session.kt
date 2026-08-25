package com.linuxdroid.core.model

import java.time.Instant

/**
 * Unique identifier for an active session.
 */
@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun generate(): SessionId = SessionId(java.util.UUID.randomUUID().toString())
    }
}

/**
 * Lifecycle state of a session.
 */
enum class SessionState {
    /** Session is being created. */
    INITIALIZING,
    /** Runtime is starting. */
    STARTING_RUNTIME,
    /** Wayland compositor is starting. */
    STARTING_COMPOSITOR,
    /** Desktop environment is starting. */
    STARTING_DESKTOP,
    /** Session is fully active. */
    RUNNING,
    /** Session is shutting down. */
    STOPPING,
    /** Session stopped cleanly. */
    STOPPED,
    /** Session failed. */
    FAILED;

    fun isActive(): Boolean = this in setOf(INITIALIZING, STARTING_RUNTIME,
        STARTING_COMPOSITOR, STARTING_DESKTOP, RUNNING, STOPPING)
}

/**
 * A Session represents a complete active Linux graphical environment.
 * It owns the runtime, compositor, desktop, input, audio, and network.
 */
data class Session(
    val id: SessionId,
    val environmentId: EnvironmentId,
    val state: SessionState,
    val startedAt: Long = System.currentTimeMillis(),
    val stoppedAt: Long? = null,
    val failureMessage: String? = null,
    /** PID of the proot/runtime process. -1 if not running. */
    val runtimePid: Int = -1,
    /** PID of the Wayland compositor. -1 if not running. */
    val compositorPid: Int = -1,
    /** PID of the desktop session. -1 if not running. */
    val desktopPid: Int = -1,
    /** Wayland socket name (e.g. "wayland-0"). */
    val waylandSocket: String? = null,
    /** DISPLAY variable if XWayland is running. */
    val display: String? = null,
)
