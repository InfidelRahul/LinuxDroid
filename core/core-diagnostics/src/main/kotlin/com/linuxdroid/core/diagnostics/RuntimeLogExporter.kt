package com.linuxdroid.core.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.linuxdroid.core.filesystem.EnvironmentStorage
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.runtime.ProotRuntimeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Collects, formats, and exports comprehensive runtime diagnostics and logs.
 */
class RuntimeLogExporter(
    private val storage: EnvironmentStorage,
    private val diagnosticsManager: DiagnosticsManager,
) {
    private val log = LinuxDroidLogger(LogSubsystem.DIAGNOSTICS)

    /**
     * Builds a detailed human-readable Markdown/plain-text diagnostic report.
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
     * Saves the consolidated diagnostic log report to a file on disk.
     */
    suspend fun saveReportToFile(environment: Environment): File = withContext(Dispatchers.IO) {
        val report = generateDetailedLogReport(environment)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(storage.logsDir(environment.id), "linuxdroid_diagnostic_$timestamp.txt")
        file.parentFile?.mkdirs()
        file.writeText(report)
        log.info("Saved diagnostic report to ${file.absolutePath}")
        file
    }

    /**
     * Creates an Android share Intent for the log file or plaintext report.
     */
    suspend fun createShareIntent(context: Context, environment: Environment): Intent = withContext(Dispatchers.IO) {
        val file = saveReportToFile(environment)
        val authority = "${context.packageName}.fileprovider"

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LinuxDroid Runtime Log - ${environment.name} (${environment.id.value})")
            putExtra(Intent.EXTRA_TEXT, "Attached LinuxDroid runtime diagnostic report for ${environment.name}.")
            try {
                val uri = FileProvider.getUriForFile(context, authority, file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                log.warn("FileProvider URI creation failed, falling back to plaintext in EXTRA_TEXT: ${e.message}")
                putExtra(Intent.EXTRA_TEXT, file.readText())
            }
        }
        Intent.createChooser(sendIntent, "Share LinuxDroid Diagnostic Logs")
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

