package com.linuxdroid.core.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Collects, analyzes, formats, and exports comprehensive runtime diagnostics,
 * compact structured failure reports, and full raw log archives.
 */
class RuntimeLogExporter(
    private val storage: EnvironmentStorage,
    private val diagnosticsManager: DiagnosticsManager,
    private val detector: FailureLogDetector = FailureLogDetector(),
    private val reportExporter: FailureReportExporter = FailureReportExporter(),
) {
    private val log = LinuxDroidLogger(LogSubsystem.DIAGNOSTICS)

    /**
     * Builds a structured [FailureReport] analyzing recent errors from proot.log and console.log.
     */
    suspend fun analyzeFailures(
        environment: Environment,
        contextBefore: Int = 20,
        contextAfter: Int = 50,
    ): FailureReport = withContext(Dispatchers.IO) {
        val envId = environment.id
        val prootLog = storage.prootLogFile(envId)
        val consoleLog = storage.consoleLogFile(envId)

        val logLines = mutableListOf<String>()
        if (prootLog.exists()) {
            logLines.addAll(prootLog.readLines().takeLast(3000))
        }
        if (consoleLog.exists()) {
            logLines.addAll(consoleLog.readLines().takeLast(1500))
        }

        val detectedEvents = detector.detectFailures(logLines, environment, contextBefore, contextAfter)
        val aggregated = detector.aggregateFailures(detectedEvents)
        val chains = detector.correlateChains(detectedEvents)

        val primaryCategory = detectedEvents.firstOrNull { it.category.isCritical }?.category
            ?: detectedEvents.firstOrNull()?.category
            ?: FailureCategory.UNKNOWN

        val rootCause = when {
            detectedEvents.any { it.category == FailureCategory.PTRACE_PEEKDATA } ->
                "PTRACE_PEEKDATA memory read failure: Android Bionic ptrace ABI mismatch / memory fault."
            detectedEvents.any { it.category == FailureCategory.SIGSYS } ->
                "SIGSYS trapped: Seccomp filter denied a guest syscall (unsupported or blocked syscall)."
            detectedEvents.any { it.category == FailureCategory.ENOSYS } ->
                "ENOSYS returned: Executable interpreter / dynamic linker missing or unimplemented kernel syscall."
            detectedEvents.any { it.category == FailureCategory.EFAULT } ->
                "EFAULT memory fault: PRoot failed to access tracee memory address."
            detectedEvents.any { it.category == FailureCategory.PROOT_STARTUP } ->
                "PRoot native engine startup failed: binary missing, not executable, or platform denied execution."
            detectedEvents.isEmpty() ->
                "No active runtime failures detected in recent log streams."
            else ->
                detectedEvents.first().message
        }

        val envInfo = linkedMapOf(
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (SDK API ${Build.VERSION.SDK_INT})",
            "Kernel" to (System.getProperty("os.version") ?: "unknown"),
            "Host Architecture" to (System.getProperty("os.arch") ?: "unknown"),
            "Environment ID" to envId.value,
            "Distribution" to environment.distribution.name,
            "Architecture" to environment.architecture.name,
            "State" to environment.state.name,
            "Rootfs Path" to storage.rootfsDir(envId).absolutePath,
        )

        FailureReport(
            reportId = "fail-${System.currentTimeMillis()}",
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()),
            environmentInfo = envInfo,
            primaryCategory = primaryCategory,
            rootCauseSummary = rootCause,
            totalFailures = detectedEvents.size,
            uniqueSignaturesCount = aggregated.size,
            causalChains = chains,
            aggregatedFailures = aggregated,
            rawContextIncluded = false,
        )
    }

    /**
     * Generates a compact or developer-level failure report string (plain-text or JSON).
     */
    suspend fun generateFailureReportString(
        environment: Environment,
        includeRawContext: Boolean = false,
        asJson: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val report = analyzeFailures(environment)
        if (asJson) {
            reportExporter.buildJsonReport(report, includeRawContext)
        } else {
            reportExporter.buildPlainTextReport(report, includeRawContext)
        }
    }

    /**
     * Builds a detailed human-readable Markdown/plain-text subsystem diagnostic report.
     */
    suspend fun generateDetailedLogReport(
        environment: Environment,
        context: Context? = null,
    ): String = withContext(Dispatchers.IO) {
        val envId = environment.id
        val reportBuilder = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())

        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine("LINUXDROID RUNTIME DIAGNOSTIC & LOG REPORT")
        reportBuilder.appendLine("Generated: $timestamp")
        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine()

        // 1. HOST DEVICE INFORMATION
        reportBuilder.appendLine("--- [1. HOST ENVIRONMENT] ---")
        reportBuilder.appendLine("Manufacturer / Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        reportBuilder.appendLine("Android Version: ${Build.VERSION.RELEASE} (SDK API ${Build.VERSION.SDK_INT})")
        reportBuilder.appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        reportBuilder.appendLine("Linux Kernel: ${System.getProperty("os.version") ?: "unknown"}")
        reportBuilder.appendLine("Architecture: ${System.getProperty("os.arch") ?: "unknown"}")
        reportBuilder.appendLine()

        // 2. GUEST ENVIRONMENT SPECIFICATION
        reportBuilder.appendLine("--- [2. GUEST ENVIRONMENT] ---")
        reportBuilder.appendLine("Environment ID: ${environment.id.value}")
        reportBuilder.appendLine("Name: ${environment.name}")
        reportBuilder.appendLine("Distribution: ${environment.distribution}")
        reportBuilder.appendLine("Architecture: ${environment.architecture}")
        reportBuilder.appendLine("State: ${environment.state}")
        reportBuilder.appendLine("Rootfs Directory: ${storage.rootfsDir(envId).absolutePath}")
        reportBuilder.appendLine("Rootfs Exists: ${storage.rootfsDir(envId).exists()}")
        if (storage.rootfsDir(envId).exists()) {
            val rootfsBytes = storage.rootfsSize(envId)
            reportBuilder.appendLine("Rootfs Size: ${rootfsBytes / 1_048_576} MB ($rootfsBytes bytes)")
        }
        reportBuilder.appendLine()

        // 3. GUEST BINARIES & DYNAMIC LINKERS INTEGRITY
        reportBuilder.appendLine("--- [3. GUEST BINARY INTEGRITY AUDIT] ---")
        val criticalPaths = listOf(
            "bin/sh",
            "usr/bin/sh",
            "bin/bash",
            "usr/bin/bash",
            "bin/true",
            "usr/bin/true",
            "etc/os-release",
            "etc/resolv.conf",
            "lib/ld-linux-aarch64.so.1",
            "lib64/ld-linux-aarch64.so.1",
            "usr/lib/ld-linux-aarch64.so.1",
            "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
            "lib64/ld-linux-x86-64.so.2",
        )
        for (rel in criticalPaths) {
            val f = File(storage.rootfsDir(envId), rel)
            val exists = f.exists()
            val len = if (exists) f.length() else 0
            val exec = if (exists) f.canExecute() else false
            reportBuilder.appendLine(" - /$rel: exists=$exists, size=$len bytes, executable=$exec")
        }
        reportBuilder.appendLine()

        // 4. SUBSYSTEM DIAGNOSTICS SUMMARY
        reportBuilder.appendLine("--- [4. SUBSYSTEM DIAGNOSTICS REPORT] ---")
        try {
            val diagReport = diagnosticsManager.generateReport(environment)
            listOf(
                diagReport.runtime,
                diagReport.filesystem,
                diagReport.linuxUserspace,
                diagReport.gpu,
                diagReport.audio,
                diagReport.network,
                diagReport.sharedStorage,
                diagReport.resources,
            ).forEach { check ->
                reportBuilder.appendLine("[${check.status}] ${check.name}: ${check.detail}")
                check.recommendation?.let { reportBuilder.appendLine("  Recommendation: $it") }
            }
        } catch (e: Exception) {
            reportBuilder.appendLine("Error generating subsystem diagnostics: ${e.message}")
        }
        reportBuilder.appendLine()

        // 5. CONSOLE LOG (STDOUT / STDERR)
        reportBuilder.appendLine("--- [5. CONSOLE LOG (console.log)] ---")
        val consoleLog = storage.consoleLogFile(envId)
        if (consoleLog.exists()) {
            reportBuilder.appendLine("File: ${consoleLog.absolutePath} (${consoleLog.length()} bytes)")
            reportBuilder.appendLine("Content:")
            reportBuilder.appendLine(readTail(consoleLog, 1500))
        } else {
            reportBuilder.appendLine("Console log file not found at ${consoleLog.absolutePath}")
        }
        reportBuilder.appendLine()

        // 6. PROOT INTERNAL ENGINE TRACE (proot.log)
        reportBuilder.appendLine("--- [6. PROOT DETAILED ENGINE TRACE (proot.log)] ---")
        val prootLog = storage.prootLogFile(envId)
        if (prootLog.exists()) {
            reportBuilder.appendLine("File: ${prootLog.absolutePath} (${prootLog.length()} bytes)")
            reportBuilder.appendLine("Trace Output:")
            reportBuilder.appendLine(readTail(prootLog, 2500))
        } else {
            reportBuilder.appendLine("PRoot log file not found at ${prootLog.absolutePath}")
        }
        reportBuilder.appendLine()

        // 7. SYSTEM LOGCAT (LINUXDROID TAGS)
        reportBuilder.appendLine("--- [7. LOGCAT (LinuxDroid Buffer)] ---")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "LinuxDroid*:V", "*:S"))
            val logcatOutput = process.inputStream.bufferedReader().use { it.readText() }
            if (logcatOutput.isNotBlank()) {
                reportBuilder.appendLine(logcatOutput.lines().takeLast(300).joinToString("\n"))
            } else {
                reportBuilder.appendLine("Logcat buffer empty.")
            }
        } catch (e: Exception) {
            reportBuilder.appendLine("Failed to read logcat: ${e.message}")
        }
        reportBuilder.appendLine()

        reportBuilder.appendLine("================================================================================")
        reportBuilder.appendLine("END OF DIAGNOSTIC REPORT")
        reportBuilder.appendLine("================================================================================")

        reportBuilder.toString()
    }

    /**
     * Creates a ZIP archive containing all raw internal logs (console.log, proot.log, diagnostic report).
     */
    suspend fun generateFullLogsArchive(environment: Environment): File = withContext(Dispatchers.IO) {
        val envId = environment.id
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(storage.logsDir(envId), "linuxdroid_full_logs_$timestamp.zip")
        zipFile.parentFile?.mkdirs()

        val filesToZip = mutableListOf<Pair<String, File>>()
        val console = storage.consoleLogFile(envId)
        if (console.exists()) filesToZip.add("console.log" to console)
        val proot = storage.prootLogFile(envId)
        if (proot.exists()) filesToZip.add("proot.log" to proot)

        // Add plain text diagnostic summary
        val diagReport = File(storage.logsDir(envId), "diagnostics_summary.txt").apply {
            writeText(generateDetailedLogReport(environment))
        }
        filesToZip.add("diagnostics_summary.txt" to diagReport)

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((name, file) in filesToZip) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        }

        log.info("Created full logs archive at ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        zipFile
    }

    /**
     * Generates a comprehensive Terminal Session and Failure Log combining:
     * - Host device, Android OS, and kernel details
     * - Guest environment metadata and runtime state
     * - Terminal session state (exit code, PTY alive status)
     * - Plain-text terminal console buffer
     * - Correlated PRoot / system failure analysis from log streams
     * - Tail of internal proot.log and console.log
     */
    suspend fun generateTerminalFailureLog(
        environment: Environment,
        terminalOutput: String? = null,
        exitCode: Int? = null,
        isPtyActive: Boolean = false,
        asJson: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val envId = environment.id

        if (asJson) {
            val failureReport = analyzeFailures(environment)
            val jsonObject = org.json.JSONObject().apply {
                put("reportType", "TERMINAL_SESSION_FAILURE_LOG")
                put("timestamp", timestamp)
                put("environmentId", envId.value)
                put("environmentName", environment.name)
                put("distribution", environment.distribution.name)
                put("architecture", environment.architecture.name)
                put("state", environment.state.name)
                put("exitCode", exitCode ?: org.json.JSONObject.NULL)
                put("isPtyActive", isPtyActive)
                put("terminalOutput", terminalOutput ?: "")
                put("failureReport", org.json.JSONObject(reportExporter.buildJsonReport(failureReport, includeRawContext = false)))
            }
            return@withContext jsonObject.toString(2)
        }

        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("LINUXDROID TERMINAL SESSION & FAILURE LOG")
        sb.appendLine("Generated: $timestamp")
        sb.appendLine("================================================================================")
        sb.appendLine()

        // 1. SESSION & ENVIRONMENT METADATA
        sb.appendLine("--- [1. SESSION & ENVIRONMENT METADATA] ---")
        sb.appendLine("Environment: ${environment.name} (${envId.value})")
        sb.appendLine("Distribution: ${environment.distribution.displayName} (${environment.architecture.abiName})")
        sb.appendLine("Environment State: ${environment.state}")
        sb.appendLine("PTY Shell Active: $isPtyActive")
        sb.appendLine("Last Shell Exit Code: ${exitCode?.toString() ?: "N/A (Active or Aborted)"}")
        sb.appendLine("Rootfs Directory: ${storage.rootfsDir(envId).absolutePath}")
        sb.appendLine("Host Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Host Kernel: ${System.getProperty("os.version") ?: "unknown"}")
        sb.appendLine()

        // 2. TERMINAL CONSOLE BUFFER (RAW OUTPUT)
        sb.appendLine("--- [2. TERMINAL CONSOLE OUTPUT] ---")
        if (!terminalOutput.isNullOrBlank()) {
            sb.appendLine(terminalOutput.trimEnd())
        } else {
            sb.appendLine("(No terminal console text buffered)")
        }
        sb.appendLine()

        // 3. CORRELATED RUNTIME & SYSCALL FAILURES
        sb.appendLine("--- [3. CORRELATED RUNTIME & SYSCALL FAILURES] ---")
        try {
            val failureReport = analyzeFailures(environment)
            sb.appendLine("Primary Failure: ${failureReport.primaryCategory}")
            sb.appendLine("Root Cause Summary: ${failureReport.rootCauseSummary}")
            sb.appendLine("Total Detected Failures: ${failureReport.totalFailures}")
            if (failureReport.aggregatedFailures.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Aggregated Failure Signatures:")
                failureReport.aggregatedFailures.forEach { agg ->
                    sb.appendLine(" - [${agg.category}] count=${agg.count} source=${agg.source} syscall=${agg.syscallName ?: "none"}: ${agg.message}")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("Error analyzing runtime failures: ${e.message}")
        }
        sb.appendLine()

        // 4. RECENT CONSOLE & PROOT LOG TAILS
        sb.appendLine("--- [4. RECENT ENGINE LOGS] ---")
        val consoleLog = storage.consoleLogFile(envId)
        if (consoleLog.exists() && consoleLog.length() > 0) {
            sb.appendLine(">>> console.log (last 100 lines):")
            sb.appendLine(readTail(consoleLog, 100))
            sb.appendLine()
        }
        val prootLog = storage.prootLogFile(envId)
        if (prootLog.exists() && prootLog.length() > 0) {
            sb.appendLine(">>> proot.log (last 150 lines):")
            sb.appendLine(readTail(prootLog, 150))
            sb.appendLine()
        }

        sb.appendLine("================================================================================")
        sb.appendLine("END OF TERMINAL FAILURE LOG")
        sb.appendLine("================================================================================")
        sb.toString()
    }

    /**
     * Saves the requested export report to a file on disk.
     */
    suspend fun saveReportToFile(
        environment: Environment,
        exportType: LogExportType = LogExportType.TERMINAL_FAILURE_LOG,
        asJson: Boolean = false,
        terminalOutput: String? = null,
        exitCode: Int? = null,
        isPtyActive: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logsDir = storage.logsDir(environment.id).apply { mkdirs() }

        when (exportType) {
            LogExportType.TERMINAL_FAILURE_LOG -> {
                val ext = if (asJson) "json" else "txt"
                val file = File(logsDir, "linuxdroid_terminal_failure_$timestamp.$ext")
                val text = generateTerminalFailureLog(
                    environment = environment,
                    terminalOutput = terminalOutput,
                    exitCode = exitCode,
                    isPtyActive = isPtyActive,
                    asJson = asJson,
                )
                file.writeText(text)
                file
            }
            LogExportType.FAILURE_REPORT_COMPACT -> {
                val ext = if (asJson) "json" else "txt"
                val file = File(logsDir, "linuxdroid_failure_report_$timestamp.$ext")
                val text = generateFailureReportString(environment, includeRawContext = false, asJson = asJson)
                file.writeText(text)
                file
            }
            LogExportType.FAILURE_REPORT_DEVELOPER -> {
                val ext = if (asJson) "json" else "txt"
                val file = File(logsDir, "linuxdroid_failure_context_$timestamp.$ext")
                val text = generateFailureReportString(environment, includeRawContext = true, asJson = asJson)
                file.writeText(text)
                file
            }
            LogExportType.SYSTEM_DIAGNOSTICS -> {
                val file = File(logsDir, "linuxdroid_diagnostics_$timestamp.txt")
                file.writeText(generateDetailedLogReport(environment))
                file
            }
            LogExportType.FULL_LOGS -> {
                generateFullLogsArchive(environment)
            }
        }
    }

    /**
     * Creates an Android share Intent for the requested export type.
     */
    suspend fun createShareIntent(
        context: Context,
        environment: Environment,
        exportType: LogExportType = LogExportType.TERMINAL_FAILURE_LOG,
        asJson: Boolean = false,
        terminalOutput: String? = null,
        exitCode: Int? = null,
        isPtyActive: Boolean = false,
    ): Intent = withContext(Dispatchers.IO) {
        val file = saveReportToFile(
            environment = environment,
            exportType = exportType,
            asJson = asJson,
            terminalOutput = terminalOutput,
            exitCode = exitCode,
            isPtyActive = isPtyActive,
        )
        val authority = "${context.packageName}.fileprovider"

        val mimeType = when {
            file.name.endsWith(".zip") -> "application/zip"
            file.name.endsWith(".json") -> "application/json"
            else -> "text/plain"
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, "LinuxDroid ${exportType.displayName} - ${environment.name}")
            putExtra(Intent.EXTRA_TEXT, "Attached LinuxDroid ${exportType.displayName} for ${environment.name}.")
            try {
                val uri = FileProvider.getUriForFile(context, authority, file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                log.warn("FileProvider URI creation failed: ${e.message}")
                if (!file.name.endsWith(".zip")) {
                    putExtra(Intent.EXTRA_TEXT, file.readText())
                }
            }
        }
        Intent.createChooser(sendIntent, "Share LinuxDroid ${exportType.displayName}")
    }

    private fun readTail(file: File, maxLines: Int): String {
        return try {
            val lines = file.readLines()
            if (lines.size > maxLines) {
                lines.takeLast(maxLines).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }
        } catch (e: Exception) {
            "Error reading ${file.name}: ${e.message}"
        }
    }
}
