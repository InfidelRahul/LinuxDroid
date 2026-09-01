package com.linuxdroid.core.gui

import com.linuxdroid.core.model.Environment
import kotlinx.coroutines.flow.Flow

/**
 * Configuration for one graphical session request.
 */
data class GuiRuntimeConfig(
    /** When false the GUI runtime stays in [GuiState.DISABLED]. */
    val enabled: Boolean = true,
    /** Which compositor to use. Weston is the default, not a hard dependency. */
    val compositorId: CompositorId = CompositorId.WESTON,
    /** Preferred socket name; the provisioner may pick another free name. */
    val preferredSocketName: String = "wayland-0",
    /** Maximum time to wait for *observed* compositor readiness. */
    val readinessTimeoutMs: Long = 15_000,
    /** Optional backend override for diagnostics; null means "probe and select". */
    val forcedBackend: CompositorBackend? = null,
)

/**
 * The GUI Runtime: the single owner of the graphical stack.
 *
 * Responsibilities (and nothing beyond them):
 * - GUI initialization and capability probing
 * - compositor lifecycle (through [Compositor], never Weston directly)
 * - Wayland session lifecycle
 * - display and input transport lifecycle
 * - GUI readiness state, error reporting and logging
 * - GUI shutdown
 *
 * Explicit non-responsibilities: desktop shell logic, application launching,
 * Android UI, and package management.
 */
interface GuiRuntime {
    /** Current status snapshot. */
    val status: GuiRuntimeStatus

    /** Status changes for UI, diagnostics and tests. */
    val statusUpdates: Flow<GuiRuntimeStatus>

    /**
     * Probes capabilities and prepares the session environment.
     * Does not start a compositor.
     *
     * @throws com.linuxdroid.core.model.GuiError on unrecoverable setup failure.
     */
    suspend fun initialize(environment: Environment, config: GuiRuntimeConfig): GuiRuntimeStatus

    /**
     * Starts the compositor and waits for observed readiness.
     * Must be called after [initialize].
     *
     * @throws com.linuxdroid.core.model.GuiError if readiness cannot be proven.
     */
    suspend fun start(): GuiRuntimeStatus

    /** Stops the graphical session cleanly and releases session state. */
    suspend fun shutdown()

    /** Convenience: [shutdown] followed by [start] with the existing config. */
    suspend fun restart(): GuiRuntimeStatus

    /** Last probed capabilities, or [GraphicsCapabilities.UNPROBED]. */
    fun capabilities(): GraphicsCapabilities
}

/**
 * Registry of available compositor implementations, so that adding or
 * replacing a compositor does not require touching the GUI runtime.
 */
interface CompositorRegistry {
    fun ids(): Set<CompositorId>
    fun factory(id: CompositorId): CompositorFactory?
}

/** Simple in-memory [CompositorRegistry]. */
class DefaultCompositorRegistry(
    factories: List<CompositorFactory> = emptyList(),
) : CompositorRegistry {
    private val byId: Map<CompositorId, CompositorFactory> = factories.associateBy { it.id }

    override fun ids(): Set<CompositorId> = byId.keys

    override fun factory(id: CompositorId): CompositorFactory? = byId[id]
}
