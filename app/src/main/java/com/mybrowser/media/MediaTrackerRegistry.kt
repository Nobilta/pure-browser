package com.mybrowser.media

import android.webkit.WebView
import java.util.WeakHashMap

/**
 * One media probe per pooled WebView.
 *
 * The registry owns installation and teardown only: what a signal *means* stays with the host,
 * which is the only place that knows the current page, the video preferences and the system
 * media session. Signals are delivered with the tracker that produced them so a late message
 * from a replaced view can be recognised and dropped.
 */
class MediaTrackerRegistry {
    private val trackers = WeakHashMap<WebView, MediaPlaybackTracker>()

    fun trackerOf(view: WebView): MediaPlaybackTracker? = trackers[view]

    /** Replaces any tracker this view already had. */
    fun install(
        view: WebView,
        onSignal: (WebView, MediaPlaybackTracker, MediaPlaybackTracker.Signal) -> Unit,
    ) {
        trackers.remove(view)?.close()
        lateinit var tracker: MediaPlaybackTracker
        tracker = MediaPlaybackTracker(view) { signal -> onSignal(view, tracker, signal) }
        trackers[view] = tracker
        tracker.install()
    }

    /** Stops observing this view; the caller still owns anything it derived from the tracker. */
    fun remove(view: WebView): MediaPlaybackTracker? = trackers.remove(view)
}
