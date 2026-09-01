package com.linuxdroid.core.gui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the host presentation surface's lifecycle, independently of the
 * compositor session.
 *
 * This exists so "is there a surface?" has exactly one answer. The Android
 * view reports raw callbacks into it, the frame pump reads [canPresent] from
 * it, and neither keeps its own copy.
 *
 * Deliberately Android-free: the surface object itself never appears here,
 * only its state and geometry. Losing the surface is a normal, recoverable
 * event — it must never tear down the Linux environment.
 */
class SurfaceLifecycle(
    private val guiLog: () -> GuiLog? = { null },
) {

    private val _state = MutableStateFlow(SurfaceLifecycleState.NONE)

    /** Observable surface state. */
    val state: StateFlow<SurfaceLifecycleState> = _state.asStateFlow()

    @Volatile
    private var _geometry: DisplayGeometry? = null

    /** Geometry of the current surface, or null when there is none. */
    val geometry: DisplayGeometry? get() = _geometry

    /** True only when a frame may actually be presented right now. */
    val canPresent: Boolean get() = _state.value.canPresent

    /** Incremented every time a *new* surface appears, so stale frames can be discarded. */
    @Volatile
    var generation: Long = 0L
        private set

    /**
     * Moves to [next], returning false if the transition is not legal.
     *
     * Illegal transitions are refused rather than forced, so a bug shows up as
     * a rejected transition and a log line instead of a surface that is
     * presented to after being destroyed.
     */
    @Synchronized
    fun transitionTo(next: SurfaceLifecycleState): Boolean {
        val current = _state.value
        if (current == next) return true
        if (!current.canTransitionTo(next)) {
            guiLog()?.warn(
                GuiLogCategory.GRAPHICS,
                "rejected illegal surface transition $current -> $next",
            )
            return false
        }
        if (next == SurfaceLifecycleState.CREATED) generation += 1
        if (next == SurfaceLifecycleState.DESTROYED || next == SurfaceLifecycleState.NONE) {
            _geometry = null
        }
        _state.value = next
        guiLog()?.info(GuiLogCategory.GRAPHICS, "surface $current -> $next (generation=$generation)")
        return true
    }

    /** Records the surface appearing at [geometry]. */
    @Synchronized
    fun onSurfaceCreated(geometry: DisplayGeometry): Boolean {
        // A surface arriving while one is still held means the old one was
        // replaced without a destroy callback; settle to DESTROYED first.
        if (_state.value != SurfaceLifecycleState.NONE &&
            _state.value != SurfaceLifecycleState.DESTROYED
        ) {
            transitionTo(SurfaceLifecycleState.DETACHING)
            transitionTo(SurfaceLifecycleState.DESTROYED)
        }
        if (!transitionTo(SurfaceLifecycleState.CREATED)) return false
        _geometry = geometry
        return true
    }

    /** Records the sink having taken the surface. */
    fun onAttached(): Boolean = transitionTo(SurfaceLifecycleState.ATTACHED)

    /** Records the surface being ready for frames. */
    fun onActivated(): Boolean = transitionTo(SurfaceLifecycleState.ACTIVE)

    /**
     * Records a resize. Returns false when there is no live surface to resize.
     *
     * A resize drops back to [SurfaceLifecycleState.ATTACHED] so no frame is
     * presented against stale geometry until the sink is reconfigured.
     */
    @Synchronized
    fun onGeometryChanged(geometry: DisplayGeometry): Boolean {
        val current = _state.value
        if (current != SurfaceLifecycleState.ACTIVE && current != SurfaceLifecycleState.ATTACHED) {
            guiLog()?.warn(
                GuiLogCategory.GRAPHICS,
                "geometry change ignored: no live surface (state=$current)",
            )
            return false
        }
        _geometry = geometry
        if (current == SurfaceLifecycleState.ACTIVE) {
            _state.value = SurfaceLifecycleState.ATTACHED
        }
        guiLog()?.info(
            GuiLogCategory.GRAPHICS,
            "surface geometry changed: ${geometry.widthPx}x${geometry.heightPx} " +
                "@ ${geometry.densityDpi}dpi",
        )
        return true
    }

    /** Records the surface going away. Safe to call repeatedly. */
    @Synchronized
    fun onSurfaceDestroyed() {
        when (_state.value) {
            SurfaceLifecycleState.NONE, SurfaceLifecycleState.DESTROYED -> return
            SurfaceLifecycleState.DETACHING -> transitionTo(SurfaceLifecycleState.DESTROYED)
            else -> {
                transitionTo(SurfaceLifecycleState.DETACHING)
                transitionTo(SurfaceLifecycleState.DESTROYED)
            }
        }
    }
}
