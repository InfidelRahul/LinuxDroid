package com.linuxdroid.core.gui

/**
 * Lifecycle state of the GUI runtime and of any compositor it owns.
 *
 * The same state machine is used for both the GUI runtime as a whole and for a
 * single [Compositor], because both follow identical transitions. Keeping one
 * enum avoids duplicating near-identical state definitions.
 *
 * Legal transitions:
 * ```
 * DISABLED     -> INITIALIZING
 * INITIALIZING -> STARTING | ERROR | STOPPED
 * STARTING     -> READY | ERROR | STOPPING
 * READY        -> RUNNING | STOPPING | ERROR
 * RUNNING      -> STOPPING | ERROR
 * STOPPING     -> STOPPED | ERROR
 * STOPPED      -> INITIALIZING | DISABLED
 * ERROR        -> INITIALIZING | STOPPING | STOPPED | DISABLED
 * ```
 */
enum class GuiState {
    /** GUI is not enabled for this environment. */
    DISABLED,

    /** Capabilities are being probed and the session environment prepared. */
    INITIALIZING,

    /** The compositor process has been spawned but readiness is unproven. */
    STARTING,

    /** Readiness was *observed* (e.g. Wayland socket present and accepting). */
    READY,

    /** The graphical session is serving clients. */
    RUNNING,

    /** Ordered shutdown in progress. */
    STOPPING,

    /** Fully stopped and cleaned up. */
    STOPPED,

    /** A failure occurred; [GuiRuntimeStatus.failure] carries the reason. */
    ERROR,
    ;

    val isActive: Boolean
        get() = this == INITIALIZING || this == STARTING || this == READY ||
            this == RUNNING || this == STOPPING

    /** True once the compositor is proven usable by Wayland clients. */
    val isUsable: Boolean
        get() = this == READY || this == RUNNING

    fun canTransitionTo(next: GuiState): Boolean = next in allowedNext

    private val allowedNext: Set<GuiState>
        get() = when (this) {
            DISABLED -> setOf(INITIALIZING)
            INITIALIZING -> setOf(STARTING, STOPPED, ERROR)
            STARTING -> setOf(READY, STOPPING, ERROR)
            READY -> setOf(RUNNING, STOPPING, ERROR)
            RUNNING -> setOf(STOPPING, ERROR)
            STOPPING -> setOf(STOPPED, ERROR)
            STOPPED -> setOf(INITIALIZING, DISABLED)
            ERROR -> setOf(INITIALIZING, STOPPING, STOPPED, DISABLED)
        }
}

/**
 * Classification of a GUI failure. Used for logging, diagnostics and to decide
 * whether a restart can plausibly succeed.
 */
enum class GuiFailureKind {
    /** No graphics capability usable by any supported backend was found. */
    NO_VIABLE_BACKEND,

    /** Preparing XDG_RUNTIME_DIR / sockets / directories failed. */
    SESSION_SETUP_FAILED,

    /** The compositor process could not be spawned. */
    COMPOSITOR_LAUNCH_FAILED,

    /** The process started but never became observably ready. */
    COMPOSITOR_READINESS_TIMEOUT,

    /** The compositor terminated unexpectedly while active. */
    COMPOSITOR_CRASHED,

    /** Display transport could not be established. */
    DISPLAY_TRANSPORT_FAILED,

    /** Input transport could not be established. */
    INPUT_TRANSPORT_FAILED,

    /** Shutdown did not complete cleanly. */
    SHUTDOWN_FAILED,

    /** Anything not covered above. */
    INTERNAL_ERROR,
}

/**
 * A GUI failure. Never swallow one of these: a failure must surface as
 * [GuiState.ERROR] and must be logged.
 */
data class GuiFailure(
    val kind: GuiFailureKind,
    val message: String,
    val detail: String? = null,
    val cause: Throwable? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun describe(): String = buildString {
        append('[').append(kind.name).append("] ").append(message)
        detail?.let { append(" | ").append(it) }
        cause?.let { append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message) }
    }
}

/**
 * Immutable snapshot of the GUI runtime for observers (UI, diagnostics, tests).
 */
data class GuiRuntimeStatus(
    val state: GuiState = GuiState.DISABLED,
    val compositor: CompositorStatus? = null,
    val session: WaylandSessionInfo? = null,
    val capabilities: GraphicsCapabilities? = null,
    val failure: GuiFailure? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isUsable: Boolean get() = state.isUsable && compositor?.state?.isUsable == true
}
