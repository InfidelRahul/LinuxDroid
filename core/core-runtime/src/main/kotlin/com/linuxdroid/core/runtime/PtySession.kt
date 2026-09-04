package com.linuxdroid.core.runtime

import com.linuxdroid.core.model.EnvironmentId
import com.linuxdroid.native_bridge.NativeBridge
import java.io.Closeable

/**
 * A persistent interactive pseudoterminal (PTY) session running a Linux shell inside PRoot.
 */
data class PtySession(
    val sessionId: String,
    val environmentId: EnvironmentId,
    val pid: Int,
    val masterFd: Int,
) : Closeable {

    @Volatile
    private var isClosed = false

    @Volatile
    private var cachedExitCode: Int? = null

    /**
     * Writes raw bytes to the PTY master descriptor (sent to tracee shell stdin).
     */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        if (isClosed || masterFd < 0) return -1
        return NativeBridge.writeFd(masterFd, data, offset, length)
    }

    /**
     * Writes UTF-8 text string to the PTY.
     */
    fun write(text: String): Int {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return write(bytes, 0, bytes.size)
    }

    /**
     * Reads available output from the PTY master descriptor.
     */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        if (isClosed || masterFd < 0) return -1
        return NativeBridge.readFd(masterFd, buffer, offset, length)
    }

    /**
     * Updates terminal window size (TIOCSWINSZ).
     */
    fun resize(rows: Int, cols: Int): Int {
        if (isClosed || masterFd < 0) return -1
        return NativeBridge.setPtyWindowSize(masterFd, rows, cols)
    }

    /**
     * Checks if the tracee process is still active.
     */
    fun isAlive(): Boolean {
        if (isClosed || pid <= 0 || masterFd < 0) return false
        if (cachedExitCode != null) return false
        val status = NativeBridge.waitpid(pid, false)
        return if (status >= 0) {
            cachedExitCode = status
            false
        } else {
            true // -1 means still running
        }
    }

    /**
     * Gets exit status if process terminated.
     * Performs a bounded wait (up to [maxWaitMs]) to handle kernel process teardown transitions.
     */
    fun getExitCode(maxWaitMs: Long = 1000L): Int? {
        if (cachedExitCode != null) return cachedExitCode
        if (pid <= 0) return null

        val startTime = System.currentTimeMillis()
        while (true) {
            val status = NativeBridge.waitpid(pid, false)
            if (status >= 0) {
                cachedExitCode = status
                return status
            }
            if (System.currentTimeMillis() - startTime >= maxWaitMs) {
                break
            }
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                break
            }
        }
        return null
    }

    /**
     * Closes the PTY session and terminates child process cleanly.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        if (masterFd >= 0) {
            NativeBridge.closeFd(masterFd)
        }
        if (pid > 0 && cachedExitCode == null) {
            NativeBridge.sendSignal(pid, 15) // SIGTERM
            val startWait = System.currentTimeMillis()
            while (System.currentTimeMillis() - startWait < 200) {
                val status = NativeBridge.waitpid(pid, false)
                if (status >= 0) {
                    cachedExitCode = status
                    return
                }
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
            // Force kill if not reaped after SIGTERM
            NativeBridge.sendSignal(pid, 9) // SIGKILL
            val finalStatus = NativeBridge.waitpid(pid, false)
            if (finalStatus >= 0) {
                cachedExitCode = finalStatus
            }
        }
    }
}

