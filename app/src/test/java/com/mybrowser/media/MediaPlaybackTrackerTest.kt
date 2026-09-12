package com.mybrowser.media

import android.app.Application
import android.content.Context
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaPlaybackTrackerTest {
    private class ProbeView(context: Context) : WebView(context) {
        var signals = JSONArray()
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            if (script.contains("JSON.stringify(out)")) resultCallback?.onReceiveValue(signals.toString())
        }
    }

    private fun signal(frame: String, playing: Boolean, media: Boolean = true) = JSONObject()
        .put("frameId", frame).put("videoId", if (media) "video" else "")
        .put("hasVideo", media).put("hasMedia", media).put("playing", playing)
        .put("playbackAvailable", media).put("frameUrl", "https://example.com/")

    @Test fun aRemovedFrameExpiresWithoutWaitingForAnotherDocumentMessage() {
        val view = ProbeView(RuntimeEnvironment.getApplication())
        val states = mutableListOf<MediaPlaybackTracker.Signal>()
        val tracker = MediaPlaybackTracker(view, onSignal = states::add)
        view.signals = JSONArray().put(signal("child", true))
        tracker.probe()
        assertTrue(tracker.current.isPlaying)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        view.signals = JSONArray().put(signal("parent", false, false))
        tracker.probe()
        assertTrue(tracker.current.isPlaying)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(tracker.current.hasMedia)
        assertFalse(states.last().isPlaying)
        tracker.close(); view.destroy()
    }

    @Test fun suspensionRejectsDelayedPlayingSnapshotsUntilThePageResumes() {
        val view = ProbeView(RuntimeEnvironment.getApplication())
        val tracker = MediaPlaybackTracker(view) {}
        view.signals = JSONArray().put(signal("page", true))
        tracker.probe()
        tracker.setSuspended(true)
        assertFalse(tracker.current.isPlaying)
        tracker.probe()
        assertFalse(tracker.current.isPlaying)
        tracker.setSuspended(false)
        tracker.probe()
        assertTrue(tracker.current.isPlaying)
        tracker.close(); view.destroy()
    }

    @Test fun navigationResetCancelsTheOldFrameDeadline() {
        val view = ProbeView(RuntimeEnvironment.getApplication())
        val states = mutableListOf<MediaPlaybackTracker.Signal>()
        val tracker = MediaPlaybackTracker(view, onSignal = states::add)
        view.signals = JSONArray().put(signal("page", true))
        tracker.probe(); tracker.reset()
        val count = states.size
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(count, states.size)
        assertFalse(tracker.current.hasMedia)
        tracker.close(); view.destroy()
    }
}
