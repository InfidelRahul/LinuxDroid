package com.linuxdroid.core.display

import com.linuxdroid.core.model.DisplayConfig

/**
 * DisplayManager manages the Wayland display surface lifecycle.
 *
 * Architecture:
 * ```
 * Linux app → Wayland compositor → LinuxDroid bridge → Android Surface → GPU → Display
 * ```
 *
 * Implementation: Phase 11-12 of the development roadmap.
 */
interface DisplayManager {
    /** Apply display configuration. */
    suspend fun applyConfig(config: DisplayConfig)

    /** Called when Android orientation/size changes. */
    suspend fun onConfigurationChanged(widthPx: Int, heightPx: Int, dpi: Int)

    /** Returns the current active display configuration. */
    fun getCurrentConfig(): DisplayConfig
}
