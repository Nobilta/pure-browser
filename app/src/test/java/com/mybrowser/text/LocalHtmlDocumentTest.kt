package com.mybrowser.text

import android.app.Application
import android.net.Uri
import com.mybrowser.tabs.TabManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class LocalHtmlDocumentTest {
    @Test fun childTabsKeepTheGrantedFileWithinTheirSessionEvenAfterTheOpenerCloses() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://html.test/index.html")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("<p>local</p>".toByteArray()))
        val document = LocalHtmlDocument.open(context, uri)
        val tabs = TabManager()
        val privateTabs = TabManager()
        val restored = TabManager()
        try {
            val opener = tabs.currentTab!!
            opener.url = document.url
            opener.localHtml = document
            // Popup destinations are unknown when Chromium asks for a new window.
            val popupId = tabs.createTab(openerTabId = opener.id)
            assertSame(document, tabs.currentTab!!.localHtml)
            val backgroundId = tabs.createTab(document.url + "?child", select = false, openerTabId = opener.id)
            assertSame(document, tabs.tabs.first { it.id == backgroundId }.localHtml)
            val remoteId = tabs.createTab("https://remote.test/", select = false, openerTabId = opener.id)
            assertNull(tabs.tabs.first { it.id == remoteId }.localHtml)
            tabs.closeTab(tabs.tabs.indexOfFirst { it.id == opener.id })
            assertEquals(popupId, tabs.currentTab!!.id)
            assertSame(document, tabs.currentTab!!.localHtml)
            tabs.createTab()
            assertNull(tabs.currentTab!!.localHtml)
            privateTabs.createTab(document.url, openerTabId = popupId)
            assertNull(privateTabs.currentTab!!.localHtml)
            assertTrue(restored.restoreMetadata(tabs.snapshotMetadata()))
            assertTrue(restored.tabs.all { it.localHtml == null })
            assertFalse(tabs.snapshotMetadata().getString("tabs")!!.contains("local.pure-browser.invalid"))
        } finally { tabs.cleanup(); privateTabs.cleanup(); restored.cleanup() }
    }

    @Test fun localTitlesKeepPageTitlesAndReplaceChromiumAddressFallbacks() {
        val url = "https://test.local.pure-browser.invalid/my%20file.html"
        for (title in listOf(null, "", " ", url, url.removePrefix("https://"), "test.local.pure-browser.invalid")) {
            assertEquals("my file.html", LocalHtmlDocument.displayTitle(url, title))
        }
        assertEquals("Document heading", LocalHtmlDocument.displayTitle(url, "Document heading"))
        assertEquals("Web page", LocalHtmlDocument.displayTitle("https://example.com/", "Web page"))
        val tabs = TabManager()
        try {
            tabs.createTab(url, title = "test.local.pure-browser.invalid")
            assertEquals("my file.html", tabs.currentTab!!.title)
        } finally { tabs.cleanup() }
    }

    @Test fun servesOriginalHtmlBytesOnlyAtTheGrantedDocumentAndDoesNotPersistItsAddress() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://html.test/index.html")
        val bytes = "<meta charset=gb18030><style>p{color:red}</style><p>中文</p><script>window.ready=true</script>"
            .toByteArray(charset("GB18030"))
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val document = LocalHtmlDocument.open(context, uri)
        val loader = document.loader(context)
        assertTrue(document.matches(document.url))
        assertTrue(document.matches(document.url + "?child#section"))
        assertFalse(document.matches(Uri.parse(document.url).buildUpon().path("/other.html").build().toString()))
        val response = loader.shouldInterceptRequest(Uri.parse(document.url))!!
        assertEquals("text/html", response.mimeType)
        assertArrayEquals(bytes, response.data.readBytes())
        assertEquals(404, loader.shouldInterceptRequest(Uri.parse(document.url).buildUpon().path("/sibling.css").build())!!.statusCode)
        val other = LocalHtmlDocument.open(context, uri)
        assertFalse(document.matches(other.url))
        assertNotEquals(Uri.parse(document.url).host, Uri.parse(other.url).host)
        assertNull(loader.shouldInterceptRequest(Uri.parse(other.url)))
        val tabs = TabManager()
        try {
            tabs.createTab(document.url, title = document.name)
            tabs.currentTab!!.localHtml = document
            val metadata = tabs.snapshotMetadata().getString("tabs")!!
            assertFalse(metadata.contains("local.pure-browser.invalid"))
            assertFalse(metadata.contains("content://"))
        } finally { tabs.cleanup() }
    }
}
