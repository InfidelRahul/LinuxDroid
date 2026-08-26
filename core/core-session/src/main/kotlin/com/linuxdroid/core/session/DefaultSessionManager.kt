package com.linuxdroid.core.session

import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.*
import com.linuxdroid.core.runtime.RuntimeBackend
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete implementation of [SessionManager].
 * Coordinates starting/stopping runtime sessions and lifecycle tracking.
 */
class DefaultSessionManager(
    private val runtimeBackend: RuntimeBackend,
) : SessionManager {

    private val log = LinuxDroidLogger(LogSubsystem.SESSION)
    private val sessionMap = ConcurrentHashMap<SessionId, Session>()

    private val _sessions = MutableStateFlow<Map<SessionId, Session>>(emptyMap())
    override val sessions: Flow<Map<SessionId, Session>> = _sessions.asStateFlow()

    override suspend fun startSession(environment: Environment): Session {
        val sessionId = SessionId.generate()
        log.info("Starting session $sessionId for environment ${environment.id}")

        val session = Session(
            id = sessionId,
            environmentId = environment.id,
            state = SessionState.STARTING_RUNTIME,
            startedAt = System.currentTimeMillis(),
        )
        sessionMap[sessionId] = session
        _sessions.value = sessionMap.toMap()

        try {
            runtimeBackend.prepare(environment)
            runtimeBackend.initialize(environment)
            runtimeBackend.start(environment)

            val runningSession = session.copy(state = SessionState.RUNNING)
            sessionMap[sessionId] = runningSession
            _sessions.value = sessionMap.toMap()
            log.info("Session $sessionId is now RUNNING")
            return runningSession
        } catch (e: Exception) {
            log.error("Failed to start session $sessionId", e)
            val failedSession = session.copy(
                state = SessionState.FAILED,
                failureMessage = e.message ?: "Failed to start session",
                stoppedAt = System.currentTimeMillis()
            )
            sessionMap[sessionId] = failedSession
            _sessions.value = sessionMap.toMap()
            throw e
        }
    }

    override suspend fun stopSession(sessionId: SessionId) {
        val session = sessionMap[sessionId] ?: return
        log.info("Stopping session $sessionId")

        val stoppingSession = session.copy(state = SessionState.STOPPING)
        sessionMap[sessionId] = stoppingSession
        _sessions.value = sessionMap.toMap()

        val stoppedSession = stoppingSession.copy(
            state = SessionState.STOPPED,
            stoppedAt = System.currentTimeMillis(),
        )
        sessionMap[sessionId] = stoppedSession
        _sessions.value = sessionMap.toMap()
        log.info("Session $sessionId is now STOPPED")
    }

    override suspend fun getSession(environmentId: EnvironmentId): Session? {
        return sessionMap.values.firstOrNull {
            it.environmentId == environmentId && it.state.isActive()
        }
    }
}

