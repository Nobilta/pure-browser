package com.mybrowser.media

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.mybrowser.App

/** Keeps a page the user explicitly chose to keep playing audible in the background. */
class MediaPlaybackService : Service() {
    private val media get() = (application as App).mediaSession
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A new foreground request can reach an existing instance while its prior
        // stop is queued. Acknowledge every start before processing any command.
        val action = intent?.action
        startForeground(BrowserMediaSession.NOTIFICATION_ID, media.notification(withControls = action != ACTION_FINISH))
        if (action == ACTION_FINISH) stopSelfResult(startId)
        else media.command(action?.substringBefore(BrowserMediaSession.ACTION_TOKEN_SEPARATOR),
            fromService = true, ownerToken = intent?.getLongExtra(EXTRA_OWNER_TOKEN, 0L) ?: 0L)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        media.taskRemoved(rootIntent)
    }
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        media.serviceStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_FINISH = "com.mybrowser.media.FINISH_SERVICE"
        internal const val EXTRA_OWNER_TOKEN = "com.mybrowser.media.OWNER_TOKEN"
    }
}
