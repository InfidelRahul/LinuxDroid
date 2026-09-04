package com.linuxdroid.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.linuxdroid.app.R
import com.linuxdroid.app.ui.MainActivity
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.runtime.RuntimeBackend
import com.linuxdroid.core.session.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the Linux session alive independent of
 * the Android Activity lifecycle.
 *
 * KEY LIFECYCLE RULE:
 * Android Activity destroyed != Linux environment destroyed
 * This service keeps running after the Activity is closed.
 *
 * The service lifecycle:
 * 1. Started via startForegroundService() when a session begins
 * 2. Keeps the CPU alive via wake lock (managed by session)
 * 3. Stopped when all sessions are stopped
 */
@AndroidEntryPoint
class LinuxSessionService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.linuxdroid.app.ACTION_STOP_SESSION"
        const val EXTRA_ENVIRONMENT_ID = "com.linuxdroid.app.EXTRA_ENVIRONMENT_ID"

        fun start(context: Context, sessionName: String = "Linux Session", environmentId: String? = null) {
            val intent = Intent(context, LinuxSessionService::class.java).apply {
                if (environmentId != null) putExtra(EXTRA_ENVIRONMENT_ID, environmentId)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, environmentId: String? = null) {
            val intent = Intent(context, LinuxSessionService::class.java).apply {
                action = ACTION_STOP
                if (environmentId != null) putExtra(EXTRA_ENVIRONMENT_ID, environmentId)
            }
            context.startService(intent)
        }
    }

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var dao: EnvironmentDao

    @Inject
    lateinit var runtimeBackend: RuntimeBackend

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Timber.i("LinuxSessionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.i("Stop action received in LinuxSessionService")
            val targetEnvId = intent.getStringExtra(EXTRA_ENVIRONMENT_ID)
            serviceScope.launch {
                try {
                    val active = sessionManager.sessions.first()
                    if (targetEnvId != null) {
                        val sessionToStop = active.values.firstOrNull { it.environmentId.value == targetEnvId }
                        if (sessionToStop != null) {
                            sessionManager.stopSession(sessionToStop.id)
                        }
                    } else {
                        for (session in active.values) {
                            try {
                                sessionManager.stopSession(session.id)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to stop session ${session.id}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error handling stop command in LinuxSessionService")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        Timber.i("LinuxSessionService started in foreground")

        // Reconcile state on service startup / restart
        reconcileSession()

        return START_STICKY
    }

    private fun reconcileSession() {
        serviceScope.launch {
            try {
                val activeSessions = sessionManager.sessions.first()
                val environments = dao.getAll()
                var hasActiveEnvironment = false

                for (envEntity in environments) {
                    val isSessionActive = activeSessions.values.any { it.environmentId.value == envEntity.id && it.state.isActive() }
                    if (envEntity.state == EnvironmentState.RUNNING.name && !isSessionActive) {
                        Timber.w("Reconciling orphaned RUNNING environment ${envEntity.id} -> STOPPED")
                        dao.updateState(
                            id = envEntity.id,
                            state = EnvironmentState.STOPPED.name,
                            timestamp = System.currentTimeMillis(),
                            failureMessage = null,
                        )
                    } else if (isSessionActive || envEntity.state == EnvironmentState.RUNNING.name) {
                        hasActiveEnvironment = true
                    }
                }

                // If no environments are active after reconciliation, safely stop the service
                if (!hasActiveEnvironment && activeSessions.isEmpty()) {
                    Timber.i("No active environments found during reconciliation; stopping service")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: Exception) {
                Timber.e(e, "Session reconciliation error")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("LinuxSessionService destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LinuxSessionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, getString(R.string.session_notification_channel_id))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_session_running))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.action_stop_session),
                stopIntent,
            )
            .build()
    }
}
