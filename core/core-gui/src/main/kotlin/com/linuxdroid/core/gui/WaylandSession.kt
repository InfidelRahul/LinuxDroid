package com.linuxdroid.core.gui

import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.SessionId

/**
 * The fully-resolved Wayland session environment.
 *
 * Every path here is a **guest** (inside-rootfs) path, except [hostRuntimeDir]
 * which is the Android-side directory backing it. Nothing is inherited from the
 * Android process environment: the runtime establishes all of it explicitly.
 */
data class WaylandSessionInfo(
    val environmentId: EnvironmentId,
    val sessionId: SessionId,
    /** Guest XDG_RUNTIME_DIR, e.g. "/run/linuxdroid". */
    val runtimeDir: String,
    /** Android-side directory bound to [runtimeDir]. */
    val hostRuntimeDir: String,
    /** Wayland socket name, e.g. "wayland-0". */
    val socketName: String,
    /** Guest path of the Wayland socket. */
    val socketPath: String,
    /** Host path of the Wayland socket, used for readiness probing. */
    val hostSocketPath: String,
    /** Guest directory the compositor writes its logs into. */
    val logDir: String,
    /** Environment variables the graphical session must run with. */
    val environment: Map<String, String>,
) {
    init {
        require(runtimeDir.startsWith("/")) { "runtimeDir must be absolute" }
        require(socketName.isNotBlank()) { "socketName must not be blank" }
    }
}

/**
 * Creates and tears down the Wayland session environment: XDG_RUNTIME_DIR,
 * socket location, ownership and cleanup.
 *
 * Implementations must fail explicitly (never silently continue) when a
 * required directory cannot be created.
 */
interface WaylandSessionProvisioner {
    /** Prepares directories and computes the session environment. */
    suspend fun provision(environmentId: EnvironmentId, sessionId: SessionId): WaylandSessionInfo

    /**
     * Removes transient session state (stale sockets, lock files).
     * Must be safe to call after a crash and must never touch the rootfs.
     */
    suspend fun release(session: WaylandSessionInfo)
}
