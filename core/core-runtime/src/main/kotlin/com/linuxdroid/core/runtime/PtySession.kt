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
        val status = NativeBridge.waitpid(pid, false)
        return status == -1 // -1 means still running
    }

    /**
     * Gets exit status if process terminated.
     */
    fun getExitCode(): Int? {
        if (pid <= 0) return null
        val status = NativeBridge.waitpid(pid, false)
        return if (status >= 0) status else null
    }

    /**
     * Closes the PTY session and terminates child process.
     */
    override fun close() {
        if (isClosed) return
        isClosed = true
        if (masterFd >= 0) {
            NativeBridge.closeFd(masterFd)
        }
        if (pid > 0) {
            NativeBridge.sendSignal(pid, 15) // SIGTERM
        }
    }
}

