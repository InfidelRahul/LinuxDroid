package com.linuxdroid.core.gui

import kotlinx.coroutines.delay
import java.io.File

/**
 * Verifies that a Wayland endpoint is actually usable.
 *
 * Connecting to a UNIX socket requires a platform mechanism, so the actual
 * connect is delegated to [SocketConnectivityChecker]. Everything else — the
 * polling loop, liveness checks, and the ordered verification steps — is pure
 * logic and unit-testable.
 */
fun interface SocketConnectivityChecker {
    /**
     * Attempts a real connection to the UNIX socket at [hostSocketPath].
     *
     * @return true only if a connection was established (and then closed).
     */
    fun canConnect(hostSocketPath: String): Boolean
}

/** Individual verification steps, in the order they are performed. */
enum class ReadinessStep {
    PROCESS_ALIVE,
    RUNTIME_DIR_EXISTS,
    SOCKET_EXISTS,
    SOCKET_CONNECTABLE,
    PROCESS_STILL_ALIVE,
}

/** Outcome of a readiness verification attempt. */
data class ReadinessResult(
    val ready: Boolean,
    val failedStep: ReadinessStep? = null,
    val detail: String = "",
) {
    companion object {
        val READY = ReadinessResult(ready = true)
    }
}

/**
 * Default [CompositorReadinessProbe].
 *
 * Readiness requires, in order: the compositor process is alive, the runtime
 * directory exists, the socket file exists, the socket accepts a connection,
 * and the process is *still* alive after the connection succeeded. Process
 * creation alone is never sufficient.
 */
class WaylandReadinessProbe(
    private val connectivityChecker: SocketConnectivityChecker,
    private val pollIntervalMs: Long = 100,
    private val clock: () -> Long = System::currentTimeMillis,
) : CompositorReadinessProbe {

    /** Process being verified; supplied per attempt via [awaitReadyFor]. */
    override suspend fun awaitReady(session: WaylandSessionInfo, timeoutMs: Long): Boolean =
        awaitReadyFor(session, process = null, timeoutMs = timeoutMs).ready

    /**
     * Polls until every [ReadinessStep] passes, the compositor dies, or
     * [timeoutMs] elapses.
     *
     * @param process compositor process to observe; when null, liveness checks
     * are skipped (used only where the caller has no process handle).
     */
    suspend fun awaitReadyFor(
        session: WaylandSessionInfo,
        process: CompositorProcess?,
        timeoutMs: Long,
    ): ReadinessResult {
        val deadline = clock() + timeoutMs
        var last = ReadinessResult(false, ReadinessStep.SOCKET_EXISTS, "not yet attempted")

        while (true) {
            last = verify(session, process)
            if (last.ready) return last

            // A dead compositor will never become ready — fail immediately.
            if (last.failedStep == ReadinessStep.PROCESS_ALIVE ||
                last.failedStep == ReadinessStep.PROCESS_STILL_ALIVE
            ) {
                return last
            }
            if (clock() >= deadline) {
                return ReadinessResult(
                    ready = false,
                    failedStep = last.failedStep,
                    detail = "timeout after ${timeoutMs}ms; last failure: ${last.detail}",
                )
            }
            delay(pollIntervalMs)
        }
    }

    /** Runs one full verification pass without waiting. */
    fun verify(session: WaylandSessionInfo, process: CompositorProcess?): ReadinessResult {
        if (process != null && !process.isAlive()) {
            return ReadinessResult(
                ready = false,
                failedStep = ReadinessStep.PROCESS_ALIVE,
                detail = "compositor process exited (pid=${process.pid} exit=${process.exitCode()})",
            )
        }

        val runtimeDir = File(session.hostRuntimeDir)
        if (!runtimeDir.isDirectory) {
            return ReadinessResult(
                ready = false,
                failedStep = ReadinessStep.RUNTIME_DIR_EXISTS,
                detail = "runtime directory missing: ${session.hostRuntimeDir}",
            )
        }

        if (!File(session.hostSocketPath).exists()) {
            return ReadinessResult(
                ready = false,
                failedStep = ReadinessStep.SOCKET_EXISTS,
                detail = "wayland socket not present: ${session.hostSocketPath}",
            )
        }

        if (!connectivityChecker.canConnect(session.hostSocketPath)) {
            return ReadinessResult(
                ready = false,
                failedStep = ReadinessStep.SOCKET_CONNECTABLE,
                detail = "wayland socket exists but refused a connection: ${session.hostSocketPath}",
            )
        }

        if (process != null && !process.isAlive()) {
            return ReadinessResult(
                ready = false,
                failedStep = ReadinessStep.PROCESS_STILL_ALIVE,
                detail = "compositor exited during readiness verification (pid=${process.pid})",
            )
        }

        return ReadinessResult.READY
    }
}
