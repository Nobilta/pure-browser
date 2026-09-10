package com.mybrowser.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mybrowser.data.Bookmark
import com.mybrowser.data.BookmarkFolder
import com.mybrowser.data.BookmarkFolders
import com.mybrowser.data.BookmarkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Folder navigation and mutations have one owner, independent of Activity callbacks. */
class BookmarkLibrary(private val scope: CoroutineScope, private val repository: BookmarkManager,
    private val onFailure: () -> Unit) {
    var folderId by mutableStateOf(0L)
        private set
    var folders by mutableStateOf<List<BookmarkFolder>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    private val pager = LibraryPager(scope, Bookmark::id) { query, limit, offset ->
        if (query.isBlank()) repository.getFolderBookmarks(folderId, limit, offset)
        else repository.searchBookmarks(query, limit, offset)
    }
    val entries get() = pager.entries
    val query get() = pager.query
    val loading get() = pager.loading
    val hasMore get() = pager.hasMore
    val error get() = pager.error
    val path get() = runCatching { BookmarkFolders.path(folderId, folders) }.getOrDefault(emptyList())
    private var folderGeneration = 0L

    fun search(value: String) { pager.search(value); readFolders() }
    fun refresh() { pager.refresh(); readFolders() }
    fun loadMore() = pager.loadMore()
    fun openFolder(id: Long) { if (!busy) { folderId = id; search("") } }

    private fun readFolders() {
        val generation = ++folderGeneration
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.getFolders() }
                if (generation == folderGeneration) {
                    folders = snapshot
                    if (folderId != 0L && snapshot.none { it.id == folderId }) {
                        folderId = 0L
                        pager.search("")
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }

    fun mutate(action: BookmarkManager.() -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { repository.action() }; refresh() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
            finally { busy = false }
        }
    }
}
