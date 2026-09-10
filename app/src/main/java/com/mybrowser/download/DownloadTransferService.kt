package com.mybrowser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.mybrowser.App
import com.mybrowser.MainActivity
import com.mybrowser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps app-managed segmented transfers alive while Pure 浏览器 is in the background. */
class DownloadTransferService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        updateNotificationChannel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannel()
        val active = (application as App).downloadHandler.activeTransfers.value
        if (active.isNotEmpty()) notificationManager.notify(NOTIFICATION_ID, createNotification(active))
    }

    private fun updateNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val handler = (application as App).downloadHandler
        startForeground(
            NOTIFICATION_ID,
            createNotification(handler.activeTransfers.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (monitorJob == null) {
            monitorJob = serviceScope.launch {
                handler.activeTransfers.collectLatest { active ->
                    if (active.isEmpty()) {
                        // Avoid stop/start churn when one download finishes immediately before
                        // another WebView callback enqueues the next file.
                        delay(STOP_GRACE_PERIOD_MS)
                        if (handler.activeTransfers.value.isEmpty()) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } else {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            createNotification(active),
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as App).downloadHandler.pauseActiveTransfers()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotification(active: List<DownloadItem>): Notification {
        val primary = active.firstOrNull()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (active.size) {
            0 -> getString(R.string.download_notification_preparing)
            1 -> primary?.filename.orEmpty()
            else -> getString(R.string.download_notification_multiple, active.size)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                primary?.let {
                    getString(
                        R.string.download_notification_progress,
                        it.progress,
                        it.threadCount,
                    )
                } ?: getString(R.string.download_notification_preparing),
            )
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, primary?.progress ?: 0, primary?.totalBytes?.let { it <= 0L } ?: true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "browser_downloads"
        const val NOTIFICATION_ID = 0xD011
        const val STOP_GRACE_PERIOD_MS = 400L
    }
}
