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
