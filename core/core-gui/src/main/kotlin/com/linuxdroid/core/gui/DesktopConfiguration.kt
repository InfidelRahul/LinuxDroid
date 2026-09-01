package com.linuxdroid.core.gui

import com.linuxdroid.core.model.DesktopConfig

/**
 * Desktop configuration boundary.
 *
 * Persisted user configuration lives in [DesktopConfig] (`:core:core-model`,
 * stored per environment by `:core:core-database`). This file is the single
 * place that translates it into the GUI runtime's own settings, so that no
 * other component hard-codes compositor names, socket names or shell defaults.
 *
 * Configuration is intentionally separate from runtime state: runtime state is
 * [GuiRuntimeStatus] and is never written back into configuration.
 */

/** Which shell (if any) the session should start above the compositor. */
enum class ShellMode {
    /** Compositor only — used by the first graphical milestone. */
    NONE,

    /** The built-in LinuxDroid desktop shell. */
    LINUXDROID_SHELL,
    ;

    companion object {
        const val LINUXDROID_SHELL_ID = "linuxdroid-shell"

        fun fromConfigValue(value: String): ShellMode = when (value.trim().lowercase()) {
            "", "none" -> NONE
            LINUXDROID_SHELL_ID -> LINUXDROID_SHELL
            else -> NONE
        }
    }
}

/** Dock behaviour settings. Deliberately minimal. */
data class DockSettings(
    val enabled: Boolean = true,
    /** Desktop-entry ids pinned to the dock, in display order. */
    val pinnedAppIds: List<String> = emptyList(),
)

/** Launcher behaviour settings. */
data class LauncherSettings(
    val enabled: Boolean = true,
    /** Hide entries marked `NoDisplay=true`. */
    val hideNoDisplayEntries: Boolean = true,
)

/** Window behaviour settings for the floating-window model. */
data class WindowSettings(
    /** Focus the most recently mapped window. */
    val focusNewWindows: Boolean = true,
    /** Allow user move/resize of floating windows. */
    val allowUserResize: Boolean = true,
)

/** Appearance settings for the shell surfaces. */
data class AppearanceSettings(
    val showStatusBar: Boolean = true,
)

/**
 * The resolved desktop settings the GUI runtime and shell consume.
 *
 * Built only via [from]; components must not read [DesktopConfig] directly.
 */
data class DesktopSettings(
    val shellMode: ShellMode = ShellMode.NONE,
    val compositorId: CompositorId = CompositorId.WESTON,
    val xwaylandEnabled: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val dock: DockSettings = DockSettings(),
    val launcher: LauncherSettings = LauncherSettings(),
    val window: WindowSettings = WindowSettings(),
) {
    companion object {
        fun from(config: DesktopConfig): DesktopSettings {
            val compositorName = config.waylandCompositor.trim().ifBlank {
                CompositorId.WESTON.value
            }
            return DesktopSettings(
                shellMode = ShellMode.fromConfigValue(config.desktopEnvironment),
                compositorId = CompositorId(compositorName),
                xwaylandEnabled = config.xwaylandEnabled,
            )
        }
    }
}

/**
 * Supplies the desktop settings for the active environment.
 *
 * A one-method interface so tests and future settings UIs can override it
 * without a configuration framework.
 */
fun interface DesktopSettingsProvider {
    fun settings(): DesktopSettings
}
