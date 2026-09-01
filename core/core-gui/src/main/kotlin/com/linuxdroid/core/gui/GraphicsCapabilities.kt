package com.linuxdroid.core.gui

/**
 * A single probed graphics capability of the host/guest boundary.
 *
 * Availability is only ever set from an actual probe result — never inferred
 * from "this is Linux, so X must exist".
 */
enum class GraphicsCapability {
    EGL,
    OPENGL_ES,
    VULKAN,
    ANDROID_SURFACE,
    HARDWARE_BUFFER,
    SHARED_MEMORY_BUFFER,
    SOFTWARE_RENDERING,
}

/** Outcome of probing one capability. */
enum class ProbeOutcome {
    /** Probe ran and the capability is usable. */
    AVAILABLE,

    /** Probe ran and the capability is not usable. */
    UNAVAILABLE,

    /** Probe could not be executed (missing dependency, error). Treated as unavailable. */
    NOT_PROBED,
}

/**
 * Result of probing one [GraphicsCapability].
 */
data class CapabilityProbeResult(
    val capability: GraphicsCapability,
    val outcome: ProbeOutcome,
    /** Free-form probe evidence, e.g. renderer string, error text, probed path. */
    val evidence: String = "",
    val hardwareAccelerated: Boolean = false,
    val details: Map<String, String> = emptyMap(),
) {
    val isAvailable: Boolean get() = outcome == ProbeOutcome.AVAILABLE
}

/**
 * The full, probed graphics capability picture used for backend selection.
 */
data class GraphicsCapabilities(
    val results: List<CapabilityProbeResult>,
    val probedAt: Long = System.currentTimeMillis(),
) {
    fun result(capability: GraphicsCapability): CapabilityProbeResult =
        results.firstOrNull { it.capability == capability }
            ?: CapabilityProbeResult(capability, ProbeOutcome.NOT_PROBED)

    fun isAvailable(capability: GraphicsCapability): Boolean = result(capability).isAvailable

    val hasHardwareAcceleration: Boolean
        get() = results.any { it.isAvailable && it.hardwareAccelerated }

    /** True if at least one path capable of presenting pixels exists. */
    val hasAnyPresentationPath: Boolean
        get() = isAvailable(GraphicsCapability.ANDROID_SURFACE) ||
            isAvailable(GraphicsCapability.SHARED_MEMORY_BUFFER) ||
            isAvailable(GraphicsCapability.SOFTWARE_RENDERING)

    fun summary(): String = results.joinToString(", ") {
        "${it.capability.name}=${it.outcome.name}${if (it.hardwareAccelerated) "(hw)" else ""}"
    }

    companion object {
        /** Explicitly-unprobed capabilities. Used as the pre-initialization value. */
        val UNPROBED = GraphicsCapabilities(
            results = GraphicsCapability.entries.map {
                CapabilityProbeResult(it, ProbeOutcome.NOT_PROBED)
            },
        )
    }
}

/**
 * Probes the actual graphics capabilities available to LinuxDroid.
 *
 * Implementations must perform real detection and must report
 * [ProbeOutcome.NOT_PROBED] rather than guessing when detection is impossible.
 * Implemented in a later phase; Phase 1 only defines the contract.
 */
interface GraphicsCapabilityProbe {
    suspend fun probe(): GraphicsCapabilities
}
