package com.mybrowser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat
import com.mybrowser.R
import java.lang.ref.WeakReference

/** One system media owner across all windows. Pages and decoders remain in WebView. */
class BrowserMediaSession(private val context: Context) {
    interface Owner {
        fun setPlaying(playing: Boolean)
        fun seek(positionMs: Long)
        fun openIntent(): Intent
    }
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var owner = WeakReference<Owner>(null)
    private var signal = MediaPlaybackTracker.Signal()
    private var title = ""
    private var isPrivate = false
    private var backgroundAllowed = false
    private var serviceRequested = false
    private var session: MediaSession? = null
    // Chromium's AudioFocusDelegate already requests focus for the decoder. A second
    // app request steals focus from that delegate (even in the same UID) and pauses it.
    // System focus loss reaches the existing delegate; probe signals update this session.

    init {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.media_notification_channel), NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) owner.get()?.setPlaying(false)
            }
        }, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun update(source: Owner, value: MediaPlaybackTracker.Signal, name: String,
        incognito: Boolean, allowBackground: Boolean) {
        if (owner.get() !== source) {
            if (!value.isPlaying) return
            owner.get()?.setPlaying(false)
            owner = WeakReference(source)
        }
        signal = value; title = if (incognito) "" else name.take(256)
        isPrivate = incognito; backgroundAllowed = allowBackground && !incognito
        if (!value.hasMedia) { detach(source); return }
        if (!isPrivate) {
            val active = session ?: MediaSession(context, "PureBrowser").also { created ->
                created.setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = command(ACTION_PLAY)
                    override fun onPause() = command(ACTION_PAUSE)
                    override fun onStop() = command(ACTION_STOP)
                    override fun onSeekTo(pos: Long) { owner.get()?.seek(pos) }
                })
                session = created
            }
            active.isActive = true
            active.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (value.duration * 1000).toLong()).build())
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE
            if (value.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
            active.setPlaybackState(PlaybackState.Builder().setActions(actions)
                .setState(if (value.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    (value.position * 1000).toLong(), if (value.isPlaying) value.playbackRate ?: 1f else 0f).build())
            active.setSessionActivity(openPage())
        } else session?.isActive = false
        if (backgroundAllowed && value.isPlaying) ensureService()
        else if (serviceRequested) stopService()
        if (serviceRequested) notifications.notify(NOTIFICATION_ID, notification())
    }

    fun detach(source: Owner) {
        if (owner.get() !== source) return
        owner.clear(); signal = MediaPlaybackTracker.Signal(); session?.isActive = false
        stopService()
    }

    private fun openPage(): PendingIntent = PendingIntent.getActivity(context, 70,
        owner.get()?.openIntent() ?: Intent(context, com.mybrowser.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(name: String): PendingIntent = PendingIntent.getForegroundService(context, name.hashCode(),
        Intent(context, MediaPlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun command(action: String?, fromService: Boolean = false) {
        if (fromService) serviceRequested = true
        val target = owner.get() ?: run { stopService(); return }
        // A notification tap can arrive after navigation, a private-mode switch or
        // disabling background playback. Never revive that stale service request.
        if (isPrivate || (fromService && !backgroundAllowed)) { stopService(); return }
        when (action) {
            ACTION_PLAY -> {
                if (backgroundAllowed && !fromService) {
                    serviceRequested = runCatching {
                        ContextCompat.startForegroundService(context,
                            Intent(context, MediaPlaybackService::class.java).setAction(ACTION_PLAY)); true
                    }.getOrDefault(false)
                } else target.setPlaying(true)
            }
            ACTION_PAUSE -> { target.setPlaying(false); stopService() }
            ACTION_STOP -> { target.setPlaying(false); detach(target) }
            null -> if (!signal.isPlaying) stopService()
        }
    }

    private fun ensureService() {
        if (serviceRequested) return
        serviceRequested = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MediaPlaybackService::class.java)); true
        }.getOrDefault(false)
    }
    private fun stopService() {
        serviceRequested = false
        context.stopService(Intent(context, MediaPlaybackService::class.java))
        notifications.cancel(NOTIFICATION_ID)
    }
    fun serviceStopped() { serviceRequested = false }
    fun taskRemoved(rootIntent: Intent?) {
        val current = owner.get() ?: run { stopService(); return }
        if (rootIntent?.component == current.openIntent().component) command(ACTION_STOP)
    }
    fun notification(): Notification {
        val paused = !signal.isPlaying
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) }).setContentIntent(openPage())
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(!paused)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context,
                if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                context.getString(if (paused) R.string.ui_play_video else R.string.ui_pause_video), action(if (paused) ACTION_PLAY else ACTION_PAUSE)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_close),
                context.getString(R.string.ui_close), action(ACTION_STOP)).build())
        session?.let { builder.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1)) }
        return builder.build()
    }
    companion object {
        const val NOTIFICATION_ID = 2002
        private const val CHANNEL = "web_media"
        const val ACTION_PLAY = "com.mybrowser.media.PLAY"
        const val ACTION_PAUSE = "com.mybrowser.media.PAUSE"
        const val ACTION_STOP = "com.mybrowser.media.STOP"
    }
}
