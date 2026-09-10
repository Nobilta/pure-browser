package com.mybrowser.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookmarkFoldersTest {
    @Test fun upgradingVersionTwoPreservesIdsAndTimestamps() {
        val context = RuntimeEnvironment.getApplication()
        val path = context.getDatabasePath("browser.db").also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, favicon_url TEXT, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, url TEXT NOT NULL, visit_time INTEGER NOT NULL, visit_count INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("INSERT INTO bookmarks VALUES (42,'中文资料','https://www.example.com/doc',NULL,123456)")
            db.execSQL("INSERT INTO history VALUES (7,'Visited','https://example.org',654321,8)")
            db.version = 2
        }
        val bookmarks = BookmarkManager(context)
        val history = HistoryManager(context)
        try {
            val row = bookmarks.getAllBookmarks().single()
            assertEquals(42L, row.id); assertEquals(123456L, row.createdAt); assertEquals(0L, row.folderId)
            assertEquals(8, history.getAllHistory().single().visitCount)
            assertEquals(row.id, bookmarks.suggestions("example.com").single().id)
            assertEquals(row.id, bookmarks.suggestions("文资").single().id)
        } finally { bookmarks.close(); history.close() }
    }

    @Test fun moveReorderRemoveFolderAndBulkDeletePreserveUnselectedItems() {
        val manager = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            val root = manager.addFolder("Work")
            val nested = manager.addFolder("Docs", root)
            val a = manager.addBookmark("A", "https://a.test")
            val b = manager.addBookmark("B", "https://b.test")
            val keep = manager.addBookmark("Keep", "https://keep.test")
            manager.moveBookmarks(linkedSetOf(a, b), nested)
            assertEquals(listOf(a, b), manager.getFolderBookmarks(nested).map { it.id })
            manager.reorder(b, false, -1)
            assertEquals(listOf(b, a), manager.getFolderBookmarks(nested).map { it.id })
            manager.removeFolder(nested)
            assertEquals(setOf(a, b), manager.getFolderBookmarks(root).map { it.id }.toSet())
            manager.deleteBookmarks(setOf(a, b))
            assertEquals(listOf(keep), manager.getAllBookmarks().map { it.id })
        } finally { manager.close() }
    }

    @Test fun rejectsCyclesAndUnknownDestinationsWithoutMovingData() {
        val manager = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            val a = manager.addFolder("A")
            val b = manager.addFolder("B", a)
            val id = manager.addBookmark("Keep", "https://keep.test")
            assertThrows(IllegalArgumentException::class.java) { manager.updateFolder(a, "A", b) }
            assertThrows(IllegalArgumentException::class.java) { manager.moveBookmarks(setOf(id), 999) }
            assertEquals(0L, manager.getFolders().first { it.id == a }.parentId)
            assertEquals(0L, manager.getAllBookmarks().single().folderId)
        } finally { manager.close() }
    }

    @Test fun nestedAndEmptyFoldersRoundTripThroughRealRustParser() {
        val manager = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            val root = manager.addFolder("中文 & Work")
            val leaf = manager.addFolder("Docs", root)
            manager.addFolder("Empty")
            val id = manager.addBookmark("A & B", "https://docs.test/?a=1&b=2")
            manager.moveBookmarks(setOf(id), leaf)
            val parsed = BookmarkHtml.parse(BookmarkHtml.encode(manager.bookmarksForExport(), manager.getFolders()).toString(Charsets.UTF_8))
            assertEquals(listOf("中文 & Work", "Docs"), parsed.entries.single().folderPath)
            assertTrue(parsed.folders.contains(listOf("Empty")))
            manager.clearAll()
            manager.importBookmarks(parsed.entries, parsed.folders)
            assertEquals(3, manager.getFolders().size)
            val result = manager.getAllBookmarks().single()
            assertEquals(listOf("中文 & Work", "Docs"), BookmarkFolders.path(result.folderId, manager.getFolders()).map { it.title })
        } finally { manager.close() }
    }

    @Test fun invalidImportRollsBackFoldersAsWellAsBookmarks() {
        val manager = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            assertThrows(IllegalArgumentException::class.java) { manager.importBookmarks(listOf(
                ImportedBookmark("Valid", "https://valid.test", listOf("Added")),
                ImportedBookmark("Bad", "javascript:alert(1)"))) }
            assertTrue(manager.getFolders().isEmpty())
            assertTrue(manager.getAllBookmarks().isEmpty())
        } finally { manager.close() }
    }
}
