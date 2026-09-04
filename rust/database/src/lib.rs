use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jlong, jstring};
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Bookmark {
    id: i64,
    title: String,
    url: String,
    favicon_url: Option<String>,
    created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct HistoryEntry {
    id: i64,
    title: String,
    url: String,
    visit_time: i64,
    visit_count: i32,
}

struct DatabaseManager {
    conn: Connection,
    bookmark_cache: Arc<RwLock<HashMap<String, Bookmark>>>,
    history_cache: Arc<RwLock<HashMap<String, HistoryEntry>>>,
}

static mut DB_MANAGER: Option<DatabaseManager> = None;

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeInitDatabase(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jboolean {
    let path: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    match Connection::open(&path) {
        Ok(conn) => {
            if let Err(_) = setup_tables(&conn) {
                return 0;
            }

            unsafe {
                DB_MANAGER = Some(DatabaseManager {
                    conn,
                    bookmark_cache: Arc::new(RwLock::new(HashMap::new())),
                    history_cache: Arc::new(RwLock::new(HashMap::new())),
                });
            }
            1
        }
        Err(_) => 0,
    }
}

fn setup_tables(conn: &Connection) -> rusqlite::Result<()> {
    // Create FTS5 virtual table for bookmarks
    conn.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS bookmarks_fts USING fts5(
            title, url, content='bookmarks', content_rowid='id'
        )",
        [],
    )?;

    // Create trigger to keep FTS index updated
    conn.execute(
        "CREATE TRIGGER IF NOT EXISTS bookmarks_ai AFTER INSERT ON bookmarks BEGIN
            INSERT INTO bookmarks_fts(rowid, title, url) VALUES (new.id, new.title, new.url);
        END",
        [],
    )?;

    conn.execute(
        "CREATE TRIGGER IF NOT EXISTS bookmarks_ad AFTER DELETE ON bookmarks BEGIN
            DELETE FROM bookmarks_fts WHERE rowid = old.id;
        END",
        [],
    )?;

    // Create FTS5 virtual table for history
    conn.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS history_fts USING fts5(
            title, url, content='history', content_rowid='id'
        )",
        [],
    )?;

    conn.execute(
        "CREATE TRIGGER IF NOT EXISTS history_ai AFTER INSERT ON history BEGIN
            INSERT INTO history_fts(rowid, title, url) VALUES (new.id, new.title, new.url);
        END",
        [],
    )?;

    conn.execute(
        "CREATE TRIGGER IF NOT EXISTS history_ad AFTER DELETE ON history BEGIN
            DELETE FROM history_fts WHERE rowid = old.id;
        END",
        [],
    )?;

    Ok(())
}

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeIsBookmarked(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jboolean {
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    unsafe {
        if let Some(ref manager) = DB_MANAGER {
            // Check cache first
            if manager.bookmark_cache.read().unwrap().contains_key(&url_str) {
                return 1;
            }

            // Query database
            let result = manager.conn.query_row(
                "SELECT COUNT(*) FROM bookmarks WHERE url = ?1",
                params![url_str],
                |row| row.get::<_, i64>(0),
            );

            match result {
                Ok(count) => if count > 0 { 1 } else { 0 },
                Err(_) => 0,
            }
        } else {
            0
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeSearchBookmarks(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
) -> jstring {
    let query_str: String = match env.get_string(&query) {
        Ok(s) => s.into(),
        Err(_) => return JObject::null().into_raw(),
    };

    let results = unsafe {
        if let Some(ref manager) = DB_MANAGER {
            // Use FTS5 for fast full-text search
            let mut stmt = match manager.conn.prepare(
                "SELECT b.id, b.title, b.url, b.favicon_url, b.created_at
                 FROM bookmarks b
                 JOIN bookmarks_fts ON b.id = bookmarks_fts.rowid
                 WHERE bookmarks_fts MATCH ?1
                 ORDER BY rank LIMIT 50"
            ) {
                Ok(s) => s,
                Err(_) => return JObject::null().into_raw(),
            };

            let bookmarks: Vec<Bookmark> = stmt
                .query_map(params![query_str], |row| {
                    Ok(Bookmark {
                        id: row.get(0)?,
                        title: row.get(1)?,
                        url: row.get(2)?,
                        favicon_url: row.get(3)?,
                        created_at: row.get(4)?,
                    })
                })
                .ok()
                .map(|rows| rows.filter_map(Result::ok).collect())
                .unwrap_or_default();

            bookmarks
        } else {
            Vec::new()
        }
    };

    let json = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());
    let output = env.new_string(json).expect("Failed to create string");
    output.into_raw()
}

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeSearchHistory(
    mut env: JNIEnv,
    _class: JClass,
    query: JString,
) -> jstring {
    let query_str: String = match env.get_string(&query) {
        Ok(s) => s.into(),
        Err(_) => return JObject::null().into_raw(),
    };

    let results = unsafe {
        if let Some(ref manager) = DB_MANAGER {
            let mut stmt = match manager.conn.prepare(
                "SELECT h.id, h.title, h.url, h.visit_time, h.visit_count
                 FROM history h
                 JOIN history_fts ON h.id = history_fts.rowid
                 WHERE history_fts MATCH ?1
                 ORDER BY h.visit_time DESC LIMIT 50"
            ) {
                Ok(s) => s,
                Err(_) => return JObject::null().into_raw(),
            };

            let history: Vec<HistoryEntry> = stmt
                .query_map(params![query_str], |row| {
                    Ok(HistoryEntry {
                        id: row.get(0)?,
                        title: row.get(1)?,
                        url: row.get(2)?,
                        visit_time: row.get(3)?,
                        visit_count: row.get(4)?,
                    })
                })
                .ok()
                .map(|rows| rows.filter_map(Result::ok).collect())
                .unwrap_or_default();

            history
        } else {
            Vec::new()
        }
    };

    let json = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());
    let output = env.new_string(json).expect("Failed to create string");
    output.into_raw()
}

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeAddToCache(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    id: jlong,
) {
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    unsafe {
        if let Some(ref manager) = DB_MANAGER {
            manager.bookmark_cache.write().unwrap().insert(
                url_str.clone(),
                Bookmark {
                    id,
                    title: String::new(),
                    url: url_str,
                    favicon_url: None,
                    created_at: 0,
                },
            );
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeRemoveFromCache(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) {
    let url_str: String = match env.get_string(&url) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    unsafe {
        if let Some(ref manager) = DB_MANAGER {
            manager.bookmark_cache.write().unwrap().remove(&url_str);
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_com_mybrowser_rust_DatabaseManager_nativeClearCache(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe {
        if let Some(ref manager) = DB_MANAGER {
            manager.bookmark_cache.write().unwrap().clear();
            manager.history_cache.write().unwrap().clear();
        }
    }
}
