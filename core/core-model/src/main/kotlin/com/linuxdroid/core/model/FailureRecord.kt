package com.linuxdroid.core.model

/**
 * High-level categories for runtime and system failures.
 */
enum class FailureCategory {
    PROOT_STARTUP,
    EXECVE_FAILURE,
    PTRACE_PEEKDATA,
    PTRACE_POKEDATA,
    PTRACE_FAILURE,
    SYSCALL_FAILURE,
    SECCOMP_FAILURE,
    SIGSYS,
    ENOSYS,
    EFAULT,
    EINVAL,
    ENOENT,
    EACCES,
    EPERM,
    PROCESS_CRASH,
    SIGNAL_TERMINATION,
    PTY_DISCONNECT,
    RUNTIME_FAILED,
    PACKAGE_MANAGER_FAILURE,
    UNKNOWN;

    val isCritical: Boolean
        get() = this in setOf(PROOT_STARTUP, EXECVE_FAILURE, PTRACE_PEEKDATA, PROCESS_CRASH, RUNTIME_FAILED, SIGSYS)
}

/**
 * Represents a single captured failure event with structured diagnostic attributes.
 */
data class FailureEvent(
    val id: String,
    val correlationId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: FailureCategory,
    val message: String,
    val source: String = "PRoot",
    val environmentId: String? = null,
    val distribution: String? = null,
    val architecture: String? = null,
    val androidVersion: String? = null,
    val kernelVersion: String? = null,
    val prootVersion: String? = null,
    val rootfsPath: String? = null,
    val command: List<String> = emptyList(),
    val pid: Int? = null,
    val syscallNumber: Int? = null,
    val syscallName: String? = null,
    val errno: Int? = null,
    val errnoName: String? = null,
    val signal: Int? = null,
    val signalName: String? = null,
    val guestArgs: List<String> = emptyList(),
    val translatedArgs: List<String> = emptyList(),
    val hostSyscall: String? = null,
    val hostResult: String? = null,
    val hostErrno: Int? = null,
    val prootResult: String? = null,
    val guestResult: String? = null,
    val ptraceRequest: String? = null,
    val traceePid: Int? = null,
    val rawAddress: String? = null,
    val normalizedAddress: String? = null,
    val actualAddressPassed: String? = null,
    val seccompSignal: Int? = null,
    val seccompCode: Int? = null,
    val seccompSyscall: Int? = null,
    val dirfd: Int? = null,
    val guestPath: String? = null,
    val hostPath: String? = null,
    val contextBefore: List<String> = emptyList(),
    val contextAfter: List<String> = emptyList(),
) {
    val signature: String
        get() {
            val normMsg = message.replace(Regex("pid=\\d+"), "pid=*")
                .replace(Regex("0x[0-9a-fA-F]+"), "0x*")
                .replace(Regex("\\b\\d{4,}\\b"), "*")
                .take(60)
            return "${category.name}:${syscallName ?: "none"}:${errnoName ?: errno ?: "none"}:$source:$normMsg"
        }
}

/**
 * Aggregation of repeated identical failure events.
 */
data class AggregatedFailure(
    val signature: String,
    val category: FailureCategory,
    val syscallName: String?,
    val errnoName: String?,
    val source: String,
    val message: String,
    var count: Int,
    var firstSeen: Long,
    var lastSeen: Long,
    val representativeEvents: MutableList<FailureEvent> = mutableListOf(),
)

/**
 * Complete failure diagnostic report containing summary, causal chains, and aggregated records.
 */
data class FailureReport(
    val reportId: String,
    val timestamp: String,
    val environmentInfo: Map<String, String>,
    val primaryCategory: FailureCategory,
    val rootCauseSummary: String,
    val totalFailures: Int,
    val uniqueSignaturesCount: Int,
    val causalChains: List<List<FailureEvent>>,
    val aggregatedFailures: List<AggregatedFailure>,
    val rawContextIncluded: Boolean,
)

/**
 * Types of diagnostic logs that can be exported from LinuxDroid.
 */
enum class LogExportType(val displayName: String, val description: String) {
    TERMINAL_FAILURE_LOG(
        displayName = "Terminal Session & Failure Log",
        description = "Terminal console scrollback, exit codes, PTY status, and correlated runtime crash context."
    ),
    FAILURE_REPORT_COMPACT(
        displayName = "Failure Report (Compact)",
        description = "Root cause analysis, syscall errors, and deduplicated failure chains (≤ 1 MB)."
    ),
    FAILURE_REPORT_DEVELOPER(
        displayName = "Failure Report + Raw Context",
        description = "Failure analysis with full bounded log windows before and after each error."
    ),
    SYSTEM_DIAGNOSTICS(
        displayName = "System & Subsystem Diagnostics",
        description = "Health audit of runtime, GPU, audio, network, and storage."
    ),
    FULL_LOGS(
        displayName = "Full Raw Runtime Logs",
        description = "Complete internal console.log, proot.log, and logcat buffer archive."
    )
}

