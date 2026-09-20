package com.mybrowser.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether the page on screen is bookmarked.
 *
 * The lookup runs off the main thread, and its answer is applied only while that page is still the
 * visible one: a fast navigation would otherwise leave the star describing the page just left.
 * Home pages and blank URLs are answered without touching the database.
 */
class BookmarkStatusTracker(
    private val lookup: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
    private val visibleUrl: () -> String,
    private val isHome: (String) -> Boolean,
    private val onChange: (Boolean) -> Unit,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var pending: Job? = null
    private var generation = 0L

    /** Called on Main after a confirmed database write, invalidating any older read of that URL. */
    fun setKnown(url: String, bookmarked: Boolean) {
        if (visibleUrl() != url) return
        generation++
        pending?.cancel()
        onChange(bookmarked)
    }

    fun refresh(url: String) {
        val request = ++generation
        pending?.cancel()
        if (url.isBlank() || isHome(url)) {
            onChange(false)
            return
        }
        pending = scope.launch(readDispatcher) {
            val bookmarked = try { lookup(url) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@launch }
            withContext(Dispatchers.Main) {
                if (request == generation && visibleUrl() == url) onChange(bookmarked)
            }
        }
    }
}
