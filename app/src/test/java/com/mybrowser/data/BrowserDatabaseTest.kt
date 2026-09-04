package com.mybrowser.data

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Host-side coverage for the repository boundary. These tests deliberately exercise both
 * managers at once: the shared SQLiteOpenHelper is a lifecycle optimisation, so a test that
 * only opens one repository would not catch a premature close or a schema mismatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserDatabaseTest {

    private lateinit var bookmarks: BookmarkManager
    private lateinit var history: HistoryManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        bookmarks = BookmarkManager(context)
        history = HistoryManager(context)
        bookmarks.clearAll()
        history.clearAll()
    }

    @After
    fun tearDown() {
        bookmarks.close()
        history.close()
    }

    @Test
    fun `shared helper remains usable until both repositories release it`() {
        bookmarks.close()

        val id = history.addHistory("Page", "https://shared.example/page")

        assertTrue(id > 0L)
        assertEquals(1, history.getAllHistory().size)
    }

    @Test
    fun `bookmark upsert preserves id and creation time`() {
        val firstId = bookmarks.addBookmark("Old title", "https://bookmark.example")
        val first = bookmarks.getAllBookmarks().single()

        val secondId = bookmarks.addBookmark("New title", "https://bookmark.example")
        val second = bookmarks.getAllBookmarks().single()

        assertEquals(firstId, secondId)
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals("New title", second.title)
    }

    @Test
    fun `like metacharacters are searched literally`() {
        bookmarks.addBookmark("100% coverage", "https://example.com/under_score")
        bookmarks.addBookmark("ordinary", "https://ordinary.example/page")

        assertEquals(
            listOf("https://example.com/under_score"),
            bookmarks.searchBookmarks("%").map { it.url },
        )
        assertEquals(
            listOf("https://example.com/under_score"),
            bookmarks.searchBookmarks("_").map { it.url },
        )
        assertTrue(bookmarks.searchBookmarks("!").isEmpty())
    }

    @Test
    fun `repeated history visits coalesce and increment the counter`() {
        history.addHistory("First title", "https://history.example/page")
        history.addHistory("Updated title", "https://history.example/page")

        val entries = history.getAllHistory()
        assertEquals(1, entries.size)
        assertEquals("Updated title", entries.single().title)
        assertEquals(2, entries.single().visitCount)
    }

    @Test
    fun `invalid limits return no rows and do not reach SQLite`() {
        bookmarks.addBookmark("Page", "https://limits.example")
        history.addHistory("Page", "https://limits.example/history")

        assertTrue(bookmarks.getAllBookmarks(-1).isEmpty())
        assertTrue(history.getAllHistory(-1).isEmpty())
        assertTrue(bookmarks.searchBookmarks("Page", 0).isEmpty())
        assertTrue(history.searchHistory("Page", 0).isEmpty())
        assertFalse(bookmarks.getAllBookmarks(999).isEmpty())
        assertFalse(history.getAllHistory(999).isEmpty())
        assertNotEquals(-1L, bookmarks.addBookmark("", "https://fallback-title.example"))
    }
}
