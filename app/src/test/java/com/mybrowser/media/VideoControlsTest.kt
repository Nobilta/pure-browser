package com.mybrowser.media

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VideoControlsTest {
    private fun signal() = JSONObject().put("frameId", "frame").put("videoId", "v1")
        .put("hasVideo", true).put("duration", 120).put("seekStart", 5).put("seekEnd", 120)

    @Test fun pausedVideoRetainsItsControlTarget() {
        val value = MediaPlaybackTracker.decodeSignal(signal().put("playing", false))!!
        assertTrue(value.hasVideo)
        assertTrue(value.canSeek)
        assertFalse(value.isPlaying)
        assertEquals("frame/v1", value.identity)
    }

    @Test fun liveOrUnknownDurationCannotBeScrubbed() {
        assertFalse(MediaPlaybackTracker.decodeSignal(signal().put("duration", 0))!!.canSeek)
        assertFalse(MediaPlaybackTracker.decodeSignal(signal().put("seekEnd", 5))!!.canSeek)
    }

    @Test fun missingFrameAndVideoCannotBecomeControlTargets() {
        assertNull(MediaPlaybackTracker.decodeSignal(signal().put("frameId", "")))
        val invalid = MediaPlaybackTracker.decodeSignal(signal().put("videoId", JSONObject.NULL).put("playing", true))!!
        assertFalse(invalid.hasVideo)
        assertFalse(invalid.isPlaying)
    }

    @Test fun hostileMetadataIsBoundedAndUnsafeUrlsAreDropped() {
        val json = signal().put("position", -40).put("width", 999999)
            .put("sourceUrl", "file:///private")
            .put("urls", JSONArray(listOf("file:///private", "javascript:alert(1)", "https://example.com/a.mp4", "https://example.com/a.mp4")))
        val parsed = MediaPlaybackTracker.decodeSignal(json)!!
        assertEquals(0.0, parsed.position, 0.0)
        assertEquals(16384, parsed.width)
        assertEquals(listOf("https://example.com/a.mp4"), parsed.urls)
        assertNull(parsed.sourceUrl)
    }

    @Test fun seekGesturesRespectBothEndsAndLiveStreams() {
        assertEquals(5.0, VideoGestureMath.seek(8.0, -1f, 120.0, 5.0, 120.0), 0.0)
        assertEquals(120.0, VideoGestureMath.seek(118.0, 1f, 120.0, 5.0, 120.0), 0.0)
        assertEquals(20.0, VideoGestureMath.seek(20.0, 1f, Double.POSITIVE_INFINITY, 0.0, 100.0), 0.0)
    }

    @Test fun levelGesturesCannotOverflowAndTimeHandlesLongVideos() {
        assertEquals(1f, VideoGestureMath.level(0.8f, 1f), 0f)
        assertEquals(0f, VideoGestureMath.level(0.2f, -1f), 0f)
        assertEquals("1:01:01", VideoGestureMath.time(3661.0))
        assertEquals("0:00", VideoGestureMath.time(Double.NaN))
    }

    @Test fun missingHandoffCapabilityKeepsWebControls() {
        val value = MediaPlaybackTracker.decodeSignal(signal().put("fullscreen", true))!!
        assertFalse(value.canUseEnhancedControls)
    }

    @Test fun fullscreenVideoRequiresPositiveHandoffCapability() {
        val value = MediaPlaybackTracker.decodeSignal(
            signal().put("fullscreen", true).put("nativeControlsAvailable", true),
        )!!
        assertTrue(value.canUseEnhancedControls)
        assertFalse(value.copy(isFullscreen = false).canUseEnhancedControls)
        assertFalse(value.copy(hasVideo = false).canUseEnhancedControls)
    }

    @Test fun anHttpHintAloneCannotEnableNativeControls() {
        val value = MediaPlaybackTracker.decodeSignal(
            signal().put("fullscreen", true)
                .put("urls", JSONArray(listOf("https://example.com/manifest.mpd"))),
        )!!
        assertFalse(value.canUseEnhancedControls)
    }
}
