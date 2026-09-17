package com.mybrowser.media

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How fast the stalling video is arriving.
 *
 * A WebView exposes no byte counter for a media response: an intercepted response cannot be
 * measured without proxying it (which would cost connection reuse, credentials and MSE
 * behaviour), and JavaScript cannot read socket totals. The page's own SourceBuffer appends are
 * therefore the only stream-scoped source, and they exist only for players that feed one — a
 * progressive download falls back to a process-wide [TrafficStats] sample, which is the whole
 * app's receive rate rather than this video's.
 *
 * Sampling only runs while the player is showing its buffering indicator, so an idle page costs
 * nothing.
 */
class PlaybackNetworkMeter(private val onSample: (String?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var identity = ""
    private var lastBytes = 0L
    private var lastBytesAt = 0L
    private var streamRate = 0.0
    private var streamFresh = false
    private var lastTraffic = UNKNOWN
    private var lastTrafficAt = 0L
    private var trafficRate = 0.0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sampleTraffic()
            onSample(format())
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        identity = ""
        streamRate = 0.0
        streamFresh = false
        trafficRate = 0.0
        lastTraffic = rxBytes()
        lastTrafficAt = SystemClock.uptimeMillis()
        handler.postDelayed(tick, INTERVAL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
    }

    /** Fed from every tracker signal; the page's own counter is preferred over the process total. */
    fun observe(signal: MediaPlaybackTracker.Signal) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        if (signal.identity != identity) {
            identity = signal.identity
            lastBytes = signal.receivedBytes
            lastBytesAt = now
            streamFresh = false
            return
        }
        val elapsed = now - lastBytesAt
        if (elapsed < MIN_WINDOW_MS) return
        val delta = signal.receivedBytes - lastBytes
        if (delta < 0L) {
            lastBytes = signal.receivedBytes
            lastBytesAt = now
            streamFresh = false
            return
        }
        // A counter that has not moved for a while says nothing about the current speed; the
        // meter then falls back to the process total instead of reporting a stale zero.
        if (delta == 0L) {
            if (now - lastBytesAt > STALE_MS) streamFresh = false
            return
        }
        val instant = delta * 1000.0 / elapsed
        streamRate = if (streamRate > 0) streamRate * (1 - SMOOTHING) + instant * SMOOTHING else instant
        lastBytes = signal.receivedBytes
        lastBytesAt = now
        streamFresh = true
    }

    private fun sampleTraffic() {
        val now = SystemClock.uptimeMillis()
        val current = rxBytes()
        val elapsed = now - lastTrafficAt
        if (current != UNKNOWN && lastTraffic != UNKNOWN && elapsed > 0) {
            val instant = (current - lastTraffic).coerceAtLeast(0L) * 1000.0 / elapsed
            trafficRate = if (trafficRate > 0) trafficRate * (1 - SMOOTHING) + instant * SMOOTHING else instant
        }
        lastTraffic = current
        lastTrafficAt = now
    }

    private fun format(): String? {
        val bytesPerSecond = if (streamFresh) streamRate else trafficRate
        if (!bytesPerSecond.isFinite() || bytesPerSecond < 1) return null
        return when {
            bytesPerSecond >= MEGABYTE -> String.format(Locale.US, "%.1f MB", bytesPerSecond / MEGABYTE)
            bytesPerSecond >= KILOBYTE -> "${(bytesPerSecond / KILOBYTE).roundToInt()} KB"
            else -> "${bytesPerSecond.roundToInt()} B"
        }
    }

    private fun rxBytes(): Long {
        // UID totals are the tighter scope; some devices only report a process or device total.
        val uid = TrafficStats.getUidRxBytes(Process.myUid())
        if (uid >= 0L) return uid
        val total = TrafficStats.getTotalRxBytes()
        return if (total >= 0L) total else UNKNOWN
    }

    private companion object {
        const val INTERVAL_MS = 500L
        const val MIN_WINDOW_MS = 300L
        const val STALE_MS = 2_000L
        const val SMOOTHING = 0.5
        const val KILOBYTE = 1024.0
        const val MEGABYTE = 1024.0 * 1024
        const val UNKNOWN = -1L
    }
}
