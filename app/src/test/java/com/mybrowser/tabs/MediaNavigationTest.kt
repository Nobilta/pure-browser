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
        val request = MediaNavigation.create(media, "https://user:secret@site.example/watch?id=1#private")!!
        assertEquals(mapOf("Referer" to source), request.headersFor(media + "#t=10"))
        assertTrue(request.headersFor("https://cdn.example/other.mp4").isEmpty())
        assertTrue(request.headersFor(media.replace("a%2Bb", "different")).isEmpty())
    }

    @Test fun onlyActualRedirectsCanExtendTheScope() {
        val request = MediaNavigation.create(media, source)!!
        val target = "https://other-cdn.example/final.mp4?token=new"
        assertNull(request.follow(target, isRedirect = false))
        val redirected = request.follow(target, isRedirect = true)!!
        assertEquals(mapOf("Referer" to source), redirected.headersFor(target))
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
