package com.mybrowser.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One bounded SQL page at a time; replacing a query also invalidates its pending result. */
class LibraryPager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fetch: (query: String, limit: Int, offset: Int) -> List<T>,
) {
    var query by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var generation = 0L
    private var nextOffset = 0
    private var retryFromStart = false

    fun search(value: String) {
        query = value.take(256)
        entries = emptyList()
        hasMore = false
        nextOffset = 0
        load(reset = true, debounce = true)
    }

    fun refresh() = load(reset = true)

    fun loadMore() {
        if (!loading && (hasMore || error)) load(reset = error && retryFromStart)
    }

    private fun load(reset: Boolean, debounce: Boolean = false) {
        val request = ++generation
        job?.cancel()
        val currentQuery = query
        val offset = if (reset) 0 else nextOffset
        loading = true
        error = false
        job = scope.launch {
            try {
                if (debounce) delay(150)
                val page = withContext(ioDispatcher) { fetch(currentQuery, PAGE_SIZE + 1, offset) }
                if (request == generation) {
                    entries = (if (reset) page.take(PAGE_SIZE) else entries + page.take(PAGE_SIZE)).distinctBy(key)
                    hasMore = page.size > PAGE_SIZE
                    nextOffset = offset + minOf(page.size, PAGE_SIZE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    error = true
                    retryFromStart = reset
                }
            } finally {
                if (request == generation) loading = false
            }
        }
    }

    private companion object { const val PAGE_SIZE = 50 }
}
