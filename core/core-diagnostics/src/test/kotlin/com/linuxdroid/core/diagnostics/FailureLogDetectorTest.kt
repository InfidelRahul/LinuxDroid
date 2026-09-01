package com.linuxdroid.core.diagnostics

import com.linuxdroid.core.model.*
import org.junit.Assert.*
import org.junit.Test

class FailureLogDetectorTest {

    private val detector = FailureLogDetector()
    private val exporter = FailureReportExporter(maxSizeBytes = 100_000)

    @Test
    fun testDetectPtracePeekdataFailureAndCorrelateChain() {
        val rawLogs = listOf(
            "[EXECVE_ENTER] pid=27239, sysnum=59, raw_path=0x76b8fefa00, normalized=0x76b8fefa00",
            "ptrace(PEEKDATA, pid=27239, addr=0x76b8fefa00) failed: Invalid argument (errno 22)",
            "[EXECVE_PATH_FAIL] get_sysarg_path failed: Bad address",
            "[SYSCALL_ENTER_ERR] pid=27239: sysnum=59 (execve) status=-14",
            "[SIGSYS_TRAPPED] signo=31, si_code=1, si_syscall=59",
            "[SYSCALL_EXIT_ERR] pid=27239: sysnum=59 (execve) tracee_status=-14 -> result=-38",
            "fatal error: see `libproot.so --help`",
        )

        val events = detector.detectFailures(rawLogs)
        assertTrue("Expected failure events to be detected", events.isNotEmpty())

        val ptraceEvent = events.find { it.category == FailureCategory.PTRACE_PEEKDATA }
        assertNotNull("Expected PTRACE_PEEKDATA event", ptraceEvent)
        assertEquals(27239, ptraceEvent?.pid)
        assertEquals("0x76b8fefa00", ptraceEvent?.rawAddress)
        assertEquals(22, ptraceEvent?.errno)

        val sigsysEvent = events.find { it.category == FailureCategory.SIGSYS }
        assertNotNull("Expected SIGSYS event", sigsysEvent)
        assertEquals(31, sigsysEvent?.signal)
        assertEquals(59, sigsysEvent?.syscallNumber)

        val chains = detector.correlateChains(events)
        assertEquals("Expected single correlated causal chain", 1, chains.size)
        assertTrue("Chain should contain all related events", chains.first().size >= 5)
    }

    @Test
    fun testDeduplicationOfRepeatedFailures() {
        val repeatedLogs = mutableListOf<String>()
        // Repeat 384 openat ENOENT errors
        for (i in 1..384) {
            repeatedLogs.add("[SYSCALL_EXIT_ERR] pid=100$i: sysnum=56 (openat) tracee_status=-2 -> result=-2")
        }

        val events = detector.detectFailures(repeatedLogs)
        assertEquals(384, events.size)

        val aggregated = detector.aggregateFailures(events)
        assertEquals(1, aggregated.size)
        val agg = aggregated.first()
        assertEquals(384, agg.count)
        assertEquals("openat", agg.syscallName)
        assertEquals("ENOENT", agg.errnoName)
        assertTrue("Representative events should be capped at 3", agg.representativeEvents.size <= 3)
    }

