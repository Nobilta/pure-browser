package com.mybrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Repository for bookmarks.
 *
 * The helper is shared with [HistoryManager], while all database work remains synchronous so
 * callers can choose their dispatcher explicitly. MainActivity and suggestions already run
 * these methods on Dispatchers.IO.
 */
class BookmarkManager(context: Context) {

    private val db = BrowserDatabase.acquire(context)
    private var closed = false

    /**
     * Adds or updates a bookmark while preserving the existing row id and creation time.
     * SQLite's CONFLICT_REPLACE deletes the old row before inserting a new one, which breaks
     * references and makes ordering jump unexpectedly; an explicit upsert avoids that.
     */
    @Synchronized
    fun addBookmark(title: String, url: String, faviconUrl: String? = null): Long {
        if (closed) return -1L
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl.length > SqlLike.MAX_URL_LENGTH) return -1L
        val cleanTitle = title.trim().take(SqlLike.MAX_TITLE_LENGTH).ifBlank { cleanUrl }
        val database = db.writableDatabase

        database.beginTransaction()
        return try {
            val updateValues = ContentValues().apply {
                put("title", cleanTitle)
                put("url", cleanUrl)
                if (faviconUrl == null) putNull("favicon_url")
                else put("favicon_url", faviconUrl.trim().take(SqlLike.MAX_URL_LENGTH))
            }
            val updated = database.update(
                "bookmarks",
                updateValues,
                "url = ?",
                arrayOf(cleanUrl),
            )
            val id = if (updated > 0) {
                findId(database, cleanUrl)
            } else {
                val insertValues = ContentValues(updateValues).apply {
                    put("created_at", System.currentTimeMillis())
                }
                val inserted = database.insertWithOnConflict(
                    "bookmarks",
                    null,
                    insertValues,
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (inserted != -1L) inserted
                else {
                    // Handles a concurrent writer on older SQLite providers. Update the row
                    // that won the race and return its stable id.
                    database.update(
                        "bookmarks",
                        updateValues,
                        "url = ?",
                        arrayOf(cleanUrl),
                    )
                    findId(database, cleanUrl)
                }
            }
            database.setTransactionSuccessful()
            id
        } catch (_: RuntimeException) {
            -1L
        } finally {
            database.endTransaction()
        }
    }

    /** Checks if a URL is bookmarked. */
    @Synchronized
    fun isBookmarked(url: String): Boolean {
        if (closed) return false
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl.length > SqlLike.MAX_URL_LENGTH) return false
        db.readableDatabase.query(
            "bookmarks",
            arrayOf("id"),
            "url = ?",
            arrayOf(cleanUrl),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return cursor.moveToFirst() }
    }

    /** Removes a bookmark by URL. */
    @Synchronized
    fun removeBookmark(url: String): Int {
        if (closed) return 0
        return db.writableDatabase.delete("bookmarks", "url = ?", arrayOf(url.trim()))
    }

    /** Gets all bookmarks, ordered by creation time descending; 0 means no explicit limit. */
    @Synchronized
    fun getAllBookmarks(limit: Int = 0): List<Bookmark> {
        if (closed || limit < 0) return emptyList()
        val result = mutableListOf<Bookmark>()
        db.readableDatabase.query(
            "bookmarks",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limitClause(limit),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toBookmark())
        }
        return result
    }

    /** Searches bookmarks by title or URL, with escaped substring matching and a hard cap. */
    @Synchronized
    fun searchBookmarks(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Bookmark> {
        if (closed || query.isBlank() || limit <= 0) return emptyList()
        val result = mutableListOf<Bookmark>()
        val pattern = SqlLike.pattern(query)
        val boundedLimit = limit.coerceAtMost(SqlLike.MAX_RESULTS)
        db.readableDatabase.query(
            "bookmarks",
            COLUMNS,
            "title LIKE ?${SqlLike.ESCAPE_CLAUSE} OR url LIKE ?${SqlLike.ESCAPE_CLAUSE}",
            arrayOf(pattern, pattern),
            null,
            null,
            "created_at DESC",
            boundedLimit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toBookmark())
        }
        return result
    }

    @Synchronized
    fun clearAll() {
        if (!closed) db.writableDatabase.delete("bookmarks", null, null)
    }

    private fun findId(database: SQLiteDatabase, url: String): Long =
        database.query(
            "bookmarks",
            arrayOf("id"),
            "url = ?",
            arrayOf(url),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }

    private fun limitClause(limit: Int): String? =
        if (limit == 0) null else limit.coerceAtMost(SqlLike.MAX_RESULTS).toString()

    private fun Cursor.toBookmark() = Bookmark(
        id = getLong(0),
        title = getString(1),
        url = getString(2),
        faviconUrl = getString(3),
        createdAt = getLong(4),
    )

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        BrowserDatabase.release(db)
    }

    private companion object {
        val COLUMNS = arrayOf("id", "title", "url", "favicon_url", "created_at")
        const val DEFAULT_SEARCH_LIMIT = 50
    }
}
