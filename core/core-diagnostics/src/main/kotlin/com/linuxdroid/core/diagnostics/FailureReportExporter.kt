package com.linuxdroid.core.diagnostics

import com.linuxdroid.core.model.AggregatedFailure
import com.linuxdroid.core.model.FailureEvent
import com.linuxdroid.core.model.FailureReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporter and Formatter for Failure Diagnostic Reports.
 *
 * Produces compact human-readable Markdown/Text and structured JSON reports.
 * Enforces strict size limits (≤ 1 MB) using prioritized truncation.
 */
class FailureReportExporter(
    private val maxSizeBytes: Int = 1_000_000, // 1 MB default limit
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    /**
     * Builds a human-readable text report.
     */
    fun buildPlainTextReport(report: FailureReport, includeRawContext: Boolean = false): String {
        val sb = StringBuilder()

        sb.appendLine("================================================================================")
        sb.appendLine("LINUXDROID FAILURE DIAGNOSTIC REPORT")
        sb.appendLine("Report ID: ${report.reportId}")
        sb.appendLine("Generated: ${report.timestamp}")
        sb.appendLine("================================================================================")
        sb.appendLine()

        // 1. Environment and System Specs
        sb.appendLine("--- [1. HOST & GUEST ENVIRONMENT] ---")
        report.environmentInfo.forEach { (k, v) ->
            sb.appendLine("$k: $v")
        }
        sb.appendLine()

        // 2. Failure Summary & Root Cause
        sb.appendLine("--- [2. FAILURE SUMMARY & ROOT CAUSE] ---")
        sb.appendLine("Primary Category: ${report.primaryCategory.name}")
        sb.appendLine("Total Detected Failures: ${report.totalFailures}")
        sb.appendLine("Unique Failure Signatures: ${report.uniqueSignaturesCount}")
        sb.appendLine("Root Cause Summary: ${report.rootCauseSummary}")
        sb.appendLine()

        // 3. Correlated Failure Chains
        sb.appendLine("--- [3. CORRELATED FAILURE CHAINS (${report.causalChains.size} Chains)] ---")
        if (report.causalChains.isEmpty()) {
            sb.appendLine("No active failure chains detected.")
        } else {
            report.causalChains.take(10).forEachIndexed { index, chain ->
                sb.appendLine("Chain #${index + 1} (Correlation ID: ${chain.firstOrNull()?.correlationId ?: "unknown"}):")
                chain.forEachIndexed { evIdx, ev ->
                    val sysStr = ev.syscallName?.let { " syscall=$it (#${ev.syscallNumber})" } ?: ""
                    val errStr = ev.errnoName?.let { " errno=$it (#${ev.errno})" } ?: ""
                    val sigStr = ev.signalName?.let { " signal=$it (#${ev.signal})" } ?: ""
                    val addrStr = ev.rawAddress?.let { " addr=$it" } ?: ""
                    sb.appendLine("  ${evIdx + 1}. [${ev.category.name}] ${ev.message}$sysStr$errStr$sigStr$addrStr")
                }
                sb.appendLine()
            }
        }

        // 4. Aggregated Deduplicated Errors
        sb.appendLine("--- [4. AGGREGATED ERRORS & OCCURRENCE COUNTS (${report.aggregatedFailures.size} Unique)] ---")
        if (report.aggregatedFailures.isEmpty()) {
            sb.appendLine("No aggregated errors recorded.")
        } else {
            report.aggregatedFailures.forEach { agg ->
                val firstStr = dateFormat.format(Date(agg.firstSeen))
                val lastStr = dateFormat.format(Date(agg.lastSeen))
                val sysInfo = agg.syscallName?.let { " | Syscall: $it" } ?: ""
                val errInfo = agg.errnoName?.let { " | Errno: $it" } ?: ""
                sb.appendLine("• [${agg.category.name}] ${agg.source}: ${agg.message.take(100)}")
                sb.appendLine("  Count: ${agg.count}$sysInfo$errInfo")
                sb.appendLine("  First Seen: $firstStr | Last Seen: $lastStr")
                sb.appendLine()
            }
        }

        // 5. Bounded Raw Log Context (Optional / Developer Level)
        if (includeRawContext) {
            sb.appendLine("--- [5. BOUNDED FAILURE TRACE CONTEXT] ---")
            report.causalChains.take(5).forEachIndexed { chainIdx, chain ->
                sb.appendLine("=== Context for Failure Chain #${chainIdx + 1} ===")
                chain.firstOrNull()?.let { ev ->
                    if (ev.contextBefore.isNotEmpty()) {
                        sb.appendLine("--- [Context Before (${ev.contextBefore.size} lines)] ---")
                        ev.contextBefore.takeLast(20).forEach { sb.appendLine("  $it") }
                    }
                    sb.appendLine(">>> [FAILURE TRIGGER]: ${ev.message}")
                    if (ev.contextAfter.isNotEmpty()) {
                        sb.appendLine("--- [Context After (${ev.contextAfter.size} lines)] ---")
                        ev.contextAfter.take(50).forEach { sb.appendLine("  $it") }
                    }
                    sb.appendLine()
                }
            }
        }

        sb.appendLine("================================================================================")
        sb.appendLine("END OF FAILURE REPORT")
        sb.appendLine("================================================================================")

        return enforceSizeLimit(sb.toString())
    }

    /**
     * Builds a structured JSON report.
     */
    fun buildJsonReport(report: FailureReport, includeRawContext: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"reportId\": \"${escapeJson(report.reportId)}\",\n")
        sb.append("  \"timestamp\": \"${escapeJson(report.timestamp)}\",\n")
        sb.append("  \"primaryCategory\": \"${report.primaryCategory.name}\",\n")
        sb.append("  \"rootCauseSummary\": \"${escapeJson(report.rootCauseSummary)}\",\n")
        sb.append("  \"totalFailures\": ${report.totalFailures},\n")
        sb.append("  \"uniqueSignaturesCount\": ${report.uniqueSignaturesCount},\n")
        sb.append("  \"rawContextIncluded\": $includeRawContext,\n")

        // Environment Info
        sb.append("  \"environmentInfo\": {\n")
        val envEntries = report.environmentInfo.entries.toList()
        envEntries.forEachIndexed { idx, (k, v) ->
            val comma = if (idx < envEntries.size - 1) "," else ""
            sb.append("    \"${escapeJson(k)}\": \"${escapeJson(v)}\"$comma\n")
        }
        sb.append("  },\n")

        // Aggregated Failures
        sb.append("  \"aggregatedFailures\": [\n")
        report.aggregatedFailures.forEachIndexed { idx, agg ->
            val comma = if (idx < report.aggregatedFailures.size - 1) "," else ""
            sb.append("    {\n")
            sb.append("      \"signature\": \"${escapeJson(agg.signature)}\",\n")
            sb.append("      \"category\": \"${agg.category.name}\",\n")
            sb.append("      \"syscallName\": ${agg.syscallName?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            sb.append("      \"errnoName\": ${agg.errnoName?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            sb.append("      \"source\": \"${escapeJson(agg.source)}\",\n")
            sb.append("      \"message\": \"${escapeJson(agg.message)}\",\n")
            sb.append("      \"count\": ${agg.count},\n")
            sb.append("      \"firstSeen\": ${agg.firstSeen},\n")
            sb.append("      \"lastSeen\": ${agg.lastSeen}\n")
            sb.append("    }$comma\n")
        }
        sb.append("  ],\n")

        // Causal Chains
        sb.append("  \"causalChains\": [\n")
        report.causalChains.take(10).forEachIndexed { chainIdx, chain ->
            val chainComma = if (chainIdx < minOf(10, report.causalChains.size) - 1) "," else ""
            sb.append("    [\n")
            chain.forEachIndexed { evIdx, ev ->
                val evComma = if (evIdx < chain.size - 1) "," else ""
                sb.append("      {\n")
                sb.append("        \"id\": \"${escapeJson(ev.id)}\",\n")
                sb.append("        \"correlationId\": \"${escapeJson(ev.correlationId)}\",\n")
                sb.append("        \"category\": \"${ev.category.name}\",\n")
                sb.append("        \"message\": \"${escapeJson(ev.message)}\",\n")
                sb.append("        \"source\": \"${escapeJson(ev.source)}\",\n")
                sb.append("        \"pid\": ${ev.pid ?: "null"},\n")
                sb.append("        \"syscallNumber\": ${ev.syscallNumber ?: "null"},\n")
                sb.append("        \"syscallName\": ${ev.syscallName?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                sb.append("        \"errno\": ${ev.errno ?: "null"},\n")
                sb.append("        \"errnoName\": ${ev.errnoName?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                sb.append("        \"signal\": ${ev.signal ?: "null"},\n")
                sb.append("        \"signalName\": ${ev.signalName?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
                sb.append("        \"rawAddress\": ${ev.rawAddress?.let { "\"${escapeJson(it)}\"" } ?: "null"}\n")
                sb.append("      }$evComma\n")
            }
            sb.append("    ]$chainComma\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")

        return enforceSizeLimit(sb.toString())
    }

    private fun enforceSizeLimit(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxSizeBytes) return content

        val headerTruncNotice = "\n... [Report Truncated to stay within ${maxSizeBytes / 1024} KB limit] ...\n"
        val cutLength = maxSizeBytes - headerTruncNotice.toByteArray(Charsets.UTF_8).size
        val truncated = String(bytes, 0, cutLength, Charsets.UTF_8)
        return truncated + headerTruncNotice
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

