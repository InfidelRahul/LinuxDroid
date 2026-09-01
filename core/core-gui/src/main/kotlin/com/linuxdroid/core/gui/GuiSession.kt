package com.linuxdroid.core.gui

import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.flow.Flow

/**
 * Ordered stages of the graphical session, matching the approved lifecycle:
 *
 * ```
 * Prepare environment -> start GUI services -> start compositor
 *   -> start shell -> DESKTOP READY -> applications
 *
 * shutdown: stop applications -> stop shell -> stop compositor
 *   -> stop GUI services -> STOPPED
 * ```
 *
 * The session controller owns this ordering. The shell never starts the Linux
 * environment, and the compositor never starts the shell.
 */
enum class GuiSessionStage {
    IDLE,
    PREPARING_ENVIRONMENT,
    STARTING_SERVICES,
    STARTING_COMPOSITOR,
    STARTING_SHELL,
    READY,
    STOPPING_APPLICATIONS,
    STOPPING_SHELL,
    STOPPING_COMPOSITOR,
    STOPPING_SERVICES,
    STOPPED,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == STOPPED || this == FAILED
}

/** Immutable snapshot of the graphical session. */
data class GuiSessionStatus(
    val sessionId: SessionId,
    val stage: GuiSessionStage = GuiSessionStage.IDLE,
    val gui: GuiRuntimeStatus = GuiRuntimeStatus(),
    val shell: ShellStatus? = null,
    val failure: GuiFailure? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isReady: Boolean get() = stage == GuiSessionStage.READY && gui.isUsable
}

/**
 * Orchestrates the full graphical session lifecycle for the single active
 * environment.
 *
 * Responsibility boundary: this coordinates the existing Linux runtime
 * (`:core:core-runtime`), the [GuiRuntime], and the [DesktopShell]. It contains
 * no compositor, rendering, or UI logic of its own.
 */
interface GuiSessionController {
    val status: GuiSessionStatus?

    val statusUpdates: Flow<GuiSessionStatus>

    /**
     * Runs the startup sequence through to [GuiSessionStage.READY].
     *
     * @throws com.linuxdroid.core.model.GuiError with the failing stage when any
     * step fails; the session is left in [GuiSessionStage.FAILED], never
     * reported as ready.
     */
    suspend fun start(environment: Environment, settings: DesktopSettings): GuiSessionStatus

    /** Runs the shutdown sequence in reverse order. Idempotent. */
    suspend fun stop()
}

/** Lifecycle state of the desktop shell process. */
enum class ShellState { STOPPED, STARTING, RUNNING, FAILED }

/** Immutable snapshot of the desktop shell. */
data class ShellStatus(
    val state: ShellState = ShellState.STOPPED,
    /** Host PID of the shell process, or -1 when not running. */
    val pid: Int = -1,
    val failure: GuiFailure? = null,
)

/**
 * The LinuxDroid desktop shell: desktop background, status bar, dock and
 * launcher surfaces.
 *
 * The shell is a Wayland client of the compositor and is replaceable. It must
 * not manage processes itself — application launching goes through a dedicated
 * application registry introduced in a later phase — and it must not start the
 * Linux environment. Implemented from Phase 5 onward.
 */
interface DesktopShell {
    val status: ShellStatus

    val statusUpdates: Flow<ShellStatus>

    /** Starts the shell against a ready Wayland session. */
    suspend fun start(session: WaylandSessionInfo, settings: DesktopSettings): ShellStatus

    /** Stops the shell. Idempotent. */
    suspend fun stop()
}
