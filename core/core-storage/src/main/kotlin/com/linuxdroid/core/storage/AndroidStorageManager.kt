package com.linuxdroid.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
 * Access requires explicit user authorization via Scoped Storage or MANAGE_EXTERNAL_STORAGE.
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

    /** The shared directory on Android external storage: /storage/emulated/0/LinuxDroid/ */
    val sharedDirectory: File
        get() = File(
            Environment.getExternalStorageDirectory(),
            SHARED_DIR_NAME
        )

    /**
     * Verifies access to the shared directory and updates [authorizationState].
     * Performs a real read/write test file probe to guarantee filesystem accessibility.
     */
    suspend fun verifyAccess(): Boolean = withContext(Dispatchers.IO) {
        try {
            log.info("Verifying storage access for ${sharedDirectory.path}")

            // 1. Check Android system permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    log.warn("MANAGE_EXTERNAL_STORAGE not granted on Android 11+")
                    _authorizationState.value = StorageAuthorizationState.Unauthorized(
                        "All Files Access permission is required to access /storage/emulated/0/LinuxDroid/"
                    )
                    return@withContext false
                }
            } else {
                val readGranted = context.checkSelfPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                val writeGranted = context.checkSelfPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (!readGranted || !writeGranted) {
                    log.warn("Storage runtime permissions not granted on Android 9/10")
                    _authorizationState.value = StorageAuthorizationState.Unauthorized(
                        "Storage permission required"
                    )
                    return@withContext false
                }
            }

            // 2. Ensure shared directory exists
            val dir = sharedDirectory
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    log.warn("Failed to create shared directory: ${dir.path}")
                    _authorizationState.value = StorageAuthorizationState.Unauthorized(
                        "Cannot create shared directory at ${dir.path}"
                    )
                    return@withContext false
                }
            }

            // 3. Perform real filesystem read/write probe
            val probeFile = File(dir, ".linuxdroid_probe")
            probeFile.writeText("OK")
            val readBack = probeFile.readText()
            probeFile.delete()

            if (readBack == "OK") {
                log.info("Shared storage fully authorized and verified at: ${dir.path}")
                _authorizationState.value = StorageAuthorizationState.Authorized(dir)
                true
            } else {
                _authorizationState.value = StorageAuthorizationState.Unauthorized("Filesystem probe failed")
                false
            }
        } catch (e: Exception) {
            log.error("Failed to verify storage access", e)
            _authorizationState.value = StorageAuthorizationState.Error(e.message ?: "Unknown storage verification error")
            false
        }
    }

    /**
     * Creates an intent to navigate the user directly to the system settings page
     * where storage permissions can be granted.
     */
    fun createPermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
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
                "Storage access not yet verified. Tap 'Verify Access' in Settings."
            )
        }
    }

    /**
     * Updates state to reflect authorization revocation.
     * Linux rootfs environment remains intact.
     */
    fun handleRevocation() {
        log.warn("Storage authorization revoked. Linux environment remains intact.")
        _authorizationState.value = StorageAuthorizationState.Unauthorized(
            "Authorization revoked by user or system"
        )
    }

    /** Returns true if storage is currently authorized. */
    fun isAuthorized(): Boolean = _authorizationState.value is StorageAuthorizationState.Authorized

    private val prefs by lazy {
        context.getSharedPreferences("linuxdroid_storage_prefs", Context.MODE_PRIVATE)
    }

    /** Returns true if user has already been shown the first-time storage setup popup. */
    fun hasPromptedStorageAccess(): Boolean {
        return prefs.getBoolean("has_prompted_storage_access", false)
    }

    /** Records that user has been shown the first-time storage setup popup. */
    fun setPromptedStorageAccess(prompted: Boolean) {
        prefs.edit().putBoolean("has_prompted_storage_access", prompted).apply()
    }
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
