package com.mybrowser.media

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mybrowser.App

/** Keeps only the explicitly enabled, currently playing normal page alive in background. */
class MediaPlaybackService : Service() {
    private val media get() = (application as App).mediaSession
    override fun onCreate() {
        super.onCreate()
        if ((application as App).restoreBlocked) { stopSelf(); return }
        startForeground(BrowserMediaSession.NOTIFICATION_ID, media.notification())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!(application as App).restoreBlocked) media.command(intent?.action, fromService = true)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!(application as App).restoreBlocked) media.taskRemoved(rootIntent)
    }
    override fun onDestroy() {
        if (!(application as App).restoreBlocked) media.serviceStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
