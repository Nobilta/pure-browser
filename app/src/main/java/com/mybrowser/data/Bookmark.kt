package com.mybrowser.data

/**
 * A bookmark entry.
 */
data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val faviconUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
