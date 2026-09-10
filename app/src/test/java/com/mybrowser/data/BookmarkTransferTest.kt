package com.mybrowser.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookmarkTransferTest {
    @Test fun realRustParserRoundTripsTheExportAndRejectsExecutableLinks() {
        val bookmark = Bookmark(title = "中文 & <Title>", url = "https://example.com/?a=1&b=2")
        val text = BookmarkHtml.encode(listOf(bookmark)).toString(Charsets.UTF_8)
        val parsed = BookmarkHtml.parse(text + "<A HREF='javascript:alert(1)'>Bad</A>")
        assertEquals(listOf(ImportedBookmark(bookmark.title, bookmark.url)), parsed.entries)
        assertEquals(1, parsed.skipped)
    }

    @Test fun importPreservesExistingIdentityAndTitleAndDeduplicatesTheBatch() {
        val manager = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            val id = manager.addBookmark("Original", "https://example.com")
            val inserted = manager.importBookmarks(listOf(ImportedBookmark("New title", "https://example.com"),
                ImportedBookmark("Second", "https://second.example"), ImportedBookmark("Duplicate", "https://second.example")))
            assertEquals(1, inserted)
            val original = manager.getAllBookmarks().single { it.url == "https://example.com" }
            assertEquals(id, original.id)
            assertEquals("Original", original.title)
            assertEquals(2, manager.bookmarksForExport().size)
        } finally { manager.close() }
    }

    @Test fun invalidBatchRollsBackEarlierRows() {
        val manager = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            manager.addBookmark("Keep", "https://keep.example")
            assertThrows(IllegalArgumentException::class.java) { manager.importBookmarks(listOf(
                ImportedBookmark("New", "https://new.example"), ImportedBookmark("Unsafe", "javascript:alert(1)"))) }
            assertEquals(listOf("https://keep.example"), manager.getAllBookmarks().map { it.url })
        } finally { manager.close() }
    }

    @Test fun exportEscapesTitlesAndSignedUrlsAndOmitsNonWebActions() {
        val html = BookmarkHtml.encode(listOf(Bookmark(title = "<中文 & \"Title\">", url = "https://example.com/?a=1&b=2", createdAt = 2000),
            Bookmark(title = "Phone", url = "tel:1234"))).toString(Charsets.UTF_8)
        assertTrue(html.contains("&lt;中文 &amp; &quot;Title&quot;&gt;"))
        assertTrue(html.contains("https://example.com/?a=1&amp;b=2"))
        assertTrue(html.contains("ADD_DATE=\"2\""))
        assertFalse(html.contains("tel:1234"))
        assertTrue(html.endsWith("</DL><p>\n"))
    }

    @Test fun oversizedExportFailsBeforeReturningPartialBytes() {
        val entries = List(1500) { Bookmark(title = "中文".repeat(256), url = "https://example.com/?" + "a".repeat(7900)) }
        assertThrows(IllegalArgumentException::class.java) { BookmarkHtml.encode(entries) }
    }
}
