package com.linuxdroid.core.input

/**
 * InputManager routes Android touch/keyboard/mouse input to the Linux Wayland session.
 *
 * Architecture: Android input → Native bridge → Wayland/Linux input → Linux apps
 *
 * Implementation: Phase 14 of the development roadmap.
 */
interface InputManager {
    suspend fun start()
    suspend fun stop()
    fun isActive(): Boolean
}
