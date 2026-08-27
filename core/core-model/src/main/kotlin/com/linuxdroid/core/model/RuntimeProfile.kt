package com.linuxdroid.core.model

import kotlinx.serialization.Serializable

/**
 * High-level profile types defining which host subsystems are required for a runtime session.
 */
@Serializable
enum class RuntimeProfileType {
    /** Minimal userspace/terminal runtime (no desktop/audio/GPU overhead). */
    MINIMAL,
    /** Standard command-line Linux runtime with networking and storage. */
    STANDARD,
    /** Full graphical desktop session with Wayland, GPU, audio, and input. */
    DESKTOP,
    /** Dedicated application runtime container. */
    APPLICATION,
}

/**
 * Declarative capability profile controlling subsystem activation.
 */
@Serializable
data class RuntimeProfile(
    val type: RuntimeProfileType,
    val requiresDisplay: Boolean = false,
    val requiresGpu: Boolean = false,
    val requiresAudio: Boolean = false,
    val requiresInput: Boolean = false,
    val requiresNetwork: Boolean = false,
    val requiresSharedStorage: Boolean = false,
) {
    companion object {
        fun minimal(): RuntimeProfile = RuntimeProfile(
            type = RuntimeProfileType.MINIMAL,
            requiresDisplay = false,
            requiresGpu = false,
            requiresAudio = false,
            requiresInput = false,
            requiresNetwork = false,
            requiresSharedStorage = false,
        )

        fun standard(): RuntimeProfile = RuntimeProfile(
            type = RuntimeProfileType.STANDARD,
            requiresDisplay = false,
            requiresGpu = false,
            requiresAudio = false,
            requiresInput = false,
            requiresNetwork = true,
            requiresSharedStorage = true,
        )

        fun desktop(): RuntimeProfile = RuntimeProfile(
            type = RuntimeProfileType.DESKTOP,
            requiresDisplay = true,
            requiresGpu = true,
            requiresAudio = true,
            requiresInput = true,
            requiresNetwork = true,
            requiresSharedStorage = true,
        )

        fun application(): RuntimeProfile = RuntimeProfile(
            type = RuntimeProfileType.APPLICATION,
            requiresDisplay = true,
            requiresGpu = true,
            requiresAudio = true,
            requiresInput = true,
            requiresNetwork = true,
            requiresSharedStorage = true,
        )
    }
}
