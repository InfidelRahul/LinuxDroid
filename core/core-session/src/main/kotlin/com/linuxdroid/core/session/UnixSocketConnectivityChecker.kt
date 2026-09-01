package com.linuxdroid.core.session

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.linuxdroid.core.gui.SocketConnectivityChecker
import java.io.File

/**
 * Verifies a Wayland endpoint by performing a real UNIX-domain connect to the
 * socket file, then closing it immediately.
 *
 * A socket file can exist while the compositor is not yet accepting, so the
 * existence check alone is never treated as readiness.
 */
class UnixSocketConnectivityChecker : SocketConnectivityChecker {

    override fun canConnect(hostSocketPath: String): Boolean {
        if (!File(hostSocketPath).exists()) return false
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        return try {
            socket.connect(
                LocalSocketAddress(hostSocketPath, LocalSocketAddress.Namespace.FILESYSTEM),
            )
            socket.isConnected
        } catch (_: Exception) {
            // Not accepting yet, or not a socket at all.
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
