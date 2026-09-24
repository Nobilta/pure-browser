package com.mybrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction

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
                put("host", SearchKey.host(cleanUrl))
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
                    put("position", nextPosition(database, "bookmarks", "folder_id", 0, first = true))
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
        } catch (error: RuntimeException) {
            // The caller only sees -1, which cannot tell a rejected URL from a full disk or a
            // closed database, so the reason has to reach the log for a bug report to be useful.
            Log.w(TAG, "Unable to record bookmark", error)
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
    fun getAllBookmarks(limit: Int = 0, offset: Int = 0): List<Bookmark> {
        if (closed || limit < 0 || offset < 0) return emptyList()
        val result = mutableListOf<Bookmark>()
        db.readableDatabase.query(
            "bookmarks",
            COLUMNS,
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
            SqlLike.limitClause(limit, offset),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toBookmark())
        }
        return result
    }

    /** Searches bookmarks by title or URL, with escaped substring matching and a hard cap. */
    @Synchronized
    fun searchBookmarks(query: String, limit: Int = DEFAULT_SEARCH_LIMIT, offset: Int = 0): List<Bookmark> {
        if (closed || query.isBlank() || limit <= 0 || offset < 0) return emptyList()
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
            "created_at DESC, id DESC",
            SqlLike.limitClause(boundedLimit, offset),
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toBookmark())
        }
        return result
    }

    @Synchronized
    fun clearAll() {
        if (!closed) db.writableDatabase.transaction {
            delete("bookmarks", null, null)
            delete("bookmark_folders", null, null)
        }
    }

    /** Import is one transaction; duplicates keep the user's existing title and identity. */
    @Synchronized
    fun importBookmarks(entries: List<ImportedBookmark>, folders: List<List<String>> = emptyList()): Int {
        check(!closed)
        require(entries.size <= BookmarkHtml.MAX_BOOKMARKS)
        val database = db.writableDatabase
        return database.transaction {
            val ids = HashMap<List<String>, Long>()
            ids[emptyList()] = 0
            fun folderId(path: List<String>): Long {
                require(path.size <= BookmarkFolders.MAX_DEPTH)
                var parent = 0L
                path.forEachIndexed { index, raw ->
                    val title = raw.trim()
                    require(title.isNotBlank() && title.length <= BookmarkFolders.MAX_NAME)
                    val key = path.take(index + 1)
                    parent = ids.getOrPut(key) {
                        database.query("bookmark_folders", arrayOf("id"), "parent_id = ? AND title = ?",
                            arrayOf(parent.toString(), title), null, null, "id", "1").use { cursor ->
                            if (cursor.moveToFirst()) cursor.getLong(0) else addFolder(title, parent)
                        }
                    }
                }
                return parent
            }
            require(folders.size <= BookmarkFolders.MAX_FOLDERS)
            folders.forEach(::folderId)
            var inserted = 0
            entries.forEach { entry ->
                require(entry.url.length <= SqlLike.MAX_URL_LENGTH && com.mybrowser.core.UrlUtils.isHttpUrl(entry.url))
                val values = ContentValues().apply {
                    put("title", entry.title.take(SqlLike.MAX_TITLE_LENGTH).ifBlank { entry.url })
                    put("url", entry.url)
                    put("host", SearchKey.host(entry.url))
                    val target = folderId(entry.folderPath)
                    put("folder_id", target)
                    put("position", nextPosition(database, "bookmarks", "folder_id", target))
                    put("created_at", System.currentTimeMillis())
                }
                if (database.insertWithOnConflict("bookmarks", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) inserted++
            }
            inserted
        }
    }

    @Synchronized
    fun bookmarksForExport(): List<Bookmark> {
        check(!closed)
        val entries = db.readableDatabase.query("bookmarks", COLUMNS, null, null, null, null,
            "folder_id, position, id", (BookmarkHtml.MAX_BOOKMARKS + 1).toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toBookmark()) }
        }
        check(entries.size <= BookmarkHtml.MAX_BOOKMARKS) { "Too many bookmarks to export" }
        return entries
    }

    /**
     * Rows for a backup file, capped instead of refused: a library larger than the
     * export budget should still produce a usable file. The caller reports what was
     * left out using [countBookmarks].
     */
    @Synchronized
    fun backupBookmarks(limit: Int): List<Bookmark> {
        // Same rule as the bulk import: a closed repository must not answer with an empty list,
        // because the export it feeds would look like a library with nothing in it.
        check(!closed)
        if (limit <= 0) return emptyList()
        return db.readableDatabase.query("bookmarks", COLUMNS, null, null, null, null,
            "folder_id, position, id", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toBookmark()) }
        }
    }

    @Synchronized
    fun countBookmarks(): Int {
        check(!closed)
        return db.readableDatabase.rawQuery("SELECT COUNT(*) FROM bookmarks", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Editing preserves row identity and fails atomically when another bookmark owns the URL. */
    @Synchronized
    fun updateBookmark(id: Long, title: String, url: String): Boolean {
        val cleanUrl = url.trim()
        if (closed || cleanUrl.isEmpty() || cleanUrl.length > SqlLike.MAX_URL_LENGTH) return false
        val database = db.writableDatabase
        return try {
            val previousUrl = database.query("bookmarks", arrayOf("url"), "id = ?",
                arrayOf(id.toString()), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else return false
            }
            database.update("bookmarks", ContentValues().apply {
                put("title", title.trim().take(SqlLike.MAX_TITLE_LENGTH).ifBlank { cleanUrl })
                put("url", cleanUrl)
                put("host", SearchKey.host(cleanUrl))
                if (previousUrl != cleanUrl) putNull("favicon_url")
            }, "id = ?", arrayOf(id.toString())) == 1
        } catch (_: RuntimeException) {
            false
        }
    }

    @Synchronized
    fun getFolders(): List<BookmarkFolder> {
        check(!closed)
        return db.readableDatabase.query("bookmark_folders", arrayOf("id", "parent_id", "title", "position"),
            null, null, null, null, "position, id", (BookmarkFolders.MAX_FOLDERS + 1).toString()).use { c ->
            buildList { while (c.moveToNext()) add(BookmarkFolder(c.getLong(0), c.getLong(1), c.getString(2), c.getLong(3))) }
        }.also(BookmarkFolders::validate)
    }

    @Synchronized
    fun getFolderBookmarks(folderId: Long, limit: Int = 50, offset: Int = 0): List<Bookmark> {
        check(!closed)
        return db.readableDatabase.query("bookmarks", COLUMNS, "folder_id = ?", arrayOf(folderId.toString()),
            null, null, "position, id", SqlLike.limitClause(limit, offset)).use { c ->
            buildList { while (c.moveToNext()) add(c.toBookmark()) }
        }
    }

    @Synchronized
    fun addFolder(title: String, parentId: Long = 0): Long {
        check(!closed)
        val folders = getFolders()
        val name = title.trim()
        require(name.isNotBlank() && name.length <= BookmarkFolders.MAX_NAME && folders.size < BookmarkFolders.MAX_FOLDERS)
        require(BookmarkFolders.path(parentId, folders).size < BookmarkFolders.MAX_DEPTH)
        return db.writableDatabase.insertOrThrow("bookmark_folders", null, ContentValues().apply {
            put("parent_id", parentId); put("title", name)
            put("position", nextPosition(db.writableDatabase, "bookmark_folders", "parent_id", parentId))
        })
    }

    @Synchronized
    fun updateFolder(id: Long, title: String, parentId: Long) {
        check(!closed)
        val folders = getFolders()
        require(folders.any { it.id == id })
        val name = title.trim()
        BookmarkFolders.validate(folders.map { if (it.id == id) it.copy(title = name, parentId = parentId) else it })
        db.writableDatabase.update("bookmark_folders", ContentValues().apply {
            put("title", name); put("parent_id", parentId)
        }, "id = ?", arrayOf(id.toString()))
    }

    /** Deleting a folder keeps its contents and moves them up one level. */
    @Synchronized
    fun removeFolder(id: Long) {
        check(!closed)
        val folder = getFolders().first { it.id == id }
        db.writableDatabase.transaction {
            execSQL("UPDATE bookmarks SET folder_id = ? WHERE folder_id = ?", arrayOf(folder.parentId, id))
            execSQL("UPDATE bookmark_folders SET parent_id = ? WHERE parent_id = ?", arrayOf(folder.parentId, id))
            delete("bookmark_folders", "id = ?", arrayOf(id.toString()))
        }
    }

    @Synchronized
    fun moveBookmarks(ids: Set<Long>, folderId: Long) {
        check(!closed)
        require(ids.size <= BookmarkHtml.MAX_BOOKMARKS)
        BookmarkFolders.path(folderId, getFolders())
        db.writableDatabase.transaction {
            var position = nextPosition(this, "bookmarks", "folder_id", folderId)
            ids.forEach { id ->
                update("bookmarks", ContentValues().apply { put("folder_id", folderId); put("position", position++) },
                    "id = ?", arrayOf(id.toString()))
            }
        }
    }

    @Synchronized
    fun deleteBookmarks(ids: Set<Long>) {
        check(!closed)
        require(ids.size <= BookmarkHtml.MAX_BOOKMARKS)
        db.writableDatabase.transaction { ids.forEach { delete("bookmarks", "id = ?", arrayOf(it.toString())) } }
    }

    @Synchronized
    fun reorder(id: Long, folder: Boolean, direction: Int) {
        check(!closed)
        require(direction == -1 || direction == 1)
        val table = if (folder) "bookmark_folders" else "bookmarks"
        val parent = if (folder) "parent_id" else "folder_id"
        db.writableDatabase.transaction {
            val parentId = query(table, arrayOf(parent), "id = ?", arrayOf(id.toString()), null, null, null).use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val ids = query(table, arrayOf("id"), "$parent = ?", arrayOf(parentId.toString()), null, null, "position, id").use {
                buildList { while (it.moveToNext()) add(it.getLong(0)) }.toMutableList()
            }
            val index = ids.indexOf(id)
            val next = index + direction
            if (next in ids.indices) {
                java.util.Collections.swap(ids, index, next)
                ids.forEachIndexed { i, row -> execSQL("UPDATE $table SET position = ? WHERE id = ?", arrayOf(i, row)) }
            }
        }
    }

    /** Indexed prefixes lead; bounded substring fallback preserves Chinese/URL search. */
    @Synchronized
    fun suggestions(query: String): List<Bookmark> {
        if (closed || query.isBlank()) return emptyList()
        val prefix = SqlLike.prefix(SearchKey.input(query))
        val first = db.readableDatabase.query("bookmarks", COLUMNS,
            "host LIKE ?${SqlLike.ESCAPE_CLAUSE} OR title LIKE ?${SqlLike.ESCAPE_CLAUSE} OR url LIKE ?${SqlLike.ESCAPE_CLAUSE}",
            arrayOf(prefix, SqlLike.prefix(query), SqlLike.prefix(query)), null, null,
            "created_at DESC, id DESC", "30").use { c -> buildList { while (c.moveToNext()) add(c.toBookmark()) } }
        return (first + searchBookmarks(query, 20)).distinctBy { it.id }
    }

    private fun nextPosition(database: SQLiteDatabase, table: String, parent: String, id: Long, first: Boolean = false): Long =
        database.rawQuery("SELECT COALESCE(${if (first) "MIN" else "MAX"}(position), 0) FROM $table WHERE $parent = ?", arrayOf(id.toString())).use {
            it.moveToFirst(); it.getLong(0) + if (first) -1 else 1
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

    private fun Cursor.toBookmark() = Bookmark(
        id = getLong(0),
        title = getString(1),
        url = getString(2),
        faviconUrl = getString(3),
        createdAt = getLong(4),
        folderId = getLong(5),
        position = getLong(6),
    )

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        BrowserDatabase.release(db)
    }

    private companion object {
        const val TAG = "BookmarkManager"
        val COLUMNS = arrayOf("id", "title", "url", "favicon_url", "created_at", "folder_id", "position")
        const val DEFAULT_SEARCH_LIMIT = 50
    }
}
