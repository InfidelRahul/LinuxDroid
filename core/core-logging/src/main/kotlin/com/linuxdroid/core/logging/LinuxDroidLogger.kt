package com.linuxdroid.core.logging

import android.util.Log
import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.core.model.SessionId
import timber.log.Timber
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Subsystem identifiers for structured log routing.
 */
enum class LogSubsystem {
    ANDROID,
    RUNTIME,
    FILESYSTEM,
    PROCESS,
    SESSION,
    WAYLAND,
    DISPLAY,
    GPU,
    INPUT,
    AUDIO,
    NETWORK,
    PACKAGE,
    APPLICATION,
    RESOURCE,
    STORAGE,
    SECURITY,
    DATABASE,
    BRIDGE,
    BOOTSTRAP,
    DIAGNOSTICS,
}

/**
 * A structured log entry capturing specific details, error codes, and stack traces.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Int, // android.util.Log levels
    val subsystem: LogSubsystem,
    val category: LogCategory = LogCategory.fromSubsystem(subsystem),
    val environmentId: EnvironmentId? = null,
    val sessionId: SessionId? = null,
    val message: String,
    val errorCode: Int? = null,
    val throwable: Throwable? = null,
    val details: Map<String, String>? = null,
) {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    fun format(): String = buildString {
        append(formatter.format(Instant.ofEpochMilli(timestamp)))
        append(' ')
        append(when (level) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'A'
        })
        append(' ')
        append("[${category.name}/${subsystem.name}")
        environmentId?.let { append("/env:$it") }
        sessionId?.let { append("/ses:$it") }
        errorCode?.let { append("/err:$it") }
        append("] ")
        append(message)
        details?.takeIf { it.isNotEmpty() }?.let { map ->
            append(" | details={")
            append(map.entries.joinToString(", ") { "${it.key}=${it.value}" })
            append("}")
        }
        throwable?.let {
            append(" | ${it.javaClass.simpleName}: ${it.message}")
            val st = it.stackTraceToString()
            append("\n    ").append(st.replace("\n", "\n    "))
        }
    }
}

/**
 * Structured logger for LinuxDroid subsystems.
 *
 * All logging goes through this class so that category, subsystem, environment ID,
 * and session ID are captured in a structured way and written to categorized log files.
 *
 * Honors [LogConfig.generate_log] to cleanly enable/disable log generation.
 */
class LinuxDroidLogger(
    val subsystem: LogSubsystem,
    val environmentId: EnvironmentId? = null,
    val sessionId: SessionId? = null,
    val category: LogCategory = LogCategory.fromSubsystem(subsystem),
) {
    private val tag = "LinuxDroid/${subsystem.name}"

    fun verbose(message: String, errorCode: Int? = null, details: Map<String, String>? = null) {
        val entry = entry(Log.VERBOSE, message, errorCode, details = details)
        Timber.tag(tag).v(entry.format())
        LogFileManager.writeEntry(category, environmentId, entry)
    }

    fun debug(message: String, errorCode: Int? = null, details: Map<String, String>? = null) {
        val entry = entry(Log.DEBUG, message, errorCode, details = details)
        Timber.tag(tag).d(entry.format())
        LogFileManager.writeEntry(category, environmentId, entry)
    }

    fun info(message: String, errorCode: Int? = null, details: Map<String, String>? = null) {
        val entry = entry(Log.INFO, message, errorCode, details = details)
        Timber.tag(tag).i(entry.format())
        LogFileManager.writeEntry(category, environmentId, entry)
    }

    fun warn(
        message: String,
        throwable: Throwable? = null,
        errorCode: Int? = null,
        details: Map<String, String>? = null,
    ) {
        val entry = entry(Log.WARN, message, errorCode, throwable, details)
        Timber.tag(tag).w(throwable, entry.format())
        LogFileManager.writeEntry(category, environmentId, entry)
    }

    fun error(
        message: String,
        throwable: Throwable? = null,
        errorCode: Int? = null,
        details: Map<String, String>? = null,
    ) {
        val entry = entry(Log.ERROR, message, errorCode, throwable, details)
        Timber.tag(tag).e(throwable, entry.format())
        LogFileManager.writeEntry(category, environmentId, entry)
    }

    fun withCategory(category: LogCategory): LinuxDroidLogger =
        LinuxDroidLogger(subsystem, environmentId, sessionId, category)

    fun withSession(sessionId: SessionId): LinuxDroidLogger =
        LinuxDroidLogger(subsystem, environmentId, sessionId, category)

    fun withEnvironment(environmentId: EnvironmentId): LinuxDroidLogger =
        LinuxDroidLogger(subsystem, environmentId, sessionId, category)

    private fun entry(
        level: Int,
        message: String,
        errorCode: Int? = null,
        throwable: Throwable? = null,
        details: Map<String, String>? = null,
    ) = LogEntry(
        level = level,
        subsystem = subsystem,
        category = category,
        environmentId = environmentId,
        sessionId = sessionId,
        message = message,
        errorCode = errorCode,
        throwable = throwable,
        details = details,
    )
}
