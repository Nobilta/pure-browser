package com.mybrowser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val handler = (application as App).downloadHandler
        startForeground(
            NOTIFICATION_ID,
            createNotification(handler.activeTransfers.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        intent?.getLongExtra(EXTRA_DOWNLOAD_ID, 0L)?.takeIf { it != 0L }?.let { id ->
            when (intent.action) { ACTION_PAUSE -> handler.pause(id); ACTION_CANCEL -> handler.cancel(id) }
        }
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
            action = ACTION_SHOW_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_ID, primary?.id ?: 0L)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (active.size) {
            0 -> getString(R.string.download_notification_preparing)
            // A private session's running download shows a generic title: the
            // filename belongs to that session and must not leak to the lock screen.
            1 -> if (primary?.showsFilenameInNotification == false) getString(R.string.download_notification_private)
                else primary?.filename.orEmpty()
            else -> getString(R.string.download_notification_multiple, active.size)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(
                primary?.let {
                    if (it.status != DownloadStatus.DOWNLOADING) getString(when (it.status) {
                        DownloadStatus.SAVING -> R.string.download_saving
                        DownloadStatus.WAITING_NETWORK -> R.string.download_waiting_network
                        else -> R.string.download_queued
                    }) else if (it.totalBytes <= 0L) getString(
                        R.string.download_progress_unknown,
                        android.text.format.Formatter.formatShortFileSize(this, it.bytesDownloaded),
                        it.threadCount,
                    ) else
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
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(100, if (primary?.status == DownloadStatus.SAVING) primary.savingProgress else primary?.progress ?: 0,
                primary == null || primary.status == DownloadStatus.QUEUED || primary.status == DownloadStatus.WAITING_NETWORK ||
                    (primary.status == DownloadStatus.DOWNLOADING && primary.totalBytes <= 0L))
        if (primary != null) {
            fun actionIntent(action: String): PendingIntent = PendingIntent.getService(this, (primary.id xor action.hashCode().toLong()).toInt(),
                Intent(this, DownloadTransferService::class.java).setAction(action).putExtra(EXTRA_DOWNLOAD_ID, primary.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.download_pause), actionIntent(ACTION_PAUSE)).build())
            builder.addAction(Notification.Action.Builder(null, getString(R.string.ui_cancel_download), actionIntent(ACTION_CANCEL)).build())
        }
        return builder.build()
    }

    companion object {
        const val ACTION_SHOW_DOWNLOAD = "com.mybrowser.SHOW_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val ACTION_PAUSE = "com.mybrowser.PAUSE_DOWNLOAD"
        private const val ACTION_CANCEL = "com.mybrowser.CANCEL_DOWNLOAD"
        const val CHANNEL_ID = "browser_downloads"
        const val NOTIFICATION_ID = 0xD011
        const val STOP_GRACE_PERIOD_MS = 400L

        /**
         * Creates the downloads channel without starting the service; the settings
         * notification guide needs the channel to exist before it can link to its page.
         * Re-creating an existing channel never resets the user's choices.
         */
        fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.download_notification_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }
}
