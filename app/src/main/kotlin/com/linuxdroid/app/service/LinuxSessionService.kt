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
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

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

        fun start(context: Context, sessionName: String = "Linux Session") {
            val intent = Intent(context, LinuxSessionService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LinuxSessionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("LinuxSessionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.i("Stop action received")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        Timber.i("LinuxSessionService started in foreground")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("LinuxSessionService destroyed")
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
