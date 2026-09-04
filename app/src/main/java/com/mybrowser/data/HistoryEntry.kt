package com.mybrowser.data

/**
 * A history entry.
 */
data class HistoryEntry(
    val id: Long = 0,
    val title: String,
    val url: String,
    val visitTime: Long = System.currentTimeMillis(),
    /** Number of visits represented by this row (kept for suggestion ranking). */
    val visitCount: Int = 1,
)
