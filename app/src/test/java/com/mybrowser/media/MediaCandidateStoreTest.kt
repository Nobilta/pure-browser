package com.mybrowser.media

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
