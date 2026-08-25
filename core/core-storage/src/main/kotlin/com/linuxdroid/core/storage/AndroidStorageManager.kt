package com.linuxdroid.core.storage

import android.content.Context
import android.os.Environment
import com.linuxdroid.core.logging.LinuxDroidLogger
import com.linuxdroid.core.logging.LogSubsystem
import com.linuxdroid.core.model.StorageAuthorizationError
import com.linuxdroid.core.model.StorageError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages access to the shared Android ↔ Linux storage directory:
 * /storage/emulated/0/LinuxDroid/
 *
 * This is the only Android storage location that LinuxDroid exposes to Linux.
 * Access requires explicit user authorization.
 *
 * Architecture:
 * ```
 * Android storage authorization
 *         ↓
 * AndroidStorageManager
 *         ↓
 * /storage/emulated/0/LinuxDroid/
 *         ↓
 * LinuxStorageBridge (bind-mounts into Linux)
 *         ↓
 * /home/user/Android/
 * ```
 */
class AndroidStorageManager(private val context: Context) {

    private val log = LinuxDroidLogger(LogSubsystem.STORAGE)

    companion object {
        const val SHARED_DIR_NAME = "LinuxDroid"
        const val LINUX_MOUNT_POINT = "/home/user/Android"
    }

    private val _authorizationState = MutableStateFlow<StorageAuthorizationState>(
        StorageAuthorizationState.Unknown
    )
    val authorizationState: StateFlow<StorageAuthorizationState> = _authorizationState.asStateFlow()

    /** The shared directory on Android external storage. */
    val sharedDirectory: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            SHARED_DIR_NAME
        )

    /**
     * Verifies access to the shared directory and updates [authorizationState].
     * Must be called after permissions are granted.
     */
    suspend fun verifyAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = sharedDirectory
            if (!dir.exists()) {
                log.info("Shared directory does not exist, creating: ${dir.path}")
                if (!dir.mkdirs()) {
                    log.warn("Failed to create shared directory: ${dir.path}")
                    _authorizationState.value = StorageAuthorizationState.Unauthorized(
                        "Cannot create shared directory"
                    )
                    return@withContext false
                }
            }

            val canRead = dir.canRead()
            val canWrite = dir.canWrite()

            if (canRead && canWrite) {
                log.info("Shared storage authorized: ${dir.path}")
                _authorizationState.value = StorageAuthorizationState.Authorized(dir)
                true
            } else {
                log.warn("Shared storage not fully accessible: read=$canRead write=$canWrite")
                _authorizationState.value = StorageAuthorizationState.Unauthorized(
                    "Insufficient permissions: read=$canRead write=$canWrite"
                )
                false
            }
        } catch (e: Exception) {
            log.error("Failed to verify storage access", e)
            _authorizationState.value = StorageAuthorizationState.Error(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Returns the authorized directory, or throws if not authorized.
     */
    fun getAuthorizedLocation(): File {
        val state = _authorizationState.value
        return when (state) {
            is StorageAuthorizationState.Authorized -> state.directory
            is StorageAuthorizationState.Unauthorized -> throw StorageAuthorizationError(state.reason)
            is StorageAuthorizationState.Error -> throw StorageError(state.message)
            StorageAuthorizationState.Unknown -> throw StorageAuthorizationError(
                "Storage access not yet verified. Call verifyAccess() first."
            )
        }
    }

    /**
     * Updates state to reflect authorization revocation.
     * Linux environment remains intact.
     */
    fun handleRevocation() {
        log.warn("Storage authorization revoked. Linux environment remains intact.")
        _authorizationState.value = StorageAuthorizationState.Unauthorized(
            "Authorization revoked by user or system"
        )
    }

    /** Returns true if storage is currently authorized. */
    fun isAuthorized(): Boolean = _authorizationState.value is StorageAuthorizationState.Authorized
}

/**
 * Authorization state for Android shared storage.
 */
sealed class StorageAuthorizationState {
    object Unknown : StorageAuthorizationState()
    data class Authorized(val directory: File) : StorageAuthorizationState()
    data class Unauthorized(val reason: String) : StorageAuthorizationState()
    data class Error(val message: String) : StorageAuthorizationState()
}
