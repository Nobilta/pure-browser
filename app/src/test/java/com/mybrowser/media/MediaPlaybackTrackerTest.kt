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

    @Test
    fun aFrameIdIsBoundToTheOriginThatFirstReportedIt() {
        val view = ProbeView(RuntimeEnvironment.getApplication())
        val tracker = MediaPlaybackTracker(view, onSignal = {})
        val probe = android.net.Uri.parse("https://player.example")
        val adFrame = android.net.Uri.parse("https://ads.example")

        // The probe derives its id from the clock and Math.random, so another frame cannot
        // guess the id it is about to use; the first reporter therefore owns it.
        assertTrue(tracker.claimsFrame("frame-a", probe))
        assertTrue(tracker.claimsFrame("frame-a", probe))
        assertFalse("another origin must not speak for a frame it does not own",
            tracker.claimsFrame("frame-a", adFrame))
        // Cross-origin embeds keep working: they report under an id of their own.
        assertTrue(tracker.claimsFrame("frame-b", adFrame))
        // A port distinguishes origins, since the probe runs on any http(s) host.
        assertFalse(tracker.claimsFrame("frame-a", android.net.Uri.parse("https://player.example:8443")))
        assertTrue(tracker.claimsFrame("frame-c", android.net.Uri.parse("https://player.example:8443")))

        tracker.close(); view.destroy()
    }

    @Test
    fun anAgedOutBindingDoesNotBlockNewFrames() {
        val view = ProbeView(RuntimeEnvironment.getApplication())
        val tracker = MediaPlaybackTracker(view, onSignal = {})
        val player = android.net.Uri.parse("https://player.example")
        val embed = android.net.Uri.parse("https://embed.example")
        assertTrue(tracker.claimsFrame("frame-old", player))
        // Bindings are dropped by the pass that runs when a frame ages out, so a frame has to be
        // reporting for that pass to be scheduled at all; a claim with no frame beside it would
        // never expire, and this test would pass without exercising anything.
        view.signals = JSONArray().put(signal("frame-old", true))
        tracker.probe()
        assertFalse(
            "while its frame is alive the binding still owns the id",
            tracker.claimsFrame("frame-old", embed),
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        // The binding left with the frame, so the id — unguessable, derived from the clock and
        // Math.random — can be claimed again by whoever reports it next.
        assertTrue(tracker.claimsFrame("frame-old", embed))
        tracker.close(); view.destroy()
    }

    @Test
    fun aFullBindingTableMakesRoomInsteadOfIgnoringLaterFrames() {
        val view = ProbeView(RuntimeEnvironment.getApplication())
        val tracker = MediaPlaybackTracker(view, onSignal = {})
        val embed = android.net.Uri.parse("https://embed.example")
        // Fill the table the way a page with many iframes would.
        repeat(256) { assertTrue(tracker.claimsFrame("filler-$it", embed)) }
        // A rejected report never reaches the expiry pass, so the table has to make room itself;
        // otherwise the page's own player would be ignored for as long as the page lives.
        assertTrue(
            "a later frame must still be able to report",
            tracker.claimsFrame("player", android.net.Uri.parse("https://player.example")),
        )
        // The binding silent longest is the one displaced; here every filler reported at the same
        // instant, so that is the first inserted. It rebinds to its own origin afterwards.
        assertTrue(tracker.claimsFrame("filler-0", embed))
        tracker.close(); view.destroy()
    }
}
