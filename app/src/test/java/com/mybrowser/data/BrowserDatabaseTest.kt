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
        assertTrue(bookmarks.getAllBookmarks(50, -1).isEmpty())
        assertTrue(history.getAllHistory(50, -1).isEmpty())
        assertTrue(bookmarks.searchBookmarks("Page", 50, -1).isEmpty())
        assertTrue(history.searchHistory("Page", 50, -1).isEmpty())
        assertTrue(bookmarks.searchBookmarks("Page", 0).isEmpty())
        assertTrue(history.searchHistory("Page", 0).isEmpty())
        assertFalse(bookmarks.getAllBookmarks(999).isEmpty())
        assertFalse(history.getAllHistory(999).isEmpty())
        assertNotEquals(-1L, bookmarks.addBookmark("", "https://fallback-title.example"))
    }

    @Test
    fun `editing preserves identity and rejects duplicate URLs without data loss`() {
        val id = bookmarks.addBookmark("First", "https://first.example", "https://first.example/icon.png")
        bookmarks.addBookmark("Second", "https://second.example")
        val before = bookmarks.getAllBookmarks().first { it.id == id }
        assertFalse(bookmarks.updateBookmark(id, "Conflict", "https://second.example"))
        assertEquals(before, bookmarks.getAllBookmarks().first { it.id == id })
        assertEquals(2, bookmarks.getAllBookmarks().size)
        assertTrue(bookmarks.updateBookmark(id, "Updated", "https://updated.example"))
        val after = bookmarks.getAllBookmarks().first { it.id == id }
        assertEquals(before.createdAt, after.createdAt)
        assertEquals("Updated", after.title)
        assertEquals("https://updated.example", after.url)
        assertEquals(null, after.faviconUrl)
        assertFalse(bookmarks.isBookmarked(before.url))
    }

    @Test
    fun `paged libraries include old records without gaps or duplicates`() {
        repeat(123) { index ->
            bookmarks.addBookmark("Page $index", "https://page.example/$index")
            history.addHistory("Page $index", "https://page.example/$index")
        }
        val bookmarkPages = (0..2).flatMap { bookmarks.getAllBookmarks(50, it * 50) }
        val historyPages = (0..2).flatMap { history.getAllHistory(50, it * 50) }
        assertEquals(bookmarks.getAllBookmarks(), bookmarkPages)
        assertEquals(history.getAllHistory(), historyPages)
        assertEquals(123, bookmarkPages.map { it.id }.distinct().size)
        assertEquals(123, historyPages.map { it.id }.distinct().size)
        assertEquals(bookmarks.getAllBookmarks().drop(100), bookmarks.getAllBookmarks(0, 100))
        assertEquals(history.getAllHistory().drop(100), history.getAllHistory(0, 100))
    }

    @Test
    fun `search queries full database and paginates filtered results`() {
        bookmarks.addBookmark("Needle", "https://old.example")
        history.addHistory("Needle", "https://old.example")
        repeat(121) { index ->
            bookmarks.addBookmark("Recent $index", "https://recent.example/$index")
            history.addHistory("Recent $index", "https://recent.example/$index")
        }
        assertEquals("https://old.example", bookmarks.searchBookmarks("Needle").single().url)
        assertEquals("https://old.example", history.searchHistory("Needle").single().url)
        assertEquals(21, bookmarks.searchBookmarks("Recent", 50, 100).size)
        assertEquals(21, history.searchHistory("Recent", 50, 100).size)
    }
}
