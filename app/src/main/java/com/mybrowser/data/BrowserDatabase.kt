package com.mybrowser.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database for bookmarks and history.
 *
 * Bookmarks table:
 * - id: INTEGER PRIMARY KEY
 * - title: TEXT
 * - url: TEXT UNIQUE
 * - favicon_url: TEXT
 * - created_at: INTEGER (timestamp)
 *
 * History table:
 * - id: INTEGER PRIMARY KEY
 * - title: TEXT
 * - url: TEXT
 * - visit_time: INTEGER (timestamp)
 */
class BrowserDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                favicon_url TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                visit_time INTEGER NOT NULL,
                visit_count INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )

        // Index on history.visit_time for fast descending queries
        db.execSQL("CREATE INDEX idx_history_time ON history(visit_time DESC)")

        // Index on history.url for fast lookups
        db.execSQL("CREATE INDEX idx_history_url ON history(url)")
        addOrganizationAndSearch(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop browsing data during an app upgrade. Version 2 only adds the
        // optional visit counter used by omnibar suggestions; older installations retain
        // every bookmark and history row.
        if (oldVersion < 2) {
            runCatching {
                db.execSQL(
                    "ALTER TABLE history ADD COLUMN visit_count INTEGER NOT NULL DEFAULT 1",
                )
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON history(visit_time DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)")
        }
        if (oldVersion < 3) addOrganizationAndSearch(db)
    }

    private fun addOrganizationAndSearch(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmark_folders (id INTEGER PRIMARY KEY AUTOINCREMENT, parent_id INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_folder_parent ON bookmark_folders(parent_id, position, id)")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE bookmarks SET position = -id")
        db.execSQL("CREATE INDEX idx_bookmark_folder ON bookmarks(folder_id, position, id)")
        db.execSQL("CREATE INDEX idx_bookmark_order ON bookmarks(created_at DESC, id DESC)")
        db.execSQL("DROP INDEX IF EXISTS idx_history_time")
        db.execSQL("CREATE INDEX idx_history_time ON history(visit_time DESC, id DESC)")
        for (table in listOf("bookmarks", "history")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN host TEXT NOT NULL DEFAULT ''")
            db.query(table, arrayOf("id", "url"), null, null, null, null, null).use { rows ->
                while (rows.moveToNext()) db.execSQL("UPDATE $table SET host = ? WHERE id = ?",
                    arrayOf(SearchKey.host(rows.getString(1)), rows.getLong(0)))
            }
            db.execSQL("CREATE INDEX idx_${table}_host ON $table(host COLLATE NOCASE)")
            db.execSQL("CREATE INDEX idx_${table}_title ON $table(title COLLATE NOCASE)")
            db.execSQL("CREATE INDEX idx_${table}_prefix ON $table(url COLLATE NOCASE)")
        }
    }

    companion object {
        private const val DATABASE_NAME = "browser.db"
        private const val DATABASE_VERSION = 3

        private val lock = Any()
        private var shared: BrowserDatabase? = null
        private var references = 0

        /**
         * Returns the process-local helper used by bookmark and history repositories.
         * SQLiteOpenHelper already serialises access, so two independent helpers for the
         * same file only add connection/setup overhead and make close ordering fragile.
         */
        fun acquire(context: Context): BrowserDatabase = synchronized(lock) {
            val helper = shared ?: BrowserDatabase(context.applicationContext).also { shared = it }
            references++
            helper
        }

        /** Releases one repository's reference without closing a helper still in use. */
        fun release(database: BrowserDatabase) = synchronized(lock) {
            if (shared === database) {
                references = (references - 1).coerceAtLeast(0)
                if (references == 0) {
                    shared = null
                    database.close()
                }
            } else {
                // Defensive fallback for a helper created by an older integration.
                database.close()
            }
        }
    }
}

/** Shared bounds/escaping rules for user-controlled SQLite search input. */
internal object SqlLike {
    const val MAX_QUERY_LENGTH = 256
    const val MAX_TITLE_LENGTH = 512
    const val MAX_URL_LENGTH = 8_192
    const val MAX_RESULTS = 200

    fun limitClause(limit: Int, offset: Int = 0): String? {
        if (limit == 0 && offset == 0) return null
        val count = if (limit == 0) Int.MAX_VALUE else limit.coerceIn(1, MAX_RESULTS)
        return "$offset,$count"
    }

    /** Escapes LIKE metacharacters while keeping substring matching semantics. */
    fun pattern(query: String): String {
        val value = query.trim().take(MAX_QUERY_LENGTH)
        val escaped = buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '!' -> append("!!")
                    '%', '_' -> append('!').append(char)
                    else -> append(char)
                }
            }
        }
        return "%$escaped%"
    }

    fun prefix(query: String): String = pattern(query).removePrefix("%")

    const val ESCAPE_CLAUSE = " ESCAPE '!'"
}

internal object SearchKey {
    fun host(url: String): String = runCatching { android.net.Uri.parse(url).host.orEmpty()
        .lowercase(java.util.Locale.ROOT).removePrefix("www.").trimEnd('.') }.getOrDefault("")
    fun input(query: String): String = query.trim().lowercase(java.util.Locale.ROOT)
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
}
