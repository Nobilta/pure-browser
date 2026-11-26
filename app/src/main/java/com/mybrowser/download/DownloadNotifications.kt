package com.mybrowser.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.mybrowser.MainActivity
import com.mybrowser.R

internal object DownloadNotifications {
    private const val TAG = "completed_download"

    fun completed(context: Context, item: DownloadItem) = show(context, item, R.string.download_complete_notification,
        android.R.drawable.stat_sys_download_done)

    fun needsLink(context: Context, item: DownloadItem) = show(context, item, R.string.download_link_attention,
        android.R.drawable.stat_notify_error)

    private fun show(context: Context, item: DownloadItem, message: Int, icon: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java)
            .setAction(DownloadTransferService.ACTION_SHOW_DOWNLOAD)
            .setData("pure-download:${item.id}".toUri())
            .putExtra(DownloadTransferService.EXTRA_DOWNLOAD_ID, item.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(TAG, item.id.hashCode(), Notification.Builder(context, DownloadTransferService.CHANNEL_ID)
                .setSmallIcon(icon)
                // A private task still reports completion; it just never names the file, because
                // the notification outlives the session that caused it.
                .setContentTitle(if (item.showsFilenameInNotification) item.filename else context.getString(R.string.app_name))
                .setContentText(context.getString(message))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }

    fun dismiss(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG, id.hashCode())
    }
}
