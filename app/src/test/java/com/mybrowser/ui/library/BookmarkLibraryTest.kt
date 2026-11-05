package com.mybrowser.ui.library

import android.app.Application
import com.mybrowser.data.BookmarkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookmarkLibraryTest {
    @Test fun corruptFoldersAreReportedBeforePublishingThemToTheSheet() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = BookmarkManager(context)
        val database = com.mybrowser.data.BrowserDatabase.acquire(context)
        try {
            val valid = repository.addFolder("Valid", 0)
            var failures = 0
            val library = BookmarkLibrary(this, repository) { failures++ }
            library.refresh()
            withTimeout(5000) { while (library.folders.isEmpty()) delay(10) }
            database.writableDatabase.execSQL("UPDATE bookmark_folders SET parent_id = 999 WHERE id = ?", arrayOf(valid))
            library.refresh()
            withTimeout(5000) { while (failures == 0) delay(10) }
            assertEquals(0L, library.folders.single().parentId)
            assertEquals(1, failures)
            // The bad database is not rewritten or silently exported as an empty tree.
            database.readableDatabase.rawQuery("SELECT parent_id FROM bookmark_folders WHERE id = ?", arrayOf(valid.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals(999L, it.getLong(0))
            }
        } finally { repository.close(); com.mybrowser.data.BrowserDatabase.release(database) }
    }

    @Test fun sortingALargeLoadedPrefixKeepsEveryRowAndTheNextPage() = runBlocking {
        val repository = BookmarkManager(RuntimeEnvironment.getApplication())
        try {
            val ids = (1..275).map { repository.addBookmark("Item $it", "https://large.test/$it") }.reversed()
            val library = BookmarkLibrary(this, repository) { fail("Library operation failed") }
            suspend fun idle() = withTimeout(5000) { while (library.loading || library.busy) delay(10) }
            library.refresh()
            idle()
            repeat(4) { library.loadMore(); idle() }
            assertEquals(250, library.entries.size)
            library.moveRelative(ids.first(), false, ids[240], true)
            idle()
            val ordered = ids.drop(1).toMutableList().apply { add(240, ids.first()) }
            assertEquals(ordered.take(250), library.entries.map { it.id })
            assertTrue(library.hasMore)
            library.loadMore()
            idle()
            assertEquals(ordered, library.entries.map { it.id })
            assertFalse(library.hasMore)
        } finally { repository.close() }
    }
}
