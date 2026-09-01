package com.linuxdroid.core.gui

import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.GuiError
import com.linuxdroid.core.model.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default [GuiRuntime]: owns display bring-up, Wayland session provisioning and
 * compositor lifecycle for the single active environment.
 *
 * It contains no compositor-specific and no Android-specific code: the
 * compositor arrives through [CompositorRegistry] and the display output
 * through [DisplayTransport].
 */
class DefaultGuiRuntime(
    private val capabilityProbe: GraphicsCapabilityProbe,
    private val backendSelector: CompositorBackendSelector,
    private val sessionProvisioner: WaylandSessionProvisioner,
    private val compositorRegistry: CompositorRegistry,
    private val displayTransport: DisplayTransport,
    private val guiLogFactory: (Environment) -> GuiLog,
    private val geometryProvider: () -> DisplayGeometry?,
    private val sessionIdProvider: () -> SessionId = { SessionId.generate() },
) : GuiRuntime {

    private val _status = MutableStateFlow(GuiRuntimeStatus())
    override val statusUpdates: Flow<GuiRuntimeStatus> = _status.asStateFlow()
    override val status: GuiRuntimeStatus get() = _status.value

    private var environment: Environment? = null
    private var config: GuiRuntimeConfig = GuiRuntimeConfig()
    private var log: GuiLog? = null
    private var compositor: Compositor? = null
    private var backend: BackendSelection? = null
    private var probed: GraphicsCapabilities = GraphicsCapabilities.UNPROBED

    override fun capabilities(): GraphicsCapabilities = probed

    override suspend fun initialize(
        environment: Environment,
        config: GuiRuntimeConfig,
    ): GuiRuntimeStatus {
        this.environment = environment
        this.config = config
        val guiLog = guiLogFactory(environment).also { log = it }

        if (!config.enabled) {
            guiLog.info(GuiLogCategory.GUI, "gui runtime disabled by configuration")
            update(GuiState.DISABLED)
            return status
        }

        transition(GuiState.INITIALIZING)
        guiLog.info(GuiLogCategory.GUI, "display initialization started")

        probed = try {
            capabilityProbe.probe()
        } catch (e: Exception) {
            return fail(
                GuiFailureKind.INTERNAL_ERROR,
                "graphics capability probe failed",
                "reason=${e.message}",
                e,
                GuiLogCategory.GRAPHICS,
            )
        }
        guiLog.info(GuiLogCategory.GRAPHICS, "graphics capabilities probed: ${probed.summary()}")
        _status.value = _status.value.copy(capabilities = probed)

        val geometry = geometryProvider()
            ?: return fail(
                GuiFailureKind.DISPLAY_TRANSPORT_FAILED,
                "android display surface unavailable",
                "reason=no output surface attached to the host graphics boundary",
                null,
                GuiLogCategory.GRAPHICS,
            )

        val selection = config.forcedBackend
            ?.let {
                BackendSelection(it, "backend forced by configuration", hardwareAccelerated = false)
            }
            ?: backendSelector.select(probed)
            ?: return fail(
                GuiFailureKind.NO_VIABLE_BACKEND,
                "no viable compositor backend for the probed capabilities",
                "capabilities=${probed.summary()}",
                null,
                GuiLogCategory.GRAPHICS,
            )
        backend = selection
        guiLog.info(
            GuiLogCategory.GRAPHICS,
            "display backend selected: ${selection.backend} " +
                "hardwareAccelerated=${selection.hardwareAccelerated} reason=${selection.rationale}",
        )

        val factory = compositorRegistry.factory(config.compositorId)
            ?: return fail(
                GuiFailureKind.COMPOSITOR_LAUNCH_FAILED,
                "configured compositor is not registered",
                "compositor=${config.compositorId} available=${compositorRegistry.ids()}",
                null,
                GuiLogCategory.COMPOSITOR,
            )
        val instance = factory.create()
        if (!instance.isSupported(selection.backend)) {
            return fail(
                GuiFailureKind.COMPOSITOR_LAUNCH_FAILED,
                "compositor cannot run with the selected backend",
                "compositor=${config.compositorId} backend=${selection.backend}",
                null,
                GuiLogCategory.COMPOSITOR,
            )
        }
        compositor = instance

        val session = try {
            sessionProvisioner.provision(environment.id, sessionIdProvider())
        } catch (e: Exception) {
            return fail(
                GuiFailureKind.SESSION_SETUP_FAILED,
                "wayland runtime directory could not be provisioned",
                "reason=${e.message}",
                e,
                GuiLogCategory.WAYLAND,
            )
        }

        try {
            displayTransport.attach(session, geometry)
        } catch (e: Exception) {
            releaseSession(session)
            return fail(
                GuiFailureKind.DISPLAY_TRANSPORT_FAILED,
                "display transport could not be attached",
                "reason=${e.message}",
                e,
                GuiLogCategory.GRAPHICS,
            )
        }
        guiLog.info(
            GuiLogCategory.GRAPHICS,
            "display transport attached: ${geometry.widthPx}x${geometry.heightPx} @ ${geometry.densityDpi}dpi",
        )

        _status.value = _status.value.copy(session = session, compositor = instance.status)
        guiLog.info(GuiLogCategory.GUI, "gui runtime initialized")
        return status
    }

    override suspend fun start(): GuiRuntimeStatus {
        val guiLog = log ?: throw GuiError("GUI runtime start() called before initialize()")
        val session = status.session
            ?: throw GuiError("GUI runtime start() called before a Wayland session was provisioned")
        val selection = backend ?: throw GuiError("GUI runtime start() called before backend selection")
        val instance = compositor ?: throw GuiError("GUI runtime start() called before compositor resolution")

        transition(GuiState.STARTING)
        val compositorStatus = try {
            instance.start(
                CompositorLaunchRequest(
                    session = session,
                    backend = selection,
                    readinessTimeoutMs = config.readinessTimeoutMs,
                ),
            )
        } catch (e: Exception) {
            // The compositor already logged the structured failure.
            val failure = instance.status.failure ?: GuiFailure(
                kind = GuiFailureKind.COMPOSITOR_LAUNCH_FAILED,
                message = "compositor startup failed",
                detail = e.message,
                cause = e,
            )
            cleanupAfterFailure(session)
            _status.value = _status.value.copy(
                state = GuiState.ERROR,
                compositor = instance.status,
                failure = failure,
                updatedAt = System.currentTimeMillis(),
            )
            throw GuiError(failure.describe(), e)
        }

        if (!compositorStatus.state.isUsable) {
            cleanupAfterFailure(session)
            return fail(
                GuiFailureKind.COMPOSITOR_READINESS_TIMEOUT,
                "compositor reported a non-usable state after startup",
                "state=${compositorStatus.state}",
                null,
                GuiLogCategory.COMPOSITOR,
            )
        }

        transition(GuiState.READY)
        _status.value = _status.value.copy(compositor = compositorStatus)
        guiLog.info(GuiLogCategory.GUI, "gui session READY: socket=${session.socketName}")

        instance.markRunning()
        transition(GuiState.RUNNING)
        _status.value = _status.value.copy(compositor = instance.status)
        return status
    }

    override suspend fun shutdown() {
        val guiLog = log ?: return
        if (status.state == GuiState.STOPPED || status.state == GuiState.DISABLED) return

        // INITIALIZING has no direct edge to STOPPING (nothing was started yet),
        // so an initialized-but-never-started runtime stops directly.
        if (status.state == GuiState.INITIALIZING) {
            releaseInitializedState()
            return
        }

        transition(GuiState.STOPPING)
        guiLog.info(GuiLogCategory.GUI, "gui session shutdown requested")

        val errors = mutableListOf<String>()

        try {
            compositor?.stop()
        } catch (e: Exception) {
            errors += "compositor stop failed: ${e.message}"
            guiLog.error(GuiLogCategory.COMPOSITOR, "compositor stop failed", e)
        }

        try {
            displayTransport.detach()
            guiLog.info(GuiLogCategory.GRAPHICS, "display resources released")
        } catch (e: Exception) {
            errors += "display detach failed: ${e.message}"
            guiLog.error(GuiLogCategory.GRAPHICS, "display transport detach failed", e)
        }

        status.session?.let { releaseSession(it) }

        compositor = null
        _status.value = _status.value.copy(
            state = GuiState.STOPPED,
            session = null,
            compositor = null,
            updatedAt = System.currentTimeMillis(),
        )
        if (errors.isEmpty()) {
            guiLog.info(GuiLogCategory.GUI, "gui session STOPPED cleanly")
        } else {
            guiLog.warn(GuiLogCategory.GUI, "gui session STOPPED with errors: ${errors.joinToString("; ")}")
        }
    }

    override suspend fun restart(): GuiRuntimeStatus {
        val env = environment ?: throw GuiError("GUI runtime restart() called before initialize()")
        shutdown()
        initialize(env, config)
        return start()
    }

    /** Tears down state created by [initialize] when nothing was ever started. */
    private suspend fun releaseInitializedState() {
        runCatching { displayTransport.detach() }
            .onFailure { log?.warn(GuiLogCategory.GRAPHICS, "display detach failed during shutdown", it) }
        status.session?.let { releaseSession(it) }
        compositor = null
        _status.value = _status.value.copy(
            state = GuiState.STOPPED,
            session = null,
            compositor = null,
            updatedAt = System.currentTimeMillis(),
        )
        log?.info(GuiLogCategory.GUI, "gui session STOPPED cleanly")
    }

    private suspend fun cleanupAfterFailure(session: WaylandSessionInfo) {
        runCatching { displayTransport.detach() }
            .onFailure { log?.warn(GuiLogCategory.GRAPHICS, "display detach failed during cleanup", it) }
        releaseSession(session)
    }

    private suspend fun releaseSession(session: WaylandSessionInfo) {
        runCatching { sessionProvisioner.release(session) }
            .onFailure { log?.warn(GuiLogCategory.WAYLAND, "wayland session release failed", it) }
    }

    private fun transition(next: GuiState) {
        val current = _status.value.state
        if (current != next && !current.canTransitionTo(next)) {
            throw GuiError("Illegal GUI state transition: $current -> $next")
        }
        update(next)
    }

    private fun update(next: GuiState) {
        _status.value = _status.value.copy(
            state = next,
            failure = if (next == GuiState.ERROR) _status.value.failure else null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun fail(
        kind: GuiFailureKind,
        message: String,
        detail: String,
        cause: Throwable?,
        category: GuiLogCategory,
    ): Nothing {
        val failure = GuiFailure(kind = kind, message = message, detail = detail, cause = cause)
        log?.failure(category, failure)
        _status.value = _status.value.copy(
            state = GuiState.ERROR,
            failure = failure,
            updatedAt = System.currentTimeMillis(),
        )
        throw GuiError(failure.describe(), cause)
    }
}
