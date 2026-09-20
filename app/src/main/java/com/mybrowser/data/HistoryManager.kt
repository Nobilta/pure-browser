package com.mybrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** One row restored from a backup file; visit time and count are preserved, not re-stamped. */
data class ImportedHistory(val title: String, val url: String, val visitTime: Long, val visitCount: Int) {
    companion object {
        /** Upper bound for a restored counter; a crafted file must not store absurd values. */
        const val MAX_VISIT_COUNT = 1_000_000
    }
}

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
                put("host", SearchKey.host(cleanUrl))
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
                    put("host", SearchKey.host(cleanUrl))
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
    fun getAllHistory(limit: Int = 0, offset: Int = 0): List<HistoryEntry> {
        if (closed || limit < 0 || offset < 0) return emptyList()
        val result = mutableListOf<HistoryEntry>()
        db.readableDatabase.query(
            "history",
            COLUMNS,
            null,
            null,
            null,
            null,
            "visit_time DESC, id DESC",
            SqlLike.limitClause(limit, offset),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toHistoryEntry())
        }
        return result
    }

    /** Searches history by title or URL with escaped substring matching and a hard cap. */
    @Synchronized
    fun searchHistory(query: String, limit: Int = DEFAULT_SEARCH_LIMIT, offset: Int = 0): List<HistoryEntry> {
        if (closed || query.isBlank() || limit <= 0 || offset < 0) return emptyList()
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
            "visit_time DESC, id DESC",
            SqlLike.limitClause(boundedLimit, offset),
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
    fun clearAll() {
        if (!closed) db.writableDatabase.delete("history", null, null)
    }

    @Synchronized
    fun clearSince(sinceMs: Long) {
        check(!closed) { "History database closed" }
        db.writableDatabase.delete("history", "visit_time >= ?", arrayOf(sinceMs.toString()))
    }

    @Synchronized
    fun suggestions(query: String): List<HistoryEntry> {
        if (closed || query.isBlank()) return emptyList()
        val prefix = SqlLike.prefix(SearchKey.input(query))
        val first = db.readableDatabase.query("history", COLUMNS,
            "host LIKE ?${SqlLike.ESCAPE_CLAUSE} OR title LIKE ?${SqlLike.ESCAPE_CLAUSE} OR url LIKE ?${SqlLike.ESCAPE_CLAUSE}",
            arrayOf(prefix, SqlLike.prefix(query), SqlLike.prefix(query)), null, null,
            "visit_time DESC, id DESC", "30").use { c -> buildList { while (c.moveToNext()) add(c.toHistoryEntry()) } }
        return (first + searchHistory(query, 20)).distinctBy { it.id }
    }

    /**
     * Most recent entries for a backup file. Unlike [getAllHistory] the limit is not
     * clamped to search-result bounds: the caller owns the file-size budget.
     */
    @Synchronized
    fun backupHistory(limit: Int): List<HistoryEntry> {
        if (closed || limit <= 0) return emptyList()
        return db.readableDatabase.query("history", COLUMNS, null, null, null, null,
            "visit_time DESC, id DESC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toHistoryEntry()) }
        }
    }

    @Synchronized
    fun countHistory(): Int {
        if (closed) return 0
        return db.readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Restores imported rows in one transaction. Import is a merge, never a replace: a
     * URL that already exists keeps the later visit time and the larger visit count, so
     * importing the same file twice cannot duplicate rows or inflate counters.
     *
     * @return how many URLs were inserted; existing ones are merged instead.
     */
    @Synchronized
    fun importHistory(entries: List<ImportedHistory>): Int {
        if (closed || entries.isEmpty()) return 0
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            var inserted = 0
            entries.forEach { entry ->
                val url = entry.url.trim()
                require(url.isNotEmpty() && url.length <= SqlLike.MAX_URL_LENGTH) { "History URL out of range" }
                require(entry.visitTime > 0) { "History visit time must be positive" }
                require(entry.visitCount in 1..ImportedHistory.MAX_VISIT_COUNT) { "History visit count out of range" }
                val existing = findId(database, url)
                if (existing < 0) {
                    val values = ContentValues().apply {
                        put("title", entry.title.trim().take(SqlLike.MAX_TITLE_LENGTH).ifBlank { url })
                        put("url", url)
                        put("host", SearchKey.host(url))
                        put("visit_time", entry.visitTime)
                        put("visit_count", entry.visitCount)
                    }
                    if (database.insert("history", null, values) != -1L) inserted++
                } else {
                    database.execSQL(
                        "UPDATE history SET visit_time = MAX(visit_time, ?), " +
                            "visit_count = MAX(COALESCE(visit_count, 1), ?) WHERE id = ?",
                        arrayOf(entry.visitTime, entry.visitCount, existing),
                    )
                }
            }
            database.setTransactionSuccessful()
            return inserted
        } finally {
            database.endTransaction()
        }
    }

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
