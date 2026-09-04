package com.mybrowser.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * Native LRU cache for thumbnails and favicons.
 *
 * Uses Rust implementation for:
 * - Lower memory overhead (no Java object headers)
 * - Reduced GC pressure
 * - Thread-safe access
 * - Efficient LRU eviction
 */
class NativeCache private constructor(
    private var handle: Long,
    private val capacityBytes: Long
) : Closeable {

    /** The native handle is a raw pointer; every access shares this lifecycle lock. */
    private val handleLock = Any()

    /**
     * Get cached bitmap by key.
     */
    fun getBitmap(key: String): Bitmap? {
        val data = get(key) ?: return null
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    /**
     * Put bitmap into cache.
     */
    fun putBitmap(key: String, bitmap: Bitmap): Boolean {
        // Compression happens before the native size check.  Reject pathological bitmaps
        // up front so an accidental full-resolution screenshot cannot allocate an
        // unbounded temporary PNG on the Java heap.
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 ||
            bitmap.width.toLong() * bitmap.height.toLong() > MAX_BITMAP_PIXELS
        ) return false

        val stream = ByteArrayOutputStream()
        val compressed = runCatching {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }.getOrDefault(false)
        if (!compressed) return false
        val data = stream.toByteArray()

        return put(key, data)
    }

    /**
     * Get raw bytes by key.
     */
    fun get(key: String): ByteArray? {
        if (!validKey(key)) return null
        return synchronized(handleLock) {
            val current = handle
            if (current == 0L) null else nativeGet(current, key)
        }
    }

    /**
     * Put raw bytes into cache.
     */
    fun put(key: String, data: ByteArray): Boolean {
        if (!validKey(key) || !validData(data)) return false
        return synchronized(handleLock) {
            val current = handle
            current != 0L && nativePut(current, key, data)
        }
    }

    /**
     * Remove entry from cache.
     */
    fun remove(key: String): Boolean {
        if (!validKey(key)) return false
        return synchronized(handleLock) {
            val current = handle
            current != 0L && nativeRemove(current, key)
        }
    }

    /**
     * Clear all entries.
     */
    fun clear() {
        synchronized(handleLock) {
            if (handle != 0L) {
                nativeClear(handle)
            }
        }
    }

    /**
     * Get number of entries in cache.
     */
    fun size(): Int {
        return synchronized(handleLock) {
            if (handle == 0L) 0 else nativeSize(handle)
        }
    }

    override fun close() {
        synchronized(handleLock) {
            if (handle != 0L) {
                nativeFree(handle)
                handle = 0L
            }
        }
    }

    private fun validKey(key: String): Boolean =
        key.isNotEmpty() && key.length <= MAX_KEY_LENGTH

    private fun validData(data: ByteArray): Boolean =
        data.size.toLong() <= capacityBytes && data.size <= MAX_ENTRY_BYTES

    companion object {
        /** Default cache size: 20 MB */
        const val DEFAULT_CAPACITY = 20 * 1024 * 1024L
        private const val MAX_CAPACITY = 512 * 1024 * 1024L
        private const val MAX_KEY_LENGTH = 1_024
        private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
        private const val MAX_BITMAP_PIXELS = 4_000_000L

        private val isAvailable: Boolean = try {
            System.loadLibrary("mybrowser_cache")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        /**
         * Create a new cache with specified capacity.
         *
         * @param capacityBytes Maximum total size in bytes
         */
        fun create(capacityBytes: Long = DEFAULT_CAPACITY): NativeCache? {
            if (!isAvailable) return null

            val safeCapacity = capacityBytes.coerceIn(1L, MAX_CAPACITY)
            val handle = nativeNew(safeCapacity)
            if (handle == 0L) return null

            return NativeCache(handle, safeCapacity)
        }

        @JvmStatic
        private external fun nativeNew(capacityBytes: Long): Long

        @JvmStatic
        private external fun nativeFree(handle: Long)

        @JvmStatic
        private external fun nativeGet(handle: Long, key: String): ByteArray?

        @JvmStatic
        private external fun nativePut(handle: Long, key: String, data: ByteArray): Boolean

        @JvmStatic
        private external fun nativeRemove(handle: Long, key: String): Boolean

        @JvmStatic
        private external fun nativeClear(handle: Long)

        @JvmStatic
        private external fun nativeSize(handle: Long): Int
    }
}
