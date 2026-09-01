package com.linuxdroid.core.diagnostics

import com.linuxdroid.core.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Intelligent Failure Event Detector and Log Parser.
 *
 * Scans raw logs, identifies critical runtime/syscall failure events,
 * correlates cascading error chains, extracts bounded context windows,
 * decodes full syscall arguments (paths, flags, modes, sockets),
 * deduplicates logical syscall events, and classifies benign probes.
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
        111 to "ECONNREFUSED",
    )

    // Comprehensive Syscall names mapping for AArch64 / ARM64, x86_64, and PRoot canonical enums
    private val commonSyscalls = mapOf(
        // AArch64 / ARM64 standard Linux syscall numbers:
        56 to "openat",
        48 to "faccessat",
        439 to "faccessat2",
        203 to "connect",
        200 to "bind",
        221 to "execve",
        281 to "execveat",
        79 to "newfstatat",
        80 to "fstat",
        43 to "statfs",
        63 to "read",
        64 to "write",
        117 to "ptrace",

        // x86_64 standard Linux syscall numbers:
        257 to "openat",
        21 to "access",
        42 to "connect",
        49 to "bind",
        59 to "execve",
        4 to "stat",
        5 to "fstat",
        6 to "lstat",
        137 to "statfs",
        0 to "read",
        1 to "write",
        101 to "ptrace",

        // PRoot canonical enum mappings:
        216 to "openat",
    )

    private val PTRACE_FAIL_PATTERN = Regex(
        "(?i)ptrace\\((PEEKDATA|POKEDATA|PEEKTEXT|POKETEXT), pid=(\\d+), addr=(0x[0-9a-fA-F]+)\\) failed: (.*)"
    )

    private val EXECVE_PATH_FAIL_PATTERN = Regex(
        "(?i)\\[EXECVE_PATH_FAIL\\] (.*)"
    )

    private val EXECVE_KERNEL_FAIL_PATTERN = Regex(
        "(?i)\\[EXECVE_KERNEL_FAIL\\] pid=(\\d+), kernel execve failed with errno=(\\d+): (.*)"
    )

    private val SYSCALL_ENTER_ERR_PATTERN = Regex(
        "(?i)\\[SYSCALL_ENTER_ERR\\] pid=(\\d+): sysnum=(\\d+)(?: \\(raw=(\\d+), ([^)]+)\\)| \\(([^)]+)\\)) status=(-?\\d+)"
    )

    private val SYSCALL_EXIT_ERR_PATTERN = Regex(
        "(?i)\\[SYSCALL_EXIT_ERR\\] pid=(\\d+): sysnum=(\\d+)(?: \\(raw=(\\d+), ([^)]+)\\)| \\(([^)]+)\\))"
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
     * Merges correlated enter/exit syscall pairs into single logical failure events.
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

        var idx = 0
        while (idx < totalLines) {
            val rawLine = logLines[idx]
            val line = LogSanitizer.sanitize(rawLine)

            // Check if this line is a SYSCALL_ENTER_ERR that has a matching SYSCALL_EXIT_ERR nearby
            val isEnterErr = line.contains("[SYSCALL_ENTER_ERR]", ignoreCase = true)
            val isExitErr = line.contains("[SYSCALL_EXIT_ERR]", ignoreCase = true)

            var detected: FailureEvent? = null
            var linesConsumed = 1

            if (isEnterErr && idx + 1 < totalLines && logLines[idx + 1].contains("[SYSCALL_EXIT_ERR]", ignoreCase = true)) {
                // Merge ENTER + EXIT pair into a single logical failure event
                val exitLine = LogSanitizer.sanitize(logLines[idx + 1])
                detected = parseMergedSyscallEvent(line, exitLine, environment)
                linesConsumed = 2
            } else if (isExitErr && idx > 0 && logLines[idx - 1].contains("[SYSCALL_ENTER_ERR]", ignoreCase = true)) {
                // Already merged in previous iteration; skip duplicate exit line
                idx++
                continue
            } else {
                detected = parseLineForFailure(line, environment)
            }

            if (detected == null) {
                idx += linesConsumed
                continue
            }

            // If failures are within 8 lines of each other, correlate them in the same causal chain
            if (idx - lastFailureLineIndex > 8) {
                currentCorrelationId = "corr-${correlationCounter.getAndIncrement()}"
            }
            lastFailureLineIndex = idx

            val startBefore = maxOf(0, idx - contextBefore)
            val beforeContext = logLines.subList(startBefore, idx).map { LogSanitizer.sanitize(it) }

            val endAfter = minOf(totalLines, idx + linesConsumed + contextAfter)
            val afterContext = if (idx + linesConsumed < totalLines) {
                logLines.subList(idx + linesConsumed, endAfter).map { LogSanitizer.sanitize(it) }
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

            idx += linesConsumed
        }

        return events
    }

    private fun parseMergedSyscallEvent(enterLine: String, exitLine: String, env: Environment?): FailureEvent? {
        val pid = extractPid(exitLine) ?: extractPid(enterLine)
        val (sysnum, rawSysnum, sysname) = extractSysnumInfo(exitLine) ?: extractSysnumInfo(enterLine) ?: Triple(null, null, null)
        val result = extractResult(exitLine) ?: extractStatus(enterLine) ?: -2
        val errnoVal = extractErrno(exitLine) ?: kotlin.math.abs(result)
        val dirfd = extractDirfd(exitLine) ?: extractDirfd(enterLine)
        val guestPath = extractGuestPath(exitLine) ?: extractGuestPath(enterLine)
        val hostPath = extractHostPath(exitLine) ?: extractHostPath(enterLine)
        val flags = extractFlags(exitLine) ?: extractFlags(enterLine)
        val mode = extractMode(exitLine) ?: extractMode(enterLine)
        val socketInfo = extractSocket(exitLine) ?: extractSocket(enterLine)

        val resolvedSysname = sysname ?: (rawSysnum?.let { commonSyscalls[it] }) ?: (sysnum?.let { commonSyscalls[it] })
        val (category, probeExplanation) = classifyProbe(resolvedSysname, errnoVal, guestPath, hostPath, socketInfo)

        val descPath = guestPath ?: socketInfo ?: ""
        val summaryMsg = if (descPath.isNotBlank()) {
            "[SYSCALL] pid=$pid: ${resolvedSysname ?: "syscall"} ($descPath) -> errno=${errnoNames[errnoVal] ?: errnoVal}"
        } else {
            exitLine.trim()
        }

        return FailureEvent(
            id = "",
            correlationId = "",
            category = category,
            message = summaryMsg,
            source = "PRoot:syscall",
            pid = pid,
            syscallNumber = sysnum,
            rawSyscallNumber = rawSysnum,
            syscallName = resolvedSysname,
            errno = errnoVal,
            errnoName = errnoNames[errnoVal] ?: "ERRNO_$errnoVal",
            hostResult = result.toString(),
            dirfd = dirfd,
            guestPath = guestPath,
            hostPath = hostPath,
            flags = flags,
            mode = mode,
            socketInfo = socketInfo,
            isExpectedProbe = (category == FailureCategory.EXPECTED_PROBE),
            probeExplanation = probeExplanation,
        )
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

        // 3. EXECVE Kernel Failure
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

        // 4. Standalone Syscall Enter Error
        if (line.contains("[SYSCALL_ENTER_ERR]", ignoreCase = true)) {
            val pid = extractPid(line)
            val (sysnum, rawSysnum, sysname) = extractSysnumInfo(line) ?: Triple(null, null, null)
            val status = extractStatus(line) ?: -2
            val errnoVal = kotlin.math.abs(status)
            val dirfd = extractDirfd(line)
            val guestPath = extractGuestPath(line)
            val hostPath = extractHostPath(line)
            val flags = extractFlags(line)
            val mode = extractMode(line)
            val socketInfo = extractSocket(line)

            val resolvedSysname = sysname ?: (rawSysnum?.let { commonSyscalls[it] }) ?: (sysnum?.let { commonSyscalls[it] })
            val (category, probeExplanation) = classifyProbe(resolvedSysname, errnoVal, guestPath, hostPath, socketInfo)

            return FailureEvent(
                id = "",
                correlationId = "",
                category = category,
                message = line.trim(),
                source = "PRoot:syscall",
                pid = pid,
                syscallNumber = sysnum,
                rawSyscallNumber = rawSysnum,
                syscallName = resolvedSysname,
                errno = errnoVal,
                errnoName = errnoNames[errnoVal] ?: "ERRNO_$errnoVal",
                hostResult = status.toString(),
                dirfd = dirfd,
                guestPath = guestPath,
                hostPath = hostPath,
                flags = flags,
                mode = mode,
                socketInfo = socketInfo,
                isExpectedProbe = (category == FailureCategory.EXPECTED_PROBE),
                probeExplanation = probeExplanation,
            )
        }

        // 5. Standalone Syscall Exit Error
        if (line.contains("[SYSCALL_EXIT_ERR]", ignoreCase = true)) {
            val pid = extractPid(line)
            val (sysnum, rawSysnum, sysname) = extractSysnumInfo(line) ?: Triple(null, null, null)
            val result = extractResult(line) ?: -14
            val errnoVal = extractErrno(line) ?: kotlin.math.abs(result)
            val dirfd = extractDirfd(line)
            val guestPath = extractGuestPath(line)
            val hostPath = extractHostPath(line)
            val flags = extractFlags(line)
            val mode = extractMode(line)
            val socketInfo = extractSocket(line)

            val resolvedSysname = sysname ?: (rawSysnum?.let { commonSyscalls[it] }) ?: (sysnum?.let { commonSyscalls[it] })
            val (category, probeExplanation) = classifyProbe(resolvedSysname, errnoVal, guestPath, hostPath, socketInfo)

            return FailureEvent(
                id = "",
                correlationId = "",
                category = category,
                message = line.trim(),
                source = "PRoot:syscall",
                pid = pid,
                syscallNumber = sysnum,
                rawSyscallNumber = rawSysnum,
                syscallName = resolvedSysname,
                errno = errnoVal,
                errnoName = errnoNames[errnoVal] ?: "ERRNO_$errnoVal",
                hostResult = result.toString(),
                dirfd = dirfd,
                guestPath = guestPath,
                hostPath = hostPath,
                flags = flags,
                mode = mode,
                socketInfo = socketInfo,
                isExpectedProbe = (category == FailureCategory.EXPECTED_PROBE),
                probeExplanation = probeExplanation,
            )
        }

        // 6. SIGSYS Trapped
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

        // 7. Seccomp Failure
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

        // 8. PRoot Fatal / Error
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

        // 9. DPKG / APT Failure
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

    /**
     * Accurately classifies whether an ENOENT or socket error is a benign, expected
     * probe during standard Linux execution (e.g. ld.so, bash, nscd fallback) or a genuine fault.
     */
    private fun classifyProbe(
        sysname: String?,
        errnoVal: Int,
        guestPath: String?,
        hostPath: String?,
        socketInfo: String?
    ): Pair<FailureCategory, String?> {
        if (errnoVal != 2) {
            val cat = when (errnoVal) {
                13 -> FailureCategory.EACCES
                1 -> FailureCategory.EPERM
                14 -> FailureCategory.EFAULT
                22 -> FailureCategory.EINVAL
                38 -> FailureCategory.ENOSYS
                else -> FailureCategory.SYSCALL_FAILURE
            }
            return Pair(cat, null)
        }

        val target = (guestPath ?: socketInfo ?: hostPath ?: "").trim()

        val isBenignProbe = when {
            target.contains("ld.so.preload") -> true
            target.contains("ld.so.cache") -> true
            target.contains("ld.so.nohwcap") -> true
            target.contains("/tls/") || target.contains("glibc-hwcaps") -> true
            target.contains("nscd/socket") || target.contains("nscd") -> true
            target.contains(".bashrc") || target.contains("bash.bashrc") || target.contains(".profile") || target.contains(".inputrc") -> true
            target.contains("locale-archive") || target.contains("/usr/share/locale") -> true
            target.contains("nsswitch.conf") -> true
            else -> false
        }

        if (isBenignProbe) {
            val explanation = when {
                target.contains("ld.so.preload") -> "Dynamic loader optional preload probe"
                target.contains("ld.so.cache") -> "Dynamic loader cache probe (falling back to search paths)"
                target.contains("nscd") -> "Glibc NSS daemon socket probe (falling back gracefully to /etc/passwd)"
                target.contains(".bashrc") || target.contains("bash.bashrc") || target.contains(".profile") -> "Shell startup optional config probe"
                target.contains("locale") -> "Glibc locale probe (defaulting to C.UTF-8)"
                else -> "Standard Linux probe; fallback handled gracefully"
            }
            return Pair(FailureCategory.EXPECTED_PROBE, explanation)
        }

        if (target.contains("/bin/sh") || target.contains("ld-linux") || target.contains("/etc/passwd")) {
            return Pair(FailureCategory.MISSING_ROOTFS_FILE, "Missing essential rootfs binary or configuration")
        }

        return Pair(FailureCategory.ENOENT, null)
    }

    private fun extractPid(text: String): Int? =
        Regex("pid=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractSysnumInfo(text: String): Triple<Int?, Int?, String?>? {
        // Match: sysnum=216 (raw=56, openat) or sysnum=216 (openat)
        val m = Regex("sysnum=(\\d+)(?: \\(raw=(\\d+), ([^)]+)\\)| \\(([^)]+)\\))").find(text)
        if (m != null) {
            val sysnum = m.groupValues[1].toIntOrNull()
            val raw = m.groupValues[2].ifBlank { null }?.toIntOrNull()
            val name = m.groupValues[3].ifBlank { m.groupValues[4] }.ifBlank { null }
            return Triple(sysnum, raw, name)
        }
        return null
    }

    private fun extractStatus(text: String): Int? =
        Regex("status=(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractResult(text: String): Int? =
        Regex("result=(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractDirfd(text: String): Long? =
        Regex("dirfd=(-?\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()

    private fun extractGuestPath(text: String): String? =
        Regex("guest_path='([^']*)'").find(text)?.groupValues?.get(1)?.ifBlank { null }

    private fun extractHostPath(text: String): String? =
        Regex("host_path='([^']*)'").find(text)?.groupValues?.get(1)?.ifBlank { null }

    private fun extractFlags(text: String): Long? {
        val s = Regex("flags=(0x[0-9a-fA-F]+|\\d+)").find(text)?.groupValues?.get(1) ?: return null
        return if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLongOrNull(16) else s.toLongOrNull()
    }

    private fun extractMode(text: String): Long? {
        val s = Regex("mode=(0x[0-9a-fA-F]+|\\d+)").find(text)?.groupValues?.get(1) ?: return null
        return if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLongOrNull(16) else s.toLongOrNull()
    }

    private fun extractSocket(text: String): String? =
        Regex("socket='([^']*)'").find(text)?.groupValues?.get(1)?.ifBlank { null }

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

