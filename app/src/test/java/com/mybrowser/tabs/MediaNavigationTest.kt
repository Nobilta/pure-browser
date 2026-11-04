package com.mybrowser.tabs

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaNavigationTest {
    private val media = "https://cdn.example/video.mp4?token=a%2Bb"
    private val source = "https://site.example/watch?id=1"

    @Test fun referrerStripsCredentialsAndFragmentButKeepsTheSourceQuery() {
        val request = MediaNavigation.create(media, "https://user:secret@site.example/watch?id=1#private", "https://user:secret@site.example/watch?id=1#private")!!
        assertEquals(mapOf("Referer" to source), request.headersFor(media + "#t=10"))
        assertTrue(request.headersFor("https://cdn.example/other.mp4").isEmpty())
        assertTrue(request.headersFor(media.replace("a%2Bb", "different")).isEmpty())
    }

    @Test fun onlyActualRedirectsCanExtendTheScope() {
        val request = MediaNavigation.create(media, source)!!
        val target = "https://other-cdn.example/final.mp4?token=new"
        assertNull(request.follow(target, isRedirect = false))
        val redirected = request.follow(target, isRedirect = true)!!
        assertEquals(mapOf("Referer" to "https://site.example/"), redirected.headersFor(target))
        assertTrue(redirected.headersFor(media).isEmpty())
        assertNull(redirected.follow("https://other-cdn.example/article", isRedirect = false))
    }

    @Test fun openingOrDownloadingTheSameMediaAgainRetainsItsOriginalSource() {
        val request = MediaNavigation.create(media, source)!!
        assertEquals(source, request.sourceFor(media, media))
        assertEquals("https://other.example/page", request.sourceFor(media, "https://other.example/page"))
        assertEquals(media, request.sourceFor("https://cdn.example/other.mp4", media))
    }

    @Test fun redirectLoopsAreBoundedAndAnExplicitReloadStartsANewBudget() {
        var request = MediaNavigation.create(media, source)!!
        repeat(10) { request = request.follow(media, isRedirect = true)!! }
        assertFalse(request.canFollowRedirect)
        assertNull(request.follow(media, isRedirect = true))
        assertNotNull(request.forLoad().follow(media, isRedirect = true))
    }

    @Test fun invalidSourcesAndDowngradesNeverProduceHeaders() {
        for (url in listOf(null, "file:///private", "https://site.example/\r\nCookie:secret")) {
            assertNull(MediaNavigation.create(media, url))
        }
        assertNull(MediaNavigation.create("http://cdn.example/video.mp4", source))
        assertNull(MediaNavigation.create(media, source)!!.follow("http://cdn.example/video.mp4", true))
        assertNull(MediaNavigation.create(media, source)!!.follow("intent://player", true))
    }

    @Test fun unknownAndAbsentObservationsCannotDiscloseThePageQueryCrossOrigin() {
        assertEquals("https://site.example/", MediaNavigation.create(media, source)!!.referer)
        assertEquals("", MediaNavigation.create(media, source, "")!!.referer)
        assertEquals("https://site.example/", MediaNavigation.create(media, source, "https://site.example/")!!.referer)
        assertEquals(source, MediaNavigation.create(media, source, source)!!.referer)
    }

    @Test fun redirectPolicyCannotReconstructAReferrerThatAnEarlierHopRemoved() {
        val same = "https://site.example/video.mp4"
        val initial = MediaNavigation.create(same, source, source)!!
        val cross = "https://cdn.example/video.mp4"
        assertEquals("https://site.example/", initial.follow(cross, true)!!.referer)
        assertEquals(source, initial.follow(cross, true, "invalid, unsafe-url")!!.referer)
        val none = initial.follow(cross, true, "no-referrer")!!
        assertEquals("", none.follow(same, true, "unsafe-url")!!.referer)
        val reduced = initial.follow(cross, true)!!
        assertEquals("https://site.example/", reduced.follow(same, true, "unsafe-url")!!.referer)
    }

    @Test fun reloadReplacesTheRequestLifetimeButRedirectsRetainIt() {
        val context = MediaNavigation.create(media, source)!!
        context.loadId.cancelled = true
        context.loadId.failure = com.mybrowser.media.MediaRequestInterceptor.Failure(com.mybrowser.media.MediaRequestInterceptor.FailureKind.NETWORK)
        assertNotSame(context.loadId, context.forLoad().loadId)
        assertFalse(context.forLoad().loadId.cancelled)
        assertNull(context.forLoad().loadId.failure)
        assertSame(context.loadId, context.follow(media + "&redirect=1", true)!!.loadId)
        assertEquals("https://site.example/", MediaNavigation.origin("https://site.example:443/watch"))
    }

    @Test fun referrerDoesNotLeakIntoAnotherTabOrPersistedMetadata() {
        val tabs = TabManager()
        val restored = TabManager()
        try {
            tabs.createTab(media, mediaNavigation = MediaNavigation.create(media, source))
            val mediaTab = tabs.currentTab!!
            tabs.createTab("https://other.example")
            assertNull(tabs.currentTab!!.mediaNavigation)
            assertNotNull(mediaTab.mediaNavigation)
            val snapshot = tabs.snapshotMetadata()
            assertFalse(snapshot.getString("tabs")!!.contains(source))
            assertTrue(restored.restoreMetadata(snapshot))
            assertTrue(restored.tabs.all { it.mediaNavigation == null })
        } finally { tabs.cleanup(); restored.cleanup() }
    }
}
