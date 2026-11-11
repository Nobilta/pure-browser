package com.mybrowser.ui.library

import androidx.compose.runtime.mutableLongStateOf
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
    var folderId by mutableLongStateOf(0L)
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
    private var foldersLoaded = false

    // Typing a search only needs the folder list for path labels, which is loaded once when the
    // sheet opens. Refreshing the whole folder table on every keystroke was the expensive part.
    fun search(value: String) { if (!busy) { pager.search(value); ensureFolders() } }
    fun refresh() { pager.refresh(); readFolders() }
    fun loadMore() { if (!busy) pager.loadMore() }
    fun openFolder(id: Long) { if (!busy) { folderId = id; search("") } }

    private fun ensureFolders() { if (!foldersLoaded) readFolders() }

    private fun readFolders() {
        val generation = ++folderGeneration
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.getFolders() }
                if (generation == folderGeneration) {
                    folders = snapshot
                    foldersLoaded = true
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

    fun moveRelative(id: Long, folder: Boolean, anchorId: Long, after: Boolean) {
        if (busy || query.isNotBlank()) return
        busy = true
        ++folderGeneration
        pager.pauseLoading()
        val directory = folderId
        val count = entries.size.coerceAtLeast(50)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.moveRelative(id, folder, directory, anchorId, after) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
            finally {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        repository.getFolders() to repository.getFolderBookmarks(directory, count + 1)
                    }
                    if (folderId == directory) {
                        folders = snapshot.first
                        pager.replaceLoaded(snapshot.second.take(count), snapshot.second.size > count)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
                finally { busy = false }
            }
        }
    }
}