    @Test
    fun testLogSanitizerRedactsSecrets() {
        val sensitiveLine = "Connecting to API with token: secret_api_token_123456789 and Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val sanitized = LogSanitizer.sanitize(sensitiveLine)

        assertFalse("Raw token should be sanitized", sanitized.contains("secret_api_token_123456789"))
        assertFalse("Raw Bearer JWT should be sanitized", sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue("Redacted token marker should be present", sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testReportExportTextAndJson() {
        val sampleEvents = listOf(
            FailureEvent(
                id = "f-1",
                correlationId = "c-1",
                category = FailureCategory.PTRACE_PEEKDATA,
                message = "ptrace(PEEKDATA, pid=123, addr=0x1000) failed: Invalid argument",
                source = "PRoot:mem",
                errno = 22,
                errnoName = "EINVAL",
                syscallName = "execve",
                syscallNumber = 59,
            )
        )
        val aggregated = detector.aggregateFailures(sampleEvents)
        val chains = detector.correlateChains(sampleEvents)

        val report = FailureReport(
            reportId = "test-rep-1",
            timestamp = "2026-08-31 23:00:00 UTC",
            environmentInfo = mapOf("Device" to "Pixel 9", "Kernel" to "6.6.0"),
            primaryCategory = FailureCategory.PTRACE_PEEKDATA,
            rootCauseSummary = "PTRACE_PEEKDATA memory fault on Android Bionic ABI",
            totalFailures = 1,
            uniqueSignaturesCount = 1,
            causalChains = chains,
            aggregatedFailures = aggregated,
            rawContextIncluded = true,
        )

        val textReport = exporter.buildPlainTextReport(report, includeRawContext = true)
        assertTrue(textReport.contains("LINUXDROID FAILURE DIAGNOSTIC REPORT"))
        assertTrue(textReport.contains("PTRACE_PEEKDATA"))
        assertTrue(textReport.contains("Pixel 9"))

        val jsonReport = exporter.buildJsonReport(report, includeRawContext = true)
        assertTrue(jsonReport.contains("\"reportId\": \"test-rep-1\""))
        assertTrue(jsonReport.contains("\"primaryCategory\": \"PTRACE_PEEKDATA\""))
        assertTrue(jsonReport.contains("\"aggregatedFailures\""))
    }

    @Test
    fun testRichSyscallExitErrorParsing() {
        val line = "[SYSCALL_EXIT_ERR] pid=1234: sysnum=216 (raw=56, openat) result=-2 (errno=2), dirfd=-100, guest_path='/etc/ld.so.preload', host_path='/data/data/com.linuxdroid/rootfs/etc/ld.so.preload', flags=0x80000, mode=0x0, socket=''"
        val events = detector.detectFailures(listOf(line))
        assertEquals(1, events.size)

        val ev = events.first()
        assertEquals(1234, ev.pid)
        assertEquals(216, ev.syscallNumber)
        assertEquals(56, ev.rawSyscallNumber)
        assertEquals("openat", ev.syscallName)
        assertEquals(2, ev.errno)
        assertEquals("ENOENT", ev.errnoName)
        assertEquals(FailureCategory.EXPECTED_PROBE, ev.category)
        assertTrue(ev.isExpectedProbe)
        assertEquals(-100L, ev.dirfd)
        assertEquals("/etc/ld.so.preload", ev.guestPath)
        assertEquals("/data/data/com.linuxdroid/rootfs/etc/ld.so.preload", ev.hostPath)
    }

    @Test
    fun testMergingEnterAndExitPairs() {
        val rawLogs = listOf(
            "[SYSCALL_ENTER_ERR] pid=7360: sysnum=216 (raw=56, openat) status=-2 -> PR_void, dirfd=-100, guest_path='/etc/ld.so.preload', host_path='/rootfs/etc/ld.so.preload', flags=0x80000, mode=0x0, socket=''",
            "[SYSCALL_EXIT_ERR] pid=7360: sysnum=216 (raw=56, openat) result=-2 (errno=2), dirfd=-100, guest_path='/etc/ld.so.preload', host_path='/rootfs/etc/ld.so.preload', flags=0x80000, mode=0x0, socket=''",
            "[SYSCALL_ENTER_ERR] pid=7360: sysnum=43 (raw=203, connect) status=-2 -> PR_void, dirfd=3, guest_path='unix:/var/run/nscd/socket', host_path='', flags=0x0, mode=0x0, socket='unix:/var/run/nscd/socket'",
            "[SYSCALL_EXIT_ERR] pid=7360: sysnum=43 (raw=203, connect) result=-2 (errno=2), dirfd=3, guest_path='unix:/var/run/nscd/socket', host_path='', flags=0x0, mode=0x0, socket='unix:/var/run/nscd/socket'"
        )

        val events = detector.detectFailures(rawLogs)
        // 4 raw log lines (2 enter + 2 exit) should be merged into exactly 2 logical events
        assertEquals(2, events.size)

        val openatEv = events[0]
        assertEquals(7360, openatEv.pid)
        assertEquals("openat", openatEv.syscallName)
        assertEquals(56, openatEv.rawSyscallNumber)
        assertEquals(2, openatEv.errno)
        assertEquals(FailureCategory.EXPECTED_PROBE, openatEv.category)
        assertTrue(openatEv.isExpectedProbe)
        assertEquals("/etc/ld.so.preload", openatEv.guestPath)

        val connectEv = events[1]
        assertEquals(7360, connectEv.pid)
        assertEquals("connect", connectEv.syscallName)
        assertEquals(203, connectEv.rawSyscallNumber)
        assertEquals(2, connectEv.errno)
        assertEquals(FailureCategory.EXPECTED_PROBE, connectEv.category)
        assertTrue(connectEv.isExpectedProbe)
        assertEquals("unix:/var/run/nscd/socket", connectEv.socketInfo)
    }

    @Test
    fun testCriticalMissingFileCategorization() {
        val line = "[SYSCALL_EXIT_ERR] pid=1234: sysnum=216 (raw=56, openat) result=-2 (errno=2), dirfd=-100, guest_path='/bin/sh', host_path='/rootfs/bin/sh', flags=0x0, mode=0x0, socket=''"
        val events = detector.detectFailures(listOf(line))
        assertEquals(1, events.size)

        val ev = events.first()
        assertEquals(FailureCategory.MISSING_ROOTFS_FILE, ev.category)
        assertFalse(ev.isExpectedProbe)
        assertTrue(ev.category.isCritical)
    }

    @Test
    fun testStandardFstatatAndOpenatProbeClassification() {
        val logs = listOf(
            "proot info: [SYSCALL_EXIT_ERR] pid=22493: sysnum=88 (fstatat64) tracee_status=-2 -> result=-2",
            "proot info: [SYSCALL_EXIT_ERR] pid=22504: sysnum=216 (openat) tracee_status=-2 -> result=-2"
        )
        val events = detector.detectFailures(logs)
        assertEquals(2, events.size)

        val fstatatEv = events[0]
        assertEquals(22493, fstatatEv.pid)
        assertEquals(88, fstatatEv.syscallNumber)
        assertEquals("fstatat64", fstatatEv.syscallName)
        assertEquals(2, fstatatEv.errno)
        assertEquals("ENOENT", fstatatEv.errnoName)
        assertEquals(FailureCategory.EXPECTED_PROBE, fstatatEv.category)
        assertTrue(fstatatEv.isExpectedProbe)

        val openatEv = events[1]
        assertEquals(22504, openatEv.pid)
        assertEquals(216, openatEv.syscallNumber)
        assertEquals("openat", openatEv.syscallName)
        assertEquals(2, openatEv.errno)
        assertEquals("ENOENT", openatEv.errnoName)
        assertEquals(FailureCategory.EXPECTED_PROBE, openatEv.category)
        assertTrue(openatEv.isExpectedProbe)
    }
}

