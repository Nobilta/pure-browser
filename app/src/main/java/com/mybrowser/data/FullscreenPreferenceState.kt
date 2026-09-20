package com.mybrowser.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Session fallback: leaving fullscreen never waits for disk, including after rotation/resume. */
class FullscreenPreferenceState {
    private val writes = Mutex()
    private var generation = 0L
    private var exited = false

    @Synchronized fun request(enabled: Boolean): Long {
        if (!enabled) exited = true
        return ++generation
    }
    @Synchronized fun effective(saved: Boolean): Boolean = saved && !exited
    @Synchronized fun acceptImport() { generation++; exited = false }
    @Synchronized private fun current(token: Long) = generation == token

    /** Null means superseded. Enable requires a saved preference; exit remains effective on failure. */
    suspend fun persist(token: Long, enabled: Boolean, save: (Boolean) -> Boolean): Boolean? = writes.withLock {
        if (!current(token)) return@withLock null
        val saved = withContext(Dispatchers.IO) { runCatching { save(enabled) }.getOrDefault(false) }
        synchronized(this) {
            if (!current(token)) null else {
                if (saved) exited = false
                saved
            }
        }
    }
}
