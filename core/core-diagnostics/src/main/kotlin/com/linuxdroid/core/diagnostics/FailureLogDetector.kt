package com.linuxdroid.core.diagnostics

import com.linuxdroid.core.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Intelligent Failure Event Detector and Log Parser.
 *
 * Scans raw logs, identifies critical runtime/syscall failure events,
 * correlates cascading error chains, extracts bounded context windows,
 * and deduplicates high-frequency repetitive errors.
 */
class FailureLogDetector(
    private val defaultContextBefore: Int = 20,
    private val defaultContextAfter: Int = 50,
) {

    private val correlationCounter = AtomicInteger(1)

    // Common Errno mapping
    private val errnoNames = mapOf(
        1 to "EPERM",
        2 to "ENOENT",
        9 to "EBADF",
        12 to "ENOMEM",
        13 to "EACCES",
        14 to "EFAULT",
        22 to "EINVAL",
        24 to "EMFILE",
        38 to "ENOSYS",
    )

    // Syscall names mapping for ARM64 & x86_64
    private val commonSyscalls = mapOf(
        59 to "execve",
        221 to "execve", // ARM64 execve
        56 to "openat",
        216 to "openat",
        64 to "write",
        63 to "read",
        117 to "ptrace",
    )

    private val PTRACE_FAIL_PATTERN = Regex(
        "(?i)ptrace\\((PEEKDATA|POKEDATA|PEEKTEXT|POKETEXT), pid=(\\d+), addr=(0x[0-9a-fA-F]+)\\) failed: (.*)"
    )

    private val EXECVE_ENTER_PATTERN = Regex(
        "(?i)\\[EXECVE_ENTER\\] pid=(\\d+), sysnum=(\\d+), raw_path=(0x[0-9a-fA-F]+), normalized=(0x[0-9a-fA-F]+)"
    )

    private val EXECVE_PATH_FAIL_PATTERN = Regex(
        "(?i)\\[EXECVE_PATH_FAIL\\] (.*)"
    )

    private val EXECVE_KERNEL_FAIL_PATTERN = Regex(
        "(?i)\\[EXECVE_KERNEL_FAIL\\] pid=(\\d+), kernel execve failed with errno=(\\d+): (.*)"
    )

    private val SYSCALL_ENTER_ERR_PATTERN = Regex(
        "(?i)\\[SYSCALL_ENTER_ERR\\] pid=(\\d+): sysnum=(\\d+) \\(([^)]+)\\) status=(-?\\d+)"
    )

    private val SYSCALL_EXIT_ERR_PATTERN = Regex(
        "(?i)\\[SYSCALL_EXIT_ERR\\] pid=(\\d+): sysnum=(\\d+) \\(([^)]+)\\) tracee_status=(-?\\d+) -> result=(-?\\d+)"
    )

    private val SIGSYS_PATTERN = Regex(
        "(?i)\\[SIGSYS_TRAPPED\\] signo=(\\d+)[, ]+si_code=(\\d+)[, ]+si_syscall=(\\d+)"
    )

    private val SECCOMP_FAIL_PATTERN = Regex(
        "(?i)\\[SECCOMP_FILTER_FAIL\\] (.*)"
    )

    private val PROOT_FATAL_PATTERN = Regex(
        "(?i)(proot error|proot fatal|fatal error): (.*)"
    )

    private val DPKG_APT_ERR_PATTERN = Regex(
        "(?i)(dpkg: error:|E: Sub-process /usr/bin/dpkg returned an error code|apt-get: (.*))"
    )

    private val GENERIC_ERRNO_PATTERN = Regex(
        "(?i)errno[ =:]+(\\d+)(?:[ (]+([A-Z0-9_]+)[ )]+)?"
    )

    /**
     * Analyzes raw log text lines and builds a list of detected [FailureEvent]s.
     */
    fun detectFailures(
        logLines: List<String>,
        environment: Environment? = null,
        contextBefore: Int = defaultContextBefore,
        contextAfter: Int = defaultContextAfter,
    ): List<FailureEvent> {
        val events = mutableListOf<FailureEvent>()
        val totalLines = logLines.size
        var currentCorrelationId = "corr-${correlationCounter.getAndIncrement()}"
        var lastFailureLineIndex = -100

        for (idx in 0 until totalLines) {
            val rawLine = logLines[idx]
            val line = LogSanitizer.sanitize(rawLine)

            val detected = parseLineForFailure(line, environment) ?: continue

            // If failures are within 8 lines of each other, correlate them in the same causal chain
            if (idx - lastFailureLineIndex > 8) {
                currentCorrelationId = "corr-${correlationCounter.getAndIncrement()}"
            }
            lastFailureLineIndex = idx

            val startBefore = maxOf(0, idx - contextBefore)
            val beforeContext = logLines.subList(startBefore, idx).map { LogSanitizer.sanitize(it) }

            val endAfter = minOf(totalLines, idx + 1 + contextAfter)
            val afterContext = if (idx + 1 < totalLines) {
                logLines.subList(idx + 1, endAfter).map { LogSanitizer.sanitize(it) }
            } else emptyList()

            val completeEvent = detected.copy(
                id = UUID.randomUUID().toString().take(8),
                correlationId = currentCorrelationId,
                environmentId = environment?.id?.value,
                distribution = environment?.distribution?.name,
                architecture = environment?.architecture?.name ?: System.getProperty("os.arch"),
                contextBefore = beforeContext,
                contextAfter = afterContext,
            )
            events.add(completeEvent)
        }

        return events
    }

    private fun parseLineForFailure(line: String, env: Environment?): FailureEvent? {
        // 1. PTRACE PEEKDATA / POKEDATA failure
        PTRACE_FAIL_PATTERN.find(line)?.let { m ->
            val req = m.groupValues[1]
            val pid = m.groupValues[2].toIntOrNull()
            val addr = m.groupValues[3]
            val errStr = m.groupValues[4]
            val category = if (req.contains("PEEKDATA")) FailureCategory.PTRACE_PEEKDATA else FailureCategory.PTRACE_FAILURE
            val errnoVal = extractErrno(errStr) ?: if (errStr.contains("EINVAL", ignoreCase = true)) 22 else 14
            return FailureEvent(
                id = "",
                correlationId = "",
                category = category,
                message = "ptrace($req, pid=$pid, addr=$addr) failed: $errStr",
                source = "PRoot:mem",
                pid = pid,
                traceePid = pid,
                ptraceRequest = req,
                rawAddress = addr,
                actualAddressPassed = addr,
                errno = errnoVal,
                errnoName = errnoNames[errnoVal] ?: "EINVAL",
            )
        }

        // 2. EXECVE Path Failure
        EXECVE_PATH_FAIL_PATTERN.find(line)?.let { m ->
            val detail = m.groupValues[1]
            val errnoVal = extractErrno(detail) ?: 14
            return FailureEvent(
                id = "",
                correlationId = "",
                category = FailureCategory.EFAULT,
                message = "[EXECVE_PATH_FAIL] $detail",
                source = "PRoot:get_sysarg_path",
                errno = errnoVal,
                errnoName = errnoNames[errnoVal] ?: "EFAULT",
            )
        }

        // 4. EXECVE Kernel Failure
        EXECVE_KERNEL_FAIL_PATTERN.find(line)?.let { m ->
            val pid = m.groupValues[1].toIntOrNull()
            val errnoVal = m.groupValues[2].toIntOrNull() ?: 38
            val detail = m.groupValues[3]
            return FailureEvent(
                id = "",
                correlationId = "",
                category = FailureCategory.ENOSYS,
                message = "[EXECVE_KERNEL_FAIL] pid=$pid, kernel execve failed with errno=$errnoVal ($detail)",
                source = "PRoot:execve",
                pid = pid,
                errno = errnoVal,
                errnoName = errnoNames[errnoVal] ?: "ENOSYS",
            )
        }

        // 5. Syscall Enter Error
        SYSCALL_ENTER_ERR_PATTERN.find(line)?.let { m ->
            val pid = m.groupValues[1].toIntOrNull()
            val sysnum = m.groupValues[2].toIntOrNull()
            val sysname = m.groupValues[3]
            val status = m.groupValues[4].toIntOrNull() ?: -14
            val errnoVal = kotlin.math.abs(status)
            return FailureEvent(
                id = "",
                correlationId = "",
                category = FailureCategory.SYSCALL_FAILURE,
                message = "[SYSCALL_ENTER_ERR] pid=$pid: sysnum=$sysnum ($sysname) status=$status",
                source = "PRoot:syscall",
                pid = pid,
                syscallNumber = sysnum,
                syscallName = sysname,
                errno = errnoVal,
                errnoName = errnoNames[errnoVal],
            )
        }

        // 6. Syscall Exit Error
        if (line.contains("[SYSCALL_EXIT_ERR]", ignoreCase = true)) {
            val pid = Regex("pid=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            val sysnum = Regex("sysnum=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            val sysname = Regex("sysnum=\\d+ \\(([^)]+)\\)").find(line)?.groupValues?.get(1)
            val result = Regex("result=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -14
            val errnoVal = Regex("errno=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: kotlin.math.abs(result)
            val dirfd = Regex("dirfd=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            val guestPath = Regex("guest_path='([^']*)'").find(line)?.groupValues?.get(1)
            val hostPath = Regex("host_path='([^']*)'").find(line)?.groupValues?.get(1)

            val category = when (errnoVal) {
                2 -> FailureCategory.ENOENT
                13 -> FailureCategory.EACCES
                1 -> FailureCategory.EPERM
                14 -> FailureCategory.EFAULT
                22 -> FailureCategory.EINVAL
                38 -> FailureCategory.ENOSYS
                else -> FailureCategory.SYSCALL_FAILURE
            }

            return FailureEvent(
                id = "",
                correlationId = "",
                category = category,
                message = line.trim(),
                source = "PRoot:syscall",
                pid = pid,
                syscallNumber = sysnum,
                syscallName = sysname,
                errno = errnoVal,
                errnoName = errnoNames[errnoVal] ?: "ERRNO_$errnoVal",
                hostResult = result.toString(),
                dirfd = dirfd,
                guestPath = guestPath,
                hostPath = hostPath,
            )
        }

        // 7. SIGSYS Trapped
        SIGSYS_PATTERN.find(line)?.let { m ->
            val signo = m.groupValues[1].toIntOrNull() ?: 31
            val siCode = m.groupValues[2].toIntOrNull() ?: 1
            val siSyscall = m.groupValues[3].toIntOrNull() ?: 59
            return FailureEvent(
                id = "",
                correlationId = "",
                category = FailureCategory.SIGSYS,
                message = "[SIGSYS_TRAPPED] signo=$signo, si_code=$siCode, si_syscall=$siSyscall",
                source = "PRoot:seccomp",
                signal = signo,
                signalName = "SIGSYS",
                seccompSignal = signo,
                seccompCode = siCode,
                seccompSyscall = siSyscall,
                syscallNumber = siSyscall,
                syscallName = commonSyscalls[siSyscall] ?: "syscall_$siSyscall",
            )
        }

        // 8. Seccomp Failure
        SECCOMP_FAIL_PATTERN.find(line)?.let { m ->
            val detail = m.groupValues[1]
            return FailureEvent(
                id = "",
                correlationId = "",
                category = FailureCategory.SECCOMP_FAILURE,
                message = "[SECCOMP_FILTER_FAIL] $detail",
                source = "PRoot:seccomp",
            )
        }

        // 9. PRoot Fatal / Error
        PROOT_FATAL_PATTERN.find(line)?.let { m ->
            val detail = m.groupValues[2]
            val category = when {
                detail.contains("Function not implemented", ignoreCase = true) -> FailureCategory.ENOSYS
                detail.contains("Bad address", ignoreCase = true) -> FailureCategory.EFAULT
                detail.contains("Invalid argument", ignoreCase = true) -> FailureCategory.EINVAL
                detail.contains("Permission denied", ignoreCase = true) -> FailureCategory.EACCES
                detail.contains("No such file", ignoreCase = true) -> FailureCategory.ENOENT
                else -> FailureCategory.PROOT_STARTUP
            }
            return FailureEvent(
                id = "",
                correlationId = "",
                category = category,
                message = detail,
                source = "PRoot:engine",
            )
        }

        // 10. DPKG / APT Failure
        DPKG_APT_ERR_PATTERN.find(line)?.let { m ->
            val detail = m.groupValues[1]
            return FailureEvent(
                id = "",
                correlationId = "",
                category = FailureCategory.PACKAGE_MANAGER_FAILURE,
                message = detail,
                source = "Linux:dpkg",
            )
        }

        return null
    }

    private fun extractErrno(text: String): Int? {
        GENERIC_ERRNO_PATTERN.find(text)?.let { m ->
            return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    /**
     * Deduplicates and aggregates repeated failure events based on their signature.
     */
    fun aggregateFailures(events: List<FailureEvent>): List<AggregatedFailure> {
        val map = linkedMapOf<String, AggregatedFailure>()

        for (ev in events) {
            val sig = ev.signature
            val existing = map[sig]
            if (existing != null) {
                existing.count++
                existing.lastSeen = ev.timestamp
                if (existing.representativeEvents.size < 3) {
                    existing.representativeEvents.add(ev)
                }
            } else {
                map[sig] = AggregatedFailure(
                    signature = sig,
                    category = ev.category,
                    syscallName = ev.syscallName,
                    errnoName = ev.errnoName,
                    source = ev.source,
                    message = ev.message,
                    count = 1,
                    firstSeen = ev.timestamp,
                    lastSeen = ev.timestamp,
                    representativeEvents = mutableListOf(ev),
                )
            }
        }

        return map.values.toList()
    }

    /**
     * Groups failure events into correlated causal chains.
     */
    fun correlateChains(events: List<FailureEvent>): List<List<FailureEvent>> {
        return events.groupBy { it.correlationId }.values.toList()
    }
}

