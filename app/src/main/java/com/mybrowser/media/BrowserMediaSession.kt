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

/** One system media owner for the active page. Pages and decoders remain in WebView. */
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
    private var backgroundAllowed = false
    private var serviceRequested = false
    /** Keep the stopped owner until its asynchronous pause reaches the media probe. */
    private var awaitingStop = false
    /** Identifies the document the current controls belong to; stale intents are ignored. */
    private var ownerToken = 0L
    /** PendingIntents minted for [ownerToken]; the previous set is cancelled when it changes. */
    private val actionIntents = mutableMapOf<String, PendingIntent>()
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
            releaseSession()
            owner = WeakReference(source)
            ownerToken++
            awaitingStop = false
        }
        val sameMedia = signal.identity == value.identity && signal.sourceUrl == value.sourceUrl
        if (!value.hasMedia || !value.playbackAvailable || (!value.isPlaying && !sameMedia)) {
            detach(source)
            return
        }
        if (awaitingStop) {
            if (!value.isPlaying) { detach(source); return }
            if (sameMedia) return
            awaitingStop = false
        }
        signal = value; title = if (incognito) "" else name.take(256)
        backgroundAllowed = allowBackground
        // A private page gets the same controls; its metadata stays generic so the lock
        // screen never names the page, its address or its site.
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
        active.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, metadataTitle())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (value.duration * 1000).toLong()).build())
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE
        if (value.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        active.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (value.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                (value.position * 1000).toLong(), if (value.isPlaying) value.playbackRate ?: 1f else 0f).build())
        active.setSessionActivity(openPage())
        if (backgroundAllowed && value.isPlaying) ensureService()
        else if (serviceRequested) stopService()
        if (serviceRequested) notifications.notify(NOTIFICATION_ID, notification())
    }

    fun detach(source: Owner) {
        if (owner.get() !== source) return
        owner.clear()
        signal = MediaPlaybackTracker.Signal()
        title = ""
        backgroundAllowed = false
        awaitingStop = false
        ownerToken++
        releaseSession()
        stopService()
    }

    private fun releaseSession() {
        actionIntents.values.forEach { it.cancel() }
        actionIntents.clear()
        val previous = session ?: return
        session = null
        previous.setCallback(null)
        previous.setPlaybackState(PlaybackState.Builder()
            .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f).build())
        previous.setMetadata(null)
        previous.isActive = false
        // Inactive alone leaves Android holding a live token and stale PLAYING state.
        previous.release()
    }

    private fun metadataTitle(): String = title.ifBlank { context.getString(R.string.app_name) }

    private fun openPage(): PendingIntent = PendingIntent.getActivity(context, 70,
        owner.get()?.openIntent() ?: Intent(context, com.mybrowser.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(name: String): PendingIntent {
        return actionIntents.getOrPut(name) {
            PendingIntent.getForegroundService(context, name.hashCode(),
                Intent(context, MediaPlaybackService::class.java)
                    .setAction(name + ACTION_TOKEN_SEPARATOR + ownerToken)
                    .putExtra(MediaPlaybackService.EXTRA_OWNER_TOKEN, ownerToken),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    fun command(action: String?, fromService: Boolean = false, ownerToken: Long = 0L) {
        if (fromService) serviceRequested = true
        // A control can arrive after navigation, a mode switch or a session end. The token
        // names the document the user was controlling; a stale one is ignored and must not
        // stop the playback that replaced it. If nothing owns media any more, the service this
        // intent just started would otherwise keep a notification alive for nobody.
        if ((fromService && action != null && ownerToken == 0L) ||
            (ownerToken != 0L && ownerToken != this.ownerToken)) {
            if (fromService && !(backgroundAllowed && signal.isPlaying)) {
                stopService()
            }
            return
        }
        val target = owner.get()?.takeUnless { awaitingStop } ?: run { stopService(); return }
        if (fromService && !backgroundAllowed) { stopService(); return }
        when (action) {
            ACTION_PLAY -> {
                if (backgroundAllowed && !fromService) {
                    serviceRequested = runCatching {
                        ContextCompat.startForegroundService(context,
                            Intent(context, MediaPlaybackService::class.java).setAction(ACTION_PLAY)
                                .putExtra(MediaPlaybackService.EXTRA_OWNER_TOKEN, this.ownerToken)); true
                    }.getOrDefault(false)
                    if (!serviceRequested) target.setPlaying(true)
                } else target.setPlaying(true)
            }
            ACTION_PAUSE -> { target.setPlaying(false); stopService() }
            ACTION_STOP -> {
                // Releasing the owner immediately lets an in-flight PLAYING report
                // acquire a new session before WebView acknowledges the pause.
                awaitingStop = true
                signal = signal.copy(isPlaying = false)
                this.ownerToken++
                releaseSession()
                stopService()
                target.setPlaying(false)
            }
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
        val requested = serviceRequested
        serviceRequested = false
        if (requested) {
            // stopService can cancel a queued foreground start before the service
            // can acknowledge it, which Android treats as a fatal startup failure.
            // Deliver shutdown in start order; stopSelfResult keeps newer starts alive.
            context.startService(Intent(context, MediaPlaybackService::class.java)
                .setAction(MediaPlaybackService.ACTION_FINISH))
        }
        notifications.cancel(NOTIFICATION_ID)
    }
    fun serviceStopped() { serviceRequested = false }
    fun taskRemoved(rootIntent: Intent?) {
        val current = owner.get() ?: run { stopService(); return }
        if (rootIntent?.component == current.openIntent().component) command(ACTION_STOP)
    }
    fun notification(withControls: Boolean = true): Notification {
        val paused = !signal.isPlaying
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_play)
            .setContentTitle(metadataTitle()).setContentIntent(openPage())
            .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(!paused)
        if (withControls) builder.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context,
                if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                context.getString(if (paused) R.string.ui_play_video else R.string.ui_pause_video), action(if (paused) ACTION_PLAY else ACTION_PAUSE)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_close),
                context.getString(R.string.ui_close), action(ACTION_STOP)).build())
        session?.let {
            val style = Notification.MediaStyle().setMediaSession(it.sessionToken)
            if (withControls) style.setShowActionsInCompactView(0, 1)
            builder.setStyle(style)
        }
        return builder.build()
    }
    companion object {
        const val NOTIFICATION_ID = 2002
        internal const val ACTION_TOKEN_SEPARATOR = '@'
        private const val CHANNEL = "web_media"
        const val ACTION_PLAY = "com.mybrowser.media.PLAY"
        const val ACTION_PAUSE = "com.mybrowser.media.PAUSE"
        const val ACTION_STOP = "com.mybrowser.media.STOP"
    }
}
