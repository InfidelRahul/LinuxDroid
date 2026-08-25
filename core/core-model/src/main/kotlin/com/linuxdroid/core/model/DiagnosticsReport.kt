package com.linuxdroid.core.model

/**
 * Status of a single diagnostic check.
 */
enum class DiagnosticStatus {
    OK,
    WARNING,
    ERROR,
    UNKNOWN,
    NOT_APPLICABLE
}

/**
 * Result of a single subsystem diagnostic check.
 */
data class DiagnosticCheck(
    val name: String,
    val status: DiagnosticStatus,
    val detail: String = "",
    val recommendation: String? = null,
)

/**
 * Full diagnostics report for an environment or session.
 */
data class DiagnosticsReport(
    val environmentId: EnvironmentId?,
    val sessionId: SessionId?,
    val generatedAt: Long = System.currentTimeMillis(),
    val runtime: DiagnosticCheck,
    val filesystem: DiagnosticCheck,
    val linuxUserspace: DiagnosticCheck,
    val wayland: DiagnosticCheck,
    val xwayland: DiagnosticCheck,
    val gpu: DiagnosticCheck,
    val audio: DiagnosticCheck,
    val network: DiagnosticCheck,
    val sharedStorage: DiagnosticCheck,
    val resources: DiagnosticCheck,
) {
    /** True if all checks are OK. */
    val isHealthy: Boolean get() = listOf(
        runtime, filesystem, linuxUserspace, wayland, gpu, audio, network
    ).all { it.status == DiagnosticStatus.OK }

    /** Returns all checks with ERROR or WARNING status. */
    val issues: List<DiagnosticCheck> get() = listOf(
        runtime, filesystem, linuxUserspace, wayland, xwayland,
        gpu, audio, network, sharedStorage, resources
    ).filter { it.status in setOf(DiagnosticStatus.ERROR, DiagnosticStatus.WARNING) }
}
