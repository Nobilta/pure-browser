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
    }

    companion object {
        private const val DATABASE_NAME = "browser.db"
        private const val DATABASE_VERSION = 2

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

    const val ESCAPE_CLAUSE = " ESCAPE '!'"
}
