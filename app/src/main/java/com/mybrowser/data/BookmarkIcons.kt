package com.mybrowser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import com.mybrowser.core.Hex

/** Local WebView icons only: opening the library never contacts bookmarked sites. */
object BookmarkIcons {
    private fun directory(context: Context) = File(context.cacheDir, "bookmark-icons")
    private fun key(url: String) = Hex.sha256(url.toByteArray(Charsets.UTF_8)) + ".png"

    @Synchronized
    fun read(context: Context, url: String): Bitmap? = runCatching {
        val file = File(directory(context), key(url))
        if (!file.isFile || file.length() > 128 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..64 || bounds.outHeight !in 1..64) return null
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()

    @Synchronized
    fun write(context: Context, url: String, icon: Bitmap) {
        runCatching {
            val dir = directory(context).apply { mkdirs() }
            val file = File(dir, key(url))
            val scaled = Bitmap.createScaledBitmap(icon, 64, 64, true)
            try { file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { if (scaled !== icon) scaled.recycle() }
            // Cache can be rebuilt when a bookmark is visited again.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(512)?.forEach { it.delete() }
        }
    }
}
