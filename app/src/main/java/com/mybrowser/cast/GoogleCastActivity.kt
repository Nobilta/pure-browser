package com.mybrowser.cast

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.mybrowser.App
import com.mybrowser.R
import java.util.concurrent.Executors

/** Optional Google Play services path. Loading starts only after an explicit Send action. */
class GoogleCastActivity : AppCompatActivity() {
    private var cast: CastContext? = null
    private var client: RemoteMediaClient? = null
    private var source: CastMediaSource? = null
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var send: Button
    private lateinit var seek: SeekBar
    private val controls = mutableListOf<Button>()
    private val executor = Executors.newSingleThreadExecutor()
    private val session get() = cast?.sessionManager?.currentCastSession
    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { updateStatus() }
        override fun onMetadataUpdated() { updateStatus() }
    }
    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) { message(R.string.google_cast_connecting) }
        override fun onSessionStarted(s: CastSession, id: String) = connected()
        override fun onSessionStartFailed(s: CastSession, error: Int) { connected(); message(R.string.google_cast_failed) }
        override fun onSessionEnding(s: CastSession) = Unit
        override fun onSessionEnded(s: CastSession, error: Int) { connected(); message(R.string.google_cast_disconnected) }
        override fun onSessionResuming(s: CastSession, id: String) { message(R.string.google_cast_connecting) }
        override fun onSessionResumed(s: CastSession, suspended: Boolean) = connected()
        override fun onSessionResumeFailed(s: CastSession, error: Int) { connected(); message(R.string.google_cast_failed) }
        override fun onSessionSuspended(s: CastSession, reason: Int) { connected(); message(R.string.google_cast_disconnected) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((application as App).restoreBlocked) { finish(); return }
        source = CastMediaSource.parse(intent.getStringExtra("url").orEmpty(), intent.getStringExtra("kind").orEmpty())
        val padding = (16 * resources.displayMetrics.density).toInt()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        val scroll = ScrollView(this).apply { addView(content) }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        addText(getString(R.string.google_cast), 24f)
        intent.getStringExtra("title")?.takeIf { it.isNotBlank() }?.let { addText(it.take(256), 20f) }
        addText(getString(R.string.google_cast_summary))
        status = addText(getString(R.string.google_cast_connecting))
        send = button(R.string.google_cast_send) { loadSelected() }.apply { isEnabled = false }
        controls += button(R.string.ui_play_video) { control { play() } }
        controls += button(R.string.ui_pause_video) { control { pause() } }
        controls += button(R.string.cd_stop) { control { stop() } }
        controls.forEach { it.isEnabled = false }
        seek = SeekBar(this).apply {
            max = 1000; contentDescription = getString(R.string.ui_video_progress); isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) {
                    val target = client ?: return
                    if (target.streamDuration > 0) control { seek(target.streamDuration * bar.progress / 1000) }
                }
            })
        }; content.addView(seek)
        button(R.string.google_cast_disconnect) { cast?.sessionManager?.endCurrentSession(true) }
        button(R.string.ui_close) { finish() }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            message(R.string.google_cast_unavailable); return
        }
        runCatching { CastContext.getSharedInstance(this, executor).addOnSuccessListener(this) { value ->
            if (isFinishing || isDestroyed) return@addOnSuccessListener
            cast = value
            value.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
            val route = MediaRouteButton(this).apply { contentDescription = getString(R.string.google_cast_choose_device) }
            content.addView(route, 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * resources.displayMetrics.density).toInt()))
            CastButtonFactory.setUpMediaRouteButton(this, route)
            connected()
        }.addOnFailureListener(this) { message(R.string.google_cast_unavailable) }
        }.onFailure { message(R.string.google_cast_unavailable) }
    }
    private fun addText(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setPadding(0, 12, 0, 12); content.addView(this)
    }
    private fun button(label: Int, action: () -> Unit) = Button(this).apply { setText(label); setOnClickListener { action() }; content.addView(this) }
    private fun message(label: Int) { if (::status.isInitialized) status.setText(label) }
    private fun connected() {
        client?.unregisterCallback(callback)
        client = session?.takeIf { it.isConnected }?.remoteMediaClient
        client?.registerCallback(callback)
        send.isEnabled = source != null && client != null
        controls.forEach { it.isEnabled = client != null }
        seek.isEnabled = false
        message(if (client != null) R.string.google_cast_ready else R.string.google_cast_choose_device)
        updateStatus()
    }
    private fun loadSelected() {
        val selected = source ?: return
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, intent.getStringExtra("title").orEmpty().take(256))
        }
        val info = MediaInfo.Builder(selected.url).setContentType(selected.mime)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).build()
        control { load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build()) }
    }
    private fun control(action: RemoteMediaClient.() -> com.google.android.gms.common.api.PendingResult<RemoteMediaClient.MediaChannelResult>) {
        val remote = client ?: return
        runCatching { remote.action().setResultCallback { result ->
            if (!isDestroyed && !isFinishing) message(if (result.status.isSuccess) R.string.google_cast_accepted else R.string.google_cast_failed)
        } }.onFailure { message(R.string.google_cast_failed) }
    }
    private fun updateStatus() {
        val remote = client ?: return
        val media = remote.mediaStatus ?: return
        val label = when (media.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> R.string.google_cast_playing
            MediaStatus.PLAYER_STATE_PAUSED -> R.string.google_cast_paused
            MediaStatus.PLAYER_STATE_BUFFERING -> R.string.google_cast_connecting
            else -> if (media.idleReason == MediaStatus.IDLE_REASON_ERROR) R.string.google_cast_failed else R.string.google_cast_ready
        }
        message(label)
        seek.isEnabled = remote.streamDuration > 0 && media.mediaInfo?.streamType != MediaInfo.STREAM_TYPE_LIVE
        if (seek.isEnabled) seek.progress = (remote.approximateStreamPosition * 1000 / remote.streamDuration).toInt().coerceIn(0, 1000)
    }
    override fun onDestroy() {
        client?.unregisterCallback(callback)
        cast?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        executor.shutdownNow()
        super.onDestroy()
    }
}
