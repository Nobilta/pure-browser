package com.mybrowser.media

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mybrowser.R
import com.mybrowser.core.PlaybackSpeed
import com.mybrowser.data.VideoPreferences
import com.mybrowser.ui.theme.MyBrowserTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** Chromium keeps its video surface; controls and menus share a separate, non-resizing overlay. */
@SuppressLint("ViewConstructor")
class FullscreenVideoView(
    private val activity: Activity,
    private val videoView: View,
    private val preferences: VideoPreferences,
    enhancedPlayback: Boolean,
    private val tracker: MediaPlaybackTracker,
    private val titleProvider: () -> String,
    private val canCast: () -> Boolean,
    private val onExit: () -> Unit,
    private val onChooseSpeed: (Float) -> Unit,
    private val castContent: @Composable () -> Unit,
    private val onPictureInPicture: (() -> Unit)? = null,
) : FrameLayout(activity) {
    private val ui = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val accessibility = activity.getSystemService(AccessibilityManager::class.java)
    private val originalBrightness = activity.window.attributes.screenBrightness
    private val originalOrientation = activity.requestedOrientation
    private val originalVolumeStream = activity.volumeControlStream
    private val keptScreenOn = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    // A cached fullscreen flag from the previous session cannot start a new handoff.
    private var state by mutableStateOf(tracker.current.copy(isFullscreen = false))
    private var title by mutableStateOf("")
    private var released = false
    private var enhanced by mutableStateOf(false)
    private var wantEnhanced = enhancedPlayback
    private var connecting = false
    private var controlIdentity: String? = null
    private var controlGeneration = 0
    private var controlsVisible by mutableStateOf(true)
    private var pictureInPicture = false
    private var platformBack: android.window.OnBackInvokedCallback? = null
    private var locked by mutableStateOf(false)
    private var orientationChosen = false
    private var seeking by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var menu by mutableStateOf<PlayerMenu?>(null)
    private var hudMessage by mutableStateOf<String?>(null)
    private val controlsHost = ComposeView(activity)
    private val gestures = GestureSurface(activity)
    private val hideControls = Runnable { if (!locked && !seeking && menu == null) showControls(false) }
    private val hideHud = Runnable { hudMessage = null }
    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            if (hasWindowFocus() && !tracker.isInstalled) tracker.probe()
            ui.postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusableInTouchMode = true
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        addView(videoView, LayoutParams(-1, -1))
        addView(gestures, LayoutParams(-1, -1))
        gestures.visibility = GONE
        addView(controlsHost, LayoutParams(-1, -1))
        controlsHost.setContent {
            MyBrowserTheme(darkTheme = true) {
                val position = if (seeking) seekPosition(progress) else state.position
                PlayerControls(
                    state = PlayerControlsState(
                        visible = enhanced && controlsVisible,
                        locked = enhanced && locked,
                        title = title,
                        playing = state.isPlaying,
                        hasVideo = state.hasVideo,
                        canSeek = state.canSeek,
                        progress = progress,
                        position = if (state.duration > 0) progressText(position, state.duration)
                            else if (state.hasVideo) activity.getString(R.string.ui_live, VideoGestureMath.time(position))
                            else activity.getString(R.string.ui_waiting_for_webpage_video),
                        rate = state.playbackRate ?: 1f,
                        canCast = canCast(),
                        menu = menu,
                        hud = hudMessage,
                    ),
                    onPlayPause = ::togglePlayback,
                    onSeek = {
                        if (!seeking) { seeking = true; ui.removeCallbacks(hideControls); gestures.cancelGesture() }
                        progress = it
                    },
                    onSeekFinished = {
                        seeking = false
                        seekTo(seekPosition(progress))
                        scheduleHide()
                    },
                    onLock = { changeLock(!locked) },
                    onExit = onExit,
                    onRotate = ::rotate,
                    onPictureInPicture = onPictureInPicture,
                    onMenu = ::changeMenu,
                    onSpeed = { rate -> changeMenu(null); onChooseSpeed(rate) },
                    castContent = castContent,
                )
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // Insets belong to controls, never to the decoder's surface. Even transient
            // system bars must not resize or translate the underlying video.
            controlsHost.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        refreshMode()
        update(state)
        tracker.probe()
        if (!tracker.isInstalled) ui.post(poll)
    }

    fun update(signal: MediaPlaybackTracker.Signal) {
        if (released) return
        state = signal
        title = titleProvider().ifBlank { activity.getString(R.string.ui_video_playback) }
        if (!orientationChosen && preferences.landscapeFullscreen && state.isFullscreen && state.width > state.height && state.height > 0) {
            orientationChosen = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if ((enhanced || connecting) && (!state.canUseEnhancedControls || controlIdentity != state.identity)) {
            disconnectControls()
        }
        refreshMode()
        if (wantEnhanced && !enhanced && !connecting && state.canUseEnhancedControls) connectControls()
        if (!seeking) progress = if (state.canSeek)
            ((state.position - state.seekStart) / (state.seekEnd - state.seekStart)).toFloat().coerceIn(0f, 1f) else 0f
    }

    private fun connectControls() {
        if (!state.canUseEnhancedControls) return
        connecting = true
        val generation = ++controlGeneration
        val identity = state.identity
        controlIdentity = identity
        tracker.setFullscreenControls(true) { ok ->
            if (released || generation != controlGeneration) return@setFullscreenControls
            if (!ok || !wantEnhanced || !state.canUseEnhancedControls || state.identity != identity) {
                wantEnhanced = false
                disconnectControls()
                if (!ok && state.canUseEnhancedControls) {
                    showHud(activity.getString(R.string.ui_enhanced_controls_are_unavailable_for_this_webpage), 2200)
                }
                return@setFullscreenControls
            }
            connecting = false
            enhanced = true
            refreshMode()
            showControls(true)
        }
    }

    private fun disconnectControls() {
        controlGeneration++
        gestures.cancelGesture()
        menu = null
        enhanced = false
        connecting = false
        controlIdentity = null
        hudMessage = null
        changeLock(false)
        refreshMode()
        tracker.setFullscreenControls(false)
    }

    private fun refreshMode() {
        gestures.visibility = if (enhanced && !pictureInPicture) VISIBLE else GONE
        controlsHost.visibility = if (pictureInPicture) GONE else VISIBLE
        if (!enhanced) ui.removeCallbacks(hideControls)
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        if (show) scheduleHide()
    }

    fun setPictureInPicture(active: Boolean) {
        pictureInPicture = active
        cancelTransientControls()
        hudMessage = null
        refreshMode()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (enhanced && !locked && !seeking && menu == null && !accessibility.isTouchExplorationEnabled)
            ui.postDelayed(hideControls, 3500)
    }

    private fun changeLock(value: Boolean) {
        gestures.cancelGesture()
        menu = null
        locked = value
        showControls(!locked)
    }

    /** Back dismisses the local menu, then unlocks; only the next Back exits fullscreen. */
    fun handleBack(): Boolean {
        if (menu != null) { changeMenu(null); return true }
        if (!locked) return false
        changeLock(false)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !handleBack()) onExit()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (android.os.Build.VERSION.SDK_INT >= 33 && platformBack == null) {
            val callback = android.window.OnBackInvokedCallback {
                if (!released && !handleBack()) onExit()
            }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            platformBack = callback
        }
    }

    private fun togglePlayback() {
        tracker.togglePlayback { if (!it && !released) showHud(activity.getString(R.string.ui_the_webpage_could_not_change_playback), 1800) }
        scheduleHide()
    }
    private fun skip(seconds: Double) {
        if (!state.canSeek) { showHud(activity.getString(R.string.ui_seeking_is_unavailable_for_this_video), 1600); return }
        val target = (state.position + seconds).coerceIn(state.seekStart, state.seekEnd)
        seekTo(target)
        showHud(VideoGestureMath.time(target), 1200)
    }
    private fun seekTo(position: Double) {
        tracker.seekTo(position) { if (!it && !released) showHud(activity.getString(R.string.ui_the_webpage_could_not_seek), 1800) }
    }
    private fun seekPosition(progress: Float) = state.seekStart + progress * (state.seekEnd - state.seekStart)
    private fun progressText(position: Double, duration: Double) = activity.getString(
        R.string.video_progress, VideoGestureMath.time(position), VideoGestureMath.time(duration),
    )

    private fun changeMenu(value: PlayerMenu?) {
        gestures.cancelGesture()
        tracker.endBoost()
        hudMessage = null
        menu = value
        showControls(true)
        if (value != null) tracker.probe()
    }

    private fun rotate() {
        orientationChosen = true
        activity.requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        scheduleHide()
    }

    private fun showHud(message: String, millis: Long = 0) {
        if (released) return
        ui.removeCallbacks(hideHud)
        hudMessage = message
        if (millis > 0) ui.postDelayed(hideHud, millis)
    }

    fun cancelTransientControls() {
        gestures.cancelGesture()
        tracker.endBoost()
        menu = null
        ui.removeCallbacks(hideControls)
    }

    fun release() {
        if (released) return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            platformBack?.let { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            platformBack = null
        }
        disconnectControls()
        released = true
        controlsHost.disposeComposition()
        ui.removeCallbacksAndMessages(null)
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
        activity.requestedOrientation = originalOrientation
        activity.volumeControlStream = originalVolumeStream
        if (!keptScreenOn) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        removeView(videoView)
    }

    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelTransientControls()
        else if (!released && enhanced && !locked) showControls(true)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    private inner class GestureSurface(context: Context) : View(context) {
        private var down = false
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var axis = 0 // 0 pending, 1 brightness, 2 volume, 3 seek, 4 cancelled/hold
        private var startBrightness = 0f
        private var startVolume = 0
        private var startPosition = 0.0
        private var targetPosition = 0.0
        private var hold = false
        private var originalRate = 1f
        private var cancelledClick = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                when { event.x < width * 0.35f -> skip(-10.0)
                    event.x > width * 0.65f -> skip(10.0)
                    else -> togglePlayback() }
                return true
            }
        }).apply { setIsLongpressEnabled(false) }
        private val startHold = Runnable {
            if (down && axis == 0 && !locked && preferences.holdToBoost && state.isPlaying) {
                hold = true; axis = 4; originalRate = state.playbackRate ?: 1f
                cancelClick()
                tracker.beginBoost(preferences.boostRate) { ok ->
                    if (released) return@beginBoost
                    if (ok && hold) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showHud(activity.getString(R.string.ui_temporary_release_to_restore_speed, PlaybackSpeed.label(maxOf(originalRate, preferences.boostRate))))
                    } else if (ok) tracker.endBoost()
                    else if (hold) showHud(activity.getString(R.string.ui_temporary_speed_boost_is_unavailable_for_this_video), 1800)
                }
            }
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun performClick(): Boolean { super.performClick(); showControls(!controlsVisible); return true }
        private fun cancelClick() {
            if (cancelledClick) return
            cancelledClick = true
            val cancel = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
            detector.onTouchEvent(cancel); cancel.recycle()
        }
        fun cancelGesture() {
            ui.removeCallbacks(startHold)
            if (hold) {
                hold = false
                tracker.endBoost()
                showHud(activity.getString(R.string.ui_restored, PlaybackSpeed.label(originalRate)), 900)
            }
            if (down) cancelClick()
            down = false; axis = 0
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (locked) return true
            if (!enhanced) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x < dp(24) || event.x > width - dp(24)) return false
                    down = true; downX = event.x; downY = event.y; downTime = event.downTime
                    axis = 0; cancelledClick = false
                    startBrightness = activity.window.attributes.screenBrightness.takeIf { it >= 0f }
                        ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startPosition = state.position; targetPosition = startPosition
                    detector.onTouchEvent(event)
                    ui.postDelayed(startHold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_POINTER_DOWN -> { cancelGesture(); axis = 4 }
                MotionEvent.ACTION_MOVE -> {
                    if (!down || event.pointerCount != 1) return true
                    val dx = event.x - downX; val dy = event.y - downY
                    if (axis == 0 && maxOf(abs(dx), abs(dy)) > slop) {
                        ui.removeCallbacks(startHold); cancelClick()
                        axis = when {
                            abs(dy) > abs(dx) * 1.2f && preferences.verticalGestures -> if (downX < width / 2) 1 else 2
                            abs(dx) > abs(dy) * 1.2f && preferences.horizontalSeek && state.canSeek -> 3
                            else -> 4
                        }
                        showControls(false)
                    }
                    when (axis) {
                        1 -> {
                            val brightness = VideoGestureMath.level(startBrightness, -dy / height.coerceAtLeast(1)).coerceAtLeast(0.02f)
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                            showHud(activity.getString(R.string.ui_brightness, VideoGestureMath.percent(brightness)))
                        }
                        2 -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val volume = (VideoGestureMath.level(startVolume.toFloat() / max, -dy / height.coerceAtLeast(1)) * max).roundToInt()
                            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != volume) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
                            showHud(activity.getString(R.string.ui_volume, (volume * 100f / max).roundToInt()))
                        }
                        3 -> {
                            targetPosition = VideoGestureMath.seek(startPosition, dx / width.coerceAtLeast(1), state.duration, state.seekStart, state.seekEnd)
                            showHud(activity.getString(R.string.ui_release_to_seek, VideoGestureMath.time(targetPosition), VideoGestureMath.time(state.duration)))
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(startHold)
                    if (axis == 3) seekTo(targetPosition)
                    if (!cancelledClick && down) detector.onTouchEvent(event)
                    val wasGesture = axis != 0
                    // ACTION_UP completes a tap. Keep GestureDetector's pending single
                    // tap confirmation (and double-tap history) alive until its timeout.
                    down = false
                    cancelGesture()
                    if (wasGesture) ui.postDelayed(hideHud, 900)
                }
                MotionEvent.ACTION_CANCEL -> { cancelGesture(); ui.postDelayed(hideHud, 300) }
            }
            return true
        }
    }
}
