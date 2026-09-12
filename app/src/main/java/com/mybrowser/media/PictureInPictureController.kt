package com.mybrowser.media

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.graphics.Rect
import android.util.Rational
import androidx.core.content.ContextCompat
import com.mybrowser.R
import java.util.UUID

/** PiP contains Chromium's existing fullscreen video, preserving cookies and decoding. */
class PictureInPictureController(private val activity: Activity,
    private val tracker: () -> MediaPlaybackTracker?, private val allowed: () -> Boolean,
    private val fullscreen: () -> FullscreenVideoView?, private val automatic: () -> Boolean) {
    private val action = activity.packageName + ".pip." + UUID.randomUUID()
    private var sourceRectHint: Rect? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == action && activity.isInPictureInPictureMode) tracker()?.togglePlayback()
        }
    }
    private val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    val isAvailable get() = supported && allowed()
    init { ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED) }
    fun enter(): Boolean {
        val state = tracker()?.current
        if (!isAvailable || fullscreen() == null || state?.hasVideo != true || !state.playbackAvailable) return false
        return runCatching { activity.enterPictureInPictureMode(parameters()) }.getOrDefault(false)
    }
    fun update() {
        if (supported) runCatching { activity.setPictureInPictureParams(parameters()) }
    }
    fun modeChanged(active: Boolean) { fullscreen()?.setPictureInPicture(active) }
    private fun parameters(): PictureInPictureParams {
        val state = tracker()?.current ?: MediaPlaybackTracker.Signal()
        val ratio = if (state.width > 0 && state.height > 0) (state.width.toDouble() / state.height).coerceIn(.42, 2.38) else 16.0 / 9
        val label = activity.getString(if (state.isPlaying) R.string.ui_pause_video else R.string.ui_play_video)
        val control = RemoteAction(Icon.createWithResource(activity, if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            label, label, PendingIntent.getBroadcast(activity, 0, Intent(action).setPackage(activity.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setActions(if (state.hasVideo && state.playbackAvailable) listOf(control) else emptyList())
        val host = fullscreen()
        if (host == null && !activity.isInPictureInPictureMode) sourceRectHint = null
        else if (host != null && !activity.isInPictureInPictureMode && host.hasWindowFocus() && host.width > 0 && host.height > 0) {
            val location = IntArray(2)
            host.getLocationInWindow(location)
            val rect = Rect(location[0], location[1], location[0] + host.width, location[1] + host.height)
            if (rect.intersect(0, 0, activity.window.decorView.width, activity.window.decorView.height)) {
                sourceRectHint = rect
            }
        }
        // The hint describes the original Activity content, not the floating
        // window's screen position. Updating global coordinates from each media
        // pulse in PiP feeds the window offset back into its next transition.
        sourceRectHint?.let { builder.setSourceRectHint(Rect(it)) }
        if (android.os.Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(
            isAvailable && automatic() && fullscreen() != null && state.hasVideo && state.isPlaying)
        return builder.build()
    }
    fun close() { activity.unregisterReceiver(receiver) }
}
