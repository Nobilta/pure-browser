package com.mybrowser.core

import android.content.Context
import java.io.File

/**
 * The FileProvider cache the browser hands to other apps: shared images, uploads and captures.
 *
 * Each private session writes into `web-capture/private` so that its files can be removed by
 * ownership rather than by a timestamp guess. A file handed to another app is moved to
 * `web-capture/share` first: the receiving app may read it later, so that output outlives the
 * session and is reclaimed on the next process start like any other cache.
 */
object WebCaptureCache {
    private const val ROOT = "web-capture"
    private const val PRIVATE = "private"
    private const val SHARED = "share"

    fun directory(context: Context): File = directory(context, ROOT)

    /** Files a private session is still using; removed when the session ends. */
    fun privateDirectory(context: Context): File = directory(context, "$ROOT/$PRIVATE")

    /** Files already handed to another app; kept until the next process start. */
    fun shareDirectory(context: Context): File = directory(context, "$ROOT/$SHARED")

    /** Drops everything a previous process left behind. */
    fun clear(context: Context) {
        File(context.cacheDir, ROOT).deleteRecursively()
    }

    /** Drops the current private session's working files. */
    fun clearPrivate(context: Context) {
        File(context.cacheDir, "$ROOT/$PRIVATE").deleteRecursively()
    }

    private fun directory(context: Context, path: String): File =
        File(context.cacheDir, path).apply { mkdirs() }
}
