package com.linuxdroid.core.gui

/**
 * Boundary contracts for moving pixels and input across the Android/Linux line.
 *
 * ```
 * Weston -> LinuxDroid graphics interface -> Android graphics interface -> Surface
 * Android input -> LinuxDroid input bridge -> Wayland input -> Weston
 * ```
 *
 * Both interfaces are intentionally Android-free: the Android implementation
 * lives on the Android side of the boundary (core-display / core-input /
 * native bridge) and is injected in. Implemented in later phases.
 */

/** Geometry and density of the surface the compositor presents into. */
data class DisplayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Display geometry must be positive" }
    }
}

/**
 * Display transport: connects the compositor output to the host presentation
 * target. Owned by the GUI runtime, implemented on the Android side.
 */
interface DisplayTransport {
    /** True once a presentation target is attached and usable. */
    val isAttached: Boolean

    /** Current geometry, or null when nothing is attached. */
    val geometry: DisplayGeometry?

    /** Attaches the transport for [session]. Throws on failure; never no-ops silently. */
    suspend fun attach(session: WaylandSessionInfo, geometry: DisplayGeometry)

    /** Reports a geometry change (rotation, resize, density change). */
    suspend fun onGeometryChanged(geometry: DisplayGeometry)

    /** Detaches and releases all presentation resources. Idempotent. */
    suspend fun detach()
}

/** Normalized input event kinds crossing the bridge. */
enum class GuiInputKind { TOUCH, POINTER, KEYBOARD, SCROLL }

/**
 * Input transport: delivers host input to the Wayland session.
 *
 * Linux applications only ever see normal Wayland input; no Android type
 * appears in this interface.
 */
interface InputTransport {
    val isAttached: Boolean

    /** Kinds this transport can actually deliver, determined by probing. */
    fun supportedKinds(): Set<GuiInputKind>

    suspend fun attach(session: WaylandSessionInfo, geometry: DisplayGeometry)

    suspend fun onGeometryChanged(geometry: DisplayGeometry)

    suspend fun detach()
}
