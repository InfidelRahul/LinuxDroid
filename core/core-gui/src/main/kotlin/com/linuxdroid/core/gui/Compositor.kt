package com.linuxdroid.core.gui

import kotlinx.coroutines.flow.Flow

/**
 * Identifies a compositor implementation. Weston is the first one, but the
 * GUI runtime must never assume Weston: it only talks to [Compositor].
 */
data class CompositorId(val value: String) {
    init {
        require(value.isNotBlank()) { "CompositorId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        val WESTON = CompositorId("weston")
    }
}

/**
 * Rendering/presentation backend a compositor can be asked to use.
 *
 * The concrete mapping to compositor command-line flags belongs to the
 * compositor implementation, not to the GUI runtime.
 */
enum class CompositorBackend {
    /** Present into an Android Surface via the LinuxDroid graphics interface. */
    ANDROID_SURFACE,

    /** Headless: no presentation, useful for probing and tests. */
    HEADLESS,

    /** Software/pixman rendering into a shared-memory buffer. */
    SOFTWARE,

    /** Nested inside another Wayland compositor (development hosts only). */
    NESTED_WAYLAND,
}

/**
 * A backend choice plus the probed evidence that justified it.
 * Backends are never chosen blindly; [rationale] must reference probe results.
 */
data class BackendSelection(
    val backend: CompositorBackend,
    val rationale: String,
    val hardwareAccelerated: Boolean,
    val rejected: Map<CompositorBackend, String> = emptyMap(),
)

/**
 * Chooses the least-privileged viable compositor backend from probed
 * capabilities. Never assumes DRM/KMS or root.
 */
interface CompositorBackendSelector {
    /**
     * @return the selection, or `null` when no backend is viable — the caller
     * must then fail with [GuiFailureKind.NO_VIABLE_BACKEND].
     */
    fun select(capabilities: GraphicsCapabilities): BackendSelection?
}

/** Immutable snapshot of a compositor instance. */
data class CompositorStatus(
    val id: CompositorId,
    val state: GuiState,
    val backend: CompositorBackend? = null,
    /** Host PID of the compositor process, or -1 when not running. */
    val pid: Int = -1,
    /** Wayland socket name once established (e.g. "wayland-0"). */
    val waylandSocket: String? = null,
    val failure: GuiFailure? = null,
    val startedAt: Long? = null,
    val stoppedAt: Long? = null,
)

/**
 * Everything a compositor needs in order to start. Built by the GUI runtime so
 * that compositor implementations stay free of environment discovery logic.
 */
data class CompositorLaunchRequest(
    val session: WaylandSessionInfo,
    val backend: BackendSelection,
    /** Extra environment variables merged over the session environment. */
    val extraEnv: Map<String, String> = emptyMap(),
    /** How long to wait for *observed* readiness before failing. */
    val readinessTimeoutMs: Long = 15_000,
)

/**
 * A replaceable Wayland compositor managed by the GUI runtime.
 *
 * Contract:
 * - [start] must not report success merely because the process was spawned.
 *   It must return only once [CompositorReadinessProbe] observed readiness, or
 *   throw with an appropriate [GuiFailure].
 * - [stop] must be idempotent and must clean up sockets/processes it created.
 */
interface Compositor {
    val id: CompositorId

    /** Current status snapshot. */
    val status: CompositorStatus

    /** Status changes, for observers and diagnostics. */
    val statusUpdates: Flow<CompositorStatus>

    /**
     * True if this compositor implementation can run with [backend] in the
     * current environment (e.g. the binary exists inside the rootfs).
     */
    suspend fun isSupported(backend: CompositorBackend): Boolean

    /** Starts the compositor and waits for observed readiness. */
    suspend fun start(request: CompositorLaunchRequest): CompositorStatus

    /** Stops the compositor cleanly. Idempotent. */
    suspend fun stop()
}

/**
 * Determines whether a compositor actually became ready.
 *
 * Readiness must be based on observable session state (Wayland socket present
 * and accepting a connection, compositor log markers), never on exec() success.
 */
interface CompositorReadinessProbe {
    /**
     * Suspends until readiness is observed or [timeoutMs] elapses.
     *
     * @return true only when readiness was positively observed.
     */
    suspend fun awaitReady(session: WaylandSessionInfo, timeoutMs: Long): Boolean
}

/**
 * Creates compositor instances. Keeps the GUI runtime free of any
 * Weston-specific construction detail.
 */
interface CompositorFactory {
    val id: CompositorId
    fun create(): Compositor
}
