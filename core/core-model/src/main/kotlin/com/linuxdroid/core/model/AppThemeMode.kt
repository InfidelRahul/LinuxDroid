package com.linuxdroid.core.model

/**
 * User-selected theme mode for LinuxDroid UI.
 */
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    val displayName: String
        get() = when (this) {
            SYSTEM -> "System Default"
            LIGHT -> "Light Mode"
            DARK -> "Dark Mode"
        }
}
