package com.mybrowser.media

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mybrowser.R
import com.mybrowser.data.VideoPreferences
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native controls around Chromium's custom view; decoding, cookies and subtitles stay on the page. */
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
    private val onCast: () -> Unit,
    private val onChooseSpeed: () -> Unit,
    private val onPictureInPicture: (() -> Unit)? = null,
) : FrameLayout(activity) {
    private val ui = Handler(Looper.getMainLooper())
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val accessibility = activity.getSystemService(AccessibilityManager::class.java)
    private val originalBrightness = activity.window.attributes.screenBrightness
    private val originalOrientation = activity.requestedOrientation
    private val originalVolumeStream = activity.volumeControlStream
    private val keptScreenOn = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    private val accent = Color.rgb(165, 200, 255)
    // Chromium may create this custom view before the tracker observes the new
    // fullscreen event. A previous session's cached flag must not start a handoff.
    private var state = tracker.current.copy(isFullscreen = false)
    private var released = false
    private var enhanced = false
    private var wantEnhanced = enhancedPlayback
    private var connecting = false
    private var controlIdentity: String? = null
    private var controlGeneration = 0
    private var controlsVisible = true
    private var pictureInPicture = false
    private var platformBack: android.window.OnBackInvokedCallback? = null
    private var locked = false
    private var orientationChosen = false
    private var seeking = false
    private val top = LinearLayout(activity)
    private val bottom = LinearLayout(activity)
    private val title = TextView(activity)
    private val clock = TextView(activity)
    private val seek = SeekBar(activity)
    private val play = imageButton(R.drawable.ic_pause, activity.getString(R.string.ui_pause_video)) { togglePlayback() }
    private val speed = textButton("1×", activity.getString(R.string.menu_playback_speed)) { showSpeedPicker() }
    private val lock = imageButton(R.drawable.ic_lock, activity.getString(R.string.ui_lock_screen)) { setLocked(!locked) }
    private val cast = imageButton(R.drawable.ic_cast, activity.getString(R.string.cd_cast)) { onCast() }
    private val hud = TextView(activity)
    private val gestures = GestureSurface(activity)
    private val hideControls = Runnable { if (!locked && !seeking) showControls(false) }
    private val hideHud = Runnable { hud.visibility = GONE }
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

        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(12), dp(8), dp(12), dp(20))
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xDD000000.toInt(), Color.TRANSPARENT))
        top.addView(imageButton(R.drawable.ic_back, activity.getString(R.string.ui_exit_fullscreen), onExit), LinearLayout.LayoutParams(dp(48), dp(48)))
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        top.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        title.gravity = Gravity.CENTER_VERTICAL
        onPictureInPicture?.let { action ->
            top.addView(imageButton(R.drawable.ic_pip, activity.getString(R.string.picture_in_picture), action), LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        top.addView(imageButton(R.drawable.ic_rotate, activity.getString(R.string.ui_rotate_screen)) { rotate() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(top, LayoutParams(-1, -2, Gravity.TOP))

        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(16), dp(24), dp(16), dp(12))
        bottom.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xEE000000.toInt()))
        clock.setTextColor(Color.WHITE)
        clock.textSize = 12f
        bottom.addView(clock)
        seek.max = 10000
        seek.progressTintList = ColorStateList.valueOf(accent)
        seek.thumbTintList = ColorStateList.valueOf(accent)
        seek.contentDescription = activity.getString(R.string.ui_video_progress)
        bottom.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true; ui.removeCallbacks(hideControls); gestures.cancelGesture() }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && state.canSeek) clock.text = progressText(seekPosition(progress), state.duration)
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                seekTo(seekPosition(bar.progress))
                scheduleHide()
            }
        })
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(play, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(speed, LinearLayout.LayoutParams(-2, dp(48)))
        row.addView(cast, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottom.addView(row)
        addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(lock, LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(16) })

        hud.setTextColor(Color.WHITE)
        hud.textSize = 16f
        hud.gravity = Gravity.CENTER
        hud.setPadding(dp(24), dp(16), dp(24), dp(16))
        hud.background = rounded(0xD9222630.toInt())
        hud.visibility = GONE
        hud.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(hud, LayoutParams(-2, -2, Gravity.CENTER))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
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
        title.text = titleProvider().ifBlank { activity.getString(R.string.ui_video_playback) }
        if (!orientationChosen && preferences.landscapeFullscreen && state.isFullscreen && state.width > state.height && state.height > 0) {
            orientationChosen = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if ((enhanced || connecting) &&
            (!state.canUseEnhancedControls || controlIdentity != state.identity)
        ) {
            disconnectControls()
        }
        refreshMode()
        if (wantEnhanced && !enhanced && !connecting && state.canUseEnhancedControls) connectControls()
        play.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = if (state.isPlaying) activity.getString(R.string.ui_pause_video) else activity.getString(R.string.ui_play_video)
        play.isEnabled = state.hasVideo
        speed.text = PlaybackSpeed.label(state.playbackRate ?: 1f)
        speed.contentDescription = activity.getString(R.string.ui_playback_speed, speed.text)
        cast.visibility = if (canCast()) VISIBLE else GONE
        seek.isEnabled = state.canSeek
        if (!seeking) {
            clock.text = if (state.duration > 0) progressText(state.position, state.duration)
                else if (state.hasVideo) activity.getString(R.string.ui_live, VideoGestureMath.time(state.position)) else activity.getString(R.string.ui_waiting_for_webpage_video)
            seek.progress = if (state.canSeek) (((state.position - state.seekStart) / (state.seekEnd - state.seekStart)) * 10000).roundToInt().coerceIn(0, 10000) else 0
        }
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
        enhanced = false
        connecting = false
        controlIdentity = null
        hud.visibility = GONE
        setLocked(false)
        // Remove our input and playback layers before returning control to the page.
        refreshMode()
        tracker.setFullscreenControls(false)
    }

    private fun refreshMode() {
        gestures.visibility = if (enhanced && !pictureInPicture) VISIBLE else GONE
        if (!enhanced) ui.removeCallbacks(hideControls)
        renderControls()
    }

    private fun showControls(show: Boolean) {
        controlsVisible = show
        renderControls()
        if (show) scheduleHide()
    }

    private fun renderControls() {
        if (pictureInPicture) {
            top.visibility = GONE; bottom.visibility = GONE; lock.visibility = GONE; hud.visibility = GONE
            return
        }
        // State telemetry must not reveal controls or restart the user's hide timer.
        top.visibility = if (enhanced && !locked && controlsVisible) VISIBLE else GONE
        bottom.visibility = if (enhanced && !locked && controlsVisible) VISIBLE else GONE
        lock.visibility = if (enhanced && (controlsVisible || locked)) VISIBLE else GONE
    }

    fun setPictureInPicture(active: Boolean) {
        pictureInPicture = active
        gestures.cancelGesture()
        refreshMode()
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (enhanced && !locked && !seeking && !accessibility.isTouchExplorationEnabled) ui.postDelayed(hideControls, 3500)
    }

    private fun setLocked(value: Boolean) {
        gestures.cancelGesture()
        locked = value
        lock.setImageResource(if (locked) R.drawable.ic_lock_open else R.drawable.ic_lock)
        lock.contentDescription = if (locked) activity.getString(R.string.ui_unlock_screen) else activity.getString(R.string.ui_lock_screen)
        lock.tooltipText = lock.contentDescription
        lock.background = rounded(if (locked) 0xD9365F91.toInt() else 0x88343A46.toInt())
        showControls(!locked)
    }

    /** Back unlocks first, preventing an accidental exit while controls are locked. */
    fun unlockOnBack(): Boolean {
        if (!locked) return false
        setLocked(false)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Handle Back before Chromium's custom view, which otherwise exits while locked.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && !unlockOnBack()) onExit()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (android.os.Build.VERSION.SDK_INT >= 33 && platformBack == null) {
            // Chromium registers its own full-screen Back callback after the Activity
            // fallback. Own the overlay's gesture so a locked video unlocks first.
            val callback = android.window.OnBackInvokedCallback {
                if (!released && !unlockOnBack()) onExit()
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
    private fun seekPosition(progress: Int) = state.seekStart + progress / 10000.0 * (state.seekEnd - state.seekStart)
    private fun progressText(position: Double, duration: Double) = activity.getString(
        R.string.video_progress, VideoGestureMath.time(position), VideoGestureMath.time(duration),
    )

    private fun showSpeedPicker() {
        gestures.cancelGesture()
        ui.removeCallbacks(hideControls)
        tracker.endBoost()
        onChooseSpeed()
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
        hud.text = message
        hud.visibility = VISIBLE
        if (millis > 0) ui.postDelayed(hideHud, millis)
    }

    fun cancelTransientControls() {
        gestures.cancelGesture()
        tracker.endBoost()
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
        else if (!released && enhanced && !locked) {
            // A sheet can remain open longer than the hide timeout. Start a fresh
            // interaction window after it closes, so the first tap reaches its button.
            showControls(true)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun buttonBackground() = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), rounded(0x55343A46, 24), null)
    private fun imageButton(resource: Int, description: String, click: () -> Unit) = ImageButton(activity).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description; background = buttonBackground(); setPadding(dp(12), dp(12), dp(12), dp(12))
        tooltipText = description
        setOnClickListener { click() }
    }
    private fun textButton(label: String, description: String, click: () -> Unit) = TextView(activity).apply {
        text = label; contentDescription = description; textSize = 13f; setTextColor(Color.WHITE)
        tooltipText = description
        gravity = Gravity.CENTER; minWidth = dp(48); setPadding(dp(12), 0, dp(12), 0)
        background = buttonBackground(); isFocusable = true; setOnClickListener { click() }
    }

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
