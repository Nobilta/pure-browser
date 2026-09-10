package com.mybrowser.media

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Regression tests for active-stream matching and candidate ordering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaCandidateStoreTest {

    private fun candidate(url: String, label: String = url) = MediaSniffer.Candidate(
        url = url,
        kind = MediaSniffer.Kind.HLS,
        label = label,
        pageUrl = "https://example.com/watch",
    )

    @Test
    fun `old page request completing after navigation cannot contaminate new candidates`() {
        val store = MediaCandidateStore()
        val oldGeneration = store.pageGeneration
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var accepted = true
        val worker = thread {
            started.countDown()
            if (finish.await(5, TimeUnit.SECONDS)) {
                accepted = store.add(candidate("https://old.example/live.m3u8"), oldGeneration)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Even an empty page has in-flight requests that clear() must invalidate.
        store.clear()
        store.clear()
        val current = candidate("https://new.example/current.m3u8")
        assertTrue(store.add(current, store.pageGeneration))
        finish.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertFalse(accepted)
        assertEquals(listOf(current), store.candidates)
    }

    @Test
    fun `loaded video recovers a missed candidate with its full signed URL`() {
        val source = "https://cdn.example/movie.mp4?signature=keep%2Bthis&expires=123"
        val signal = MediaPlaybackTracker.decodeSignal(
            JSONObject().put("frameId", "frame").put("videoId", "v1")
                .put("frameUrl", "https://example.com/watch").put("hasVideo", true)
                .put("playing", true).put("sourceUrl", source).put("urls", JSONArray(listOf(source))),
        )!!
        val store = MediaCandidateStore()
        store.updatePlayback(signal)
        assertEquals(source, store.preferredCandidate?.url)
        assertEquals("https://example.com/watch", store.preferredCandidate?.pageUrl)
        assertEquals(setOf(source), store.playingCandidateUrls)
        store.updatePlayback(signal.copy(isPlaying = false))
        assertEquals(1, store.count)
        assertTrue(store.playingCandidateUrls.isEmpty())
        store.clear()
        store.updatePlayback(signal.copy(isPlaying = false))
        assertEquals(source, store.preferredCandidate?.url)
    }

    @Test
    fun `blob sources and resource hints do not create direct candidates`() {
        val store = MediaCandidateStore()
        val signal = MediaPlaybackTracker.Signal(
            hasVideo = true, isPlaying = true, sourceUrl = "blob:https://example.com/video",
            urls = listOf("https://cdn.example/unrelated.mp4"),
        )
        store.updatePlayback(signal)
        store.updatePlayback(signal.copy(sourceUrl = null))
        store.updatePlayback(signal.copy(sourceUrl = "https://cdn.example/video.m4s"))
        store.updatePlayback(signal.copy(hasVideo = false, sourceUrl = "https://cdn.example/movie.mp4"))
        assertEquals(0, store.count)
    }

    @Test
    fun `active manifest is marked and preferred`() {
        val first = candidate("https://cdn.example/first.m3u8")
        val second = candidate("https://cdn.example/second.m3u8")
        val store = MediaCandidateStore()
        store.add(first)
        store.add(second)

        store.setPlayingVideos(listOf("https://cdn.example/second.m3u8", "blob:https://example.com/id"))

        assertEquals(second, store.preferredCandidate)
        assertTrue(store.isPlaying(second))
        assertFalse(store.isPlaying(first))
        assertEquals(setOf(second.url), store.playingCandidateUrls)
        assertEquals(second, store.candidates.first())
    }

    @Test
    fun `only the first matching stream is marked when a page exposes several hints`() {
        val first = candidate("https://cdn.example/first.m3u8")
        val second = candidate("https://cdn.example/second.m3u8")
        val store = MediaCandidateStore()
        store.add(first)
        store.add(second)

        // The probe may report the active video's URL followed by other recent resources.
        store.setPlayingVideos(listOf(first.url, second.url))

        assertEquals(setOf(first.url), store.playingCandidateUrls)
        assertTrue(store.isPlaying(first))
        assertFalse(store.isPlaying(second))
    }

    @Test
    fun `an exact URL wins over an earlier signed path fallback`() {
        val signed = candidate("https://cdn.example/live.m3u8?token=old")
        val exact = candidate("https://cdn.example/live.m3u8?token=new")
        val store = MediaCandidateStore()
        store.add(signed)
        store.add(exact)

        store.setPlayingVideos(
            listOf(
                "https://cdn.example/live.m3u8?token=rotated",
                exact.url,
            ),
        )

        // The path-only hint is ambiguous and cannot be selected; the later exact URL is
        // unambiguous and should be the only marked stream.
        assertEquals(setOf(exact.url), store.playingCandidateUrls)
    }

    @Test
    fun `rotating auth query matches a unique manifest path`() {
        val manifest = candidate("https://cdn.example/live/channel.m3u8?auth=old")
        val other = candidate("https://cdn.example/live/other.m3u8?auth=old")
        val store = MediaCandidateStore()
        store.add(manifest)
        store.add(other)

        store.setPlayingVideo("https://cdn.example/live/channel.m3u8?auth=new")

        assertTrue(store.isPlaying(manifest))
        assertEquals(manifest, store.preferredCandidate)
    }

    @Test
    fun `ambiguous path does not mark every signed variant`() {
        val first = candidate("https://cdn.example/live/channel.m3u8?auth=one")
        val second = candidate("https://cdn.example/live/channel.m3u8?auth=two")
        val store = MediaCandidateStore()
        store.add(first)
        store.add(second)

        store.setPlayingVideo("https://cdn.example/live/channel.m3u8?auth=unknown")

        assertTrue(store.playingCandidateUrls.isEmpty())
        assertEquals(first, store.preferredCandidate)
    }

    @Test
    fun `encoded separators identify different streams`() {
        val encoded = candidate("https://cdn.example/live%2Fchannel.m3u8?token=a%26b")
        val decoded = candidate("https://cdn.example/live/channel.m3u8?token=a&b")
        val store = MediaCandidateStore()
        store.add(encoded)
        store.add(decoded)
        store.setPlayingVideo(decoded.url)
        assertEquals(setOf(decoded.url), store.playingCandidateUrls)
        store.setPlayingVideo("https://cdn.example/live%2Fchannel.m3u8?token=new")
        assertEquals(setOf(encoded.url), store.playingCandidateUrls)
    }

    @Test
    fun `default ports and fragments do not change the media endpoint`() {
        val stream = candidate("https://cdn.example/live.m3u8?token=abc")
        val store = MediaCandidateStore()
        store.add(stream)
        store.setPlayingVideo("https://CDN.example:443/live.m3u8?token=abc#video")
        assertEquals(setOf(stream.url), store.playingCandidateUrls)
    }

    @Test
    fun `repeated playback hints retain an immutable snapshot until candidates change`() {
        val store = MediaCandidateStore()
        val stream = candidate("https://cdn.example/live.m3u8?token=one")
        store.add(stream)
        store.setPlayingVideo("https://cdn.example/live.m3u8?token=rotated")
        val before = store.state.value
        repeat(10) { store.setPlayingVideo("https://cdn.example/live.m3u8?token=rotated") }
        org.junit.Assert.assertSame(before, store.state.value)
        store.add(candidate("https://cdn.example/live.m3u8?token=two"))
        assertTrue(store.playingCandidateUrls.isEmpty())
    }
    @Test
    fun `active candidate survives the picker cap`() {
        val store = MediaCandidateStore()
        val candidates = (0 until 30).map { index ->
            candidate("https://cdn.example/channel-$index.m3u8")
        }
        val active = candidates.last()
        // The document-start signal can arrive before the network interceptor sees the
        // manifest. Keep that hint pending while the bounded candidate list fills.
        store.setPlayingVideo(active.url)
        candidates.forEach(store::add)

        assertEquals(24, store.candidates.size)
        assertTrue(store.candidates.any { it.url == active.url })
        assertTrue(store.isPlaying(active))
        assertEquals(active, store.candidates.first())
    }
}
