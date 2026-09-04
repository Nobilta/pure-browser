package com.mybrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Repository for browsing history. */
class HistoryManager(context: Context) {

    private val db = BrowserDatabase.acquire(context)
    private var closed = false

    /**
     * Adds a visit, coalescing repeated visits to the same URL into one row. The update is
     * performed in one SQL statement so the timestamp and counter cannot drift apart.
     */
    @Synchronized
    fun addHistory(title: String, url: String): Long {
        if (closed) return -1L
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty() || cleanUrl.length > SqlLike.MAX_URL_LENGTH) return -1L
        val cleanTitle = title.trim().take(SqlLike.MAX_TITLE_LENGTH).ifBlank { cleanUrl }
        val database = db.writableDatabase
        val now = System.currentTimeMillis()

        database.beginTransaction()
        return try {
            val updated = ContentValues().apply {
                put("title", cleanTitle)
                put("visit_time", now)
            }
            val changed = database.update(
                "history",
                updated,
                "url = ?",
                arrayOf(cleanUrl),
            )
            val id = if (changed > 0) {
                database.execSQL(
                    "UPDATE history SET visit_count = COALESCE(visit_count, 1) + 1 WHERE url = ?",
                    arrayOf(cleanUrl),
                )
                findId(database, cleanUrl)
            } else {
                val values = ContentValues().apply {
                    put("title", cleanTitle)
                    put("url", cleanUrl)
                    put("visit_time", now)
                    put("visit_count", 1)
                }
                database.insert("history", null, values)
            }
            database.setTransactionSuccessful()
            id
        } catch (_: RuntimeException) {
            -1L
        } finally {
            database.endTransaction()
        }
    }

    /** Gets all history entries, ordered by visit time descending; 0 means no limit. */
    @Synchronized
    fun getAllHistory(limit: Int = 0): List<HistoryEntry> {
        if (closed || limit < 0) return emptyList()
        val result = mutableListOf<HistoryEntry>()
        db.readableDatabase.query(
            "history",
            COLUMNS,
            null,
            null,
            null,
            null,
            "visit_time DESC",
            limitClause(limit),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toHistoryEntry())
        }
        return result
    }

    /** Searches history by title or URL with escaped substring matching and a hard cap. */
    @Synchronized
    fun searchHistory(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<HistoryEntry> {
        if (closed || query.isBlank() || limit <= 0) return emptyList()
        val result = mutableListOf<HistoryEntry>()
        val pattern = SqlLike.pattern(query)
        val boundedLimit = limit.coerceAtMost(SqlLike.MAX_RESULTS)
        db.readableDatabase.query(
            "history",
            COLUMNS,
            "title LIKE ?${SqlLike.ESCAPE_CLAUSE} OR url LIKE ?${SqlLike.ESCAPE_CLAUSE}",
            arrayOf(pattern, pattern),
            null,
            null,
            "visit_time DESC",
            boundedLimit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toHistoryEntry())
        }
        return result
    }

    @Synchronized
    fun removeHistory(id: Long): Int {
        if (closed) return 0
        return db.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun removeHistoryByUrl(url: String): Int {
        if (closed) return 0
        return db.writableDatabase.delete("history", "url = ?", arrayOf(url.trim()))
    }

    @Synchronized
    fun clearAll() {
        if (!closed) db.writableDatabase.delete("history", null, null)
    }

    /** Gets the most recent history entries (useful for autocomplete). */
    @Synchronized
    fun getRecentHistory(limit: Int = 10): List<HistoryEntry> =
        getAllHistory(limit.coerceAtLeast(0))

    private fun findId(database: SQLiteDatabase, url: String): Long =
        database.query(
            "history",
            arrayOf("id"),
            "url = ?",
            arrayOf(url),
            null,
            null,
            "visit_time DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }

    private fun limitClause(limit: Int): String? =
        if (limit == 0) null else limit.coerceAtMost(SqlLike.MAX_RESULTS).toString()

    private fun Cursor.toHistoryEntry() = HistoryEntry(
        id = getLong(0),
        title = getString(1),
        url = getString(2),
        visitTime = getLong(3),
        visitCount = getInt(4).coerceAtLeast(1),
    )

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        BrowserDatabase.release(db)
    }

    private companion object {
        val COLUMNS = arrayOf("id", "title", "url", "visit_time", "visit_count")
        const val DEFAULT_SEARCH_LIMIT = 50
    }
}
