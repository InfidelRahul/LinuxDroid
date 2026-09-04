package com.linuxdroid.core.logging

import com.linuxdroid.core.model.EnvironmentId
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe file logger managing categorized logs per environment and globally.
 *
 * Honors [LogConfig.generate_log] to completely bypass file operations when logging is disabled.
 */
object LogFileManager {

    private val environmentDirs = ConcurrentHashMap<String, File>()
    private var baseLogsDir: File? = null
    private val writeLocks = ConcurrentHashMap<String, Any>()

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneOffset.UTC)

    // Regex to strip ANSI escape codes for clean terminal logging
    private val ansiEscapeRegex = Regex("\u001B\\[[;?0-9]*[a-zA-Z]|\u001B\\([a-zA-Z]")

    fun setBaseLogsDir(dir: File) {
        baseLogsDir = dir.apply { mkdirs() }
    }

    fun registerEnvironmentLogsDir(environmentId: EnvironmentId, logsDir: File) {
        environmentDirs[environmentId.value] = logsDir.apply { mkdirs() }
    }

    fun getLogsDir(environmentId: EnvironmentId?): File? {
        if (environmentId != null) {
            val registered = environmentDirs[environmentId.value]
            if (registered != null) return registered
            val base = baseLogsDir
            if (base != null) {
                val derived = File(base, "environments/${environmentId.value}/logs")
                if (derived.exists() || derived.mkdirs()) {
                    environmentDirs[environmentId.value] = derived
                    return derived
                }
            }
        }
        return baseLogsDir
    }

    /**
     * Appends a formatted log entry into the appropriate category log file.
     */
    fun writeEntry(
        category: LogCategory,
        environmentId: EnvironmentId?,
        entry: LogEntry,
    ) {
        if (!LogConfig.generate_log) return

        val dir = getLogsDir(environmentId) ?: return
        val targetFile = File(dir, category.filename)
        val formatted = entry.format() + "\n"

        appendToFile(targetFile, formatted)

        // Mirror relevant logs to console.log as well
        if (category == LogCategory.SYSTEM_PROCESS || category == LogCategory.PREBOOT || category == LogCategory.GUEST_INIT) {
            val consoleFile = File(dir, LogCategory.CONSOLE.filename)
            appendToFile(consoleFile, formatted)
        }
    }

    /**
     * Streams interactive terminal output (e.g. from PTY reader during `apt install nano`)
     * directly into terminal.log and console.log in real time.
     */
    fun appendTerminalBytes(
        environmentId: EnvironmentId?,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (!LogConfig.generate_log || length <= 0) return

        val rawText = String(bytes, offset, length, StandardCharsets.UTF_8)
        appendTerminalText(environmentId, rawText)
    }

    /**
     * Appends terminal text directly into terminal.log and console.log.
     */
    fun appendTerminalText(
        environmentId: EnvironmentId?,
        text: String,
    ) {
        if (!LogConfig.generate_log || text.isEmpty()) return

        val dir = getLogsDir(environmentId) ?: return
        val terminalFile = File(dir, LogCategory.TERMINAL.filename)
        val consoleFile = File(dir, LogCategory.CONSOLE.filename)

        // Clean ANSI sequences for human-readable transcript
        val cleanText = ansiEscapeRegex.replace(text, "")

        appendToFile(terminalFile, cleanText)
        appendToFile(consoleFile, cleanText)
    }

    /**
     * Records command execution metadata or process output into process.log and console.log.
     */
    fun appendProcessOutput(
        environmentId: EnvironmentId?,
        text: String,
    ) {
        if (!LogConfig.generate_log || text.isEmpty()) return

        val dir = getLogsDir(environmentId) ?: return
        val processFile = File(dir, LogCategory.SYSTEM_PROCESS.filename)
        val consoleFile = File(dir, LogCategory.CONSOLE.filename)

        appendToFile(processFile, text)
        appendToFile(consoleFile, text)
    }

    /**
     * Clears all log files for an environment.
     */
    fun clearLogs(environmentId: EnvironmentId?) {
        val dir = getLogsDir(environmentId) ?: return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".log")) {
                file.delete()
            }
        }
    }

    private fun appendToFile(file: File, content: String) {
        val lock = writeLocks.computeIfAbsent(file.absolutePath) { Any() }
        synchronized(lock) {
            try {
                file.parentFile?.mkdirs()

                // Check file rotation if file exceeds limit
                if (file.exists() && file.length() > LogConfig.MAX_LOG_FILE_BYTES) {
                    rotateFile(file)
                }

                FileOutputStream(file, true).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                        writer.write(content)
                        writer.flush()
                    }
                }
            } catch (_: Exception) {
                // Ignore file logging exceptions to prevent crashing the host process
            }
        }
    }

    private fun rotateFile(file: File) {
        try {
            val backup = File(file.parentFile, "${file.name}.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        } catch (_: Exception) {
            // If rename fails, truncate directly
            try {
                FileOutputStream(file).close()
            } catch (_: Exception) { }
        }
    }
}
