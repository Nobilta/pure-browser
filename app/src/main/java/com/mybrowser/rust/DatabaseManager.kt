package com.mybrowser.rust

import android.content.Context
import com.mybrowser.data.BookmarkManager
import com.mybrowser.data.HistoryManager

/**
 * Compatibility facade for callers that used the old Rust database API.
 *
 * The browser's canonical database is Android SQLite (it owns migrations and lifecycle),
 * so keeping a second Rust SQLite handle would create two schemas racing over the same
 * file. This facade delegates to the canonical managers while preserving the API used by
 * older integrations, and never attempts to load a library that is not packaged anymore.
 */
object DatabaseManager {

    @Volatile
    private var initialized = false
    private var bookmarks: BookmarkManager? = null
    private var history: HistoryManager? = null

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (initialized) return true
        return runCatching {
            val appContext = context.applicationContext
            bookmarks = BookmarkManager(appContext)
            history = HistoryManager(appContext)
            initialized = true
            true
        }.getOrElse {
            bookmarks = null
            history = null
            false
        }
    }

    fun isBookmarked(url: String): Boolean =
        if (!initialized) false else runCatching {
            bookmarks?.isBookmarked(url) == true
        }.getOrDefault(false)

    fun searchBookmarks(query: String): List<BookmarkResult> =
        if (!initialized) emptyList() else runCatching {
            bookmarks?.searchBookmarks(query).orEmpty().map { bookmark ->
                BookmarkResult(
                    id = bookmark.id,
                    title = bookmark.title,
                    url = bookmark.url,
                    faviconUrl = bookmark.faviconUrl,
                    createdAt = bookmark.createdAt,
                )
            }
        }.getOrDefault(emptyList())

    fun searchHistory(query: String): List<HistoryResult> =
        if (!initialized) emptyList() else runCatching {
            history?.searchHistory(query).orEmpty().map { entry ->
                HistoryResult(
                    id = entry.id,
                    title = entry.title,
                    url = entry.url,
                    visitTime = entry.visitTime,
                    visitCount = entry.visitCount,
                )
            }
        }.getOrDefault(emptyList())

    /** Legacy cache hooks are retained as no-ops; thumbnail caching lives in NativeCache. */
    fun addToCache(url: String, id: Long) = Unit
    fun removeFromCache(url: String) = Unit
    fun clearCache() = Unit

    @Synchronized
    fun close() {
        bookmarks?.close()
        history?.close()
        bookmarks = null
        history = null
        initialized = false
    }
}

data class BookmarkResult(
    val id: Long,
    val title: String,
    val url: String,
    val faviconUrl: String?,
    val createdAt: Long,
)

data class HistoryResult(
    val id: Long,
    val title: String,
    val url: String,
    val visitTime: Long,
    val visitCount: Int,
)
