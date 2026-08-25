package com.linuxdroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for LinuxDroid.
 *
 * Responsibilities:
 * - Initialize Timber logging
 * - Create notification channels
 * - Hilt injection root
 */
@HiltAndroidApp
class LinuxDroidApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initializeLogging()
        createNotificationChannels()
    }

    private fun initializeLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In release, plant a production tree that only logs warnings+
            Timber.plant(ReleaseTree())
        }
        Timber.i("LinuxDroid starting up (version ${BuildConfig.VERSION_NAME})")
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val sessionChannel = NotificationChannel(
            getString(R.string.session_notification_channel_id),
            getString(R.string.session_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.session_notification_channel_desc)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(sessionChannel))
        Timber.d("Notification channels created")
    }
}

/**
 * Production logging tree: logs WARN+ to logcat only.
 * Does not include sensitive user data.
 */
private class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < android.util.Log.WARN) return
        android.util.Log.println(priority, tag ?: "LinuxDroid", message)
    }
}
