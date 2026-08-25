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
 * A structured log entry.
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Int, // android.util.Log levels
    val subsystem: LogSubsystem,
    val environmentId: EnvironmentId? = null,
    val sessionId: SessionId? = null,
    val message: String,
    val errorCode: Int? = null,
    val throwable: Throwable? = null,
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
        append("[${subsystem.name}")
        environmentId?.let { append("/env:$it") }
        sessionId?.let { append("/ses:$it") }
        errorCode?.let { append("/err:$it") }
        append("] ")
        append(message)
        throwable?.let { append(" | ${it.javaClass.simpleName}: ${it.message}") }
    }
}

/**
 * Structured logger for LinuxDroid subsystems.
 *
 * All logging goes through this class so that subsystem, environment ID,
 * and session ID are always captured in a structured way.
 *
 * Usage:
 * ```kotlin
 * private val log = LinuxDroidLogger(LogSubsystem.RUNTIME, environmentId)
 * log.info("Runtime started")
 * log.error("Runtime failed", exception)
 * ```
 */
class LinuxDroidLogger(
    private val subsystem: LogSubsystem,
    private val environmentId: EnvironmentId? = null,
    private val sessionId: SessionId? = null,
) {
    private val tag = "LinuxDroid/${subsystem.name}"

    fun verbose(message: String, errorCode: Int? = null) {
        val entry = entry(Log.VERBOSE, message, errorCode)
        Timber.tag(tag).v(entry.format())
    }

    fun debug(message: String, errorCode: Int? = null) {
        val entry = entry(Log.DEBUG, message, errorCode)
        Timber.tag(tag).d(entry.format())
    }

    fun info(message: String, errorCode: Int? = null) {
        val entry = entry(Log.INFO, message, errorCode)
        Timber.tag(tag).i(entry.format())
    }

    fun warn(message: String, throwable: Throwable? = null, errorCode: Int? = null) {
        val entry = entry(Log.WARN, message, errorCode, throwable)
        Timber.tag(tag).w(throwable, entry.format())
    }

    fun error(message: String, throwable: Throwable? = null, errorCode: Int? = null) {
        val entry = entry(Log.ERROR, message, errorCode, throwable)
        Timber.tag(tag).e(throwable, entry.format())
    }

    fun withSession(sessionId: SessionId): LinuxDroidLogger =
        LinuxDroidLogger(subsystem, environmentId, sessionId)

    fun withEnvironment(environmentId: EnvironmentId): LinuxDroidLogger =
        LinuxDroidLogger(subsystem, environmentId, sessionId)

    private fun entry(
        level: Int,
        message: String,
        errorCode: Int? = null,
        throwable: Throwable? = null,
    ) = LogEntry(
        level = level,
        subsystem = subsystem,
        environmentId = environmentId,
        sessionId = sessionId,
        message = message,
        errorCode = errorCode,
        throwable = throwable,
    )
}
