package com.mybrowser.filter

import android.util.Log
import com.mybrowser.core.ResourceType
import java.io.Closeable

/**
 * Kotlin side of the Rust filter engine.
 *
 * The native symbol names encode this exact package and class name, so moving or renaming
 * this file breaks JNI resolution at the first call. Keep them in sync with
 * `rust/adblock/src/lib.rs`.
 *
 * Not thread-safe for [addList]: load every list before the first [shouldBlock]. Reads are
 * safe to make from the many threads WebView uses for subresource loading, because the
 * engine is immutable once loaded.
 */
class NativeFilter private constructor(private var handle: Long) : Closeable {
    private val documents = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 32
    }

    /** Number of network rules currently loaded. */
    val ruleCount: Int
        get() = if (handle == 0L) 0 else runCatching { nativeRuleCount(handle) }.getOrDefault(0)

    val cosmeticRuleCount: Int
        get() = if (handle == 0L) 0 else runCatching { nativeCosmeticRuleCount(handle) }.getOrDefault(0)
    val unsupportedRuleCount: Int
        get() = if (handle == 0L) 0 else runCatching { nativeUnsupportedCount(handle) }.getOrDefault(0)

    fun cosmeticCss(url: String): String = if (handle == 0L) "" else
        runCatching { nativeCosmeticCss(handle, url).orEmpty() }.getOrDefault("")

    /**
     * Parses [text] as an EasyList-syntax filter list and adds its network rules.
     *
     * Returns the total rule count after loading, or -1 if the engine has been closed or the
     * native side failed. Cosmetic rules and unsupported syntax are skipped, not an error.
     */
    fun addList(text: String): Int {
        if (handle == 0L) return -1
        return runCatching { nativeAddList(handle, text) }.getOrDefault(-1)
    }

    /**
     * Whether [requestUrl] should be blocked for a page at [documentUrl].
     *
     * Returns false for a closed engine or a Java/JNI call failure. Native aborts cannot be
     * caught by Kotlin; bounded inputs and native regression/sanitizer checks cover that boundary.
     */
    fun shouldBlock(requestUrl: String, documentUrl: String, type: ResourceType): Boolean {
        if (handle == 0L || requestUrl.length > 8192 || documentUrl.length > 8192) return false
        return runCatching {
            val id = synchronized(documents) {
                documents[documentUrl] ?: nativePrepareDocument(handle, documentUrl).also { documents[documentUrl] = it }
            }
            val result = nativeCheckDocument(handle, requestUrl, id, type.ordinal)
            if (result >= 0) result == 1
            else {
                // Another concurrent document can evict an ID between lookup and use.
                // An Arc protects in-flight native readers; this request keeps its exact URL fallback.
                synchronized(documents) { documents.remove(documentUrl) }
                nativeShouldBlock(handle, requestUrl, documentUrl, type.ordinal)
            }
        }.getOrDefault(false)
    }

    internal fun shouldBlockUncached(requestUrl: String, documentUrl: String, type: ResourceType): Boolean =
        handle != 0L && requestUrl.length <= 8192 && documentUrl.length <= 8192 && nativeShouldBlock(handle, requestUrl, documentUrl, type.ordinal)

    fun explainList(text: String, requestUrl: String, documentUrl: String, type: ResourceType): Pair<String?, String?> {
        val lines = nativeExplainList(text, requestUrl, documentUrl, type.ordinal).orEmpty().split('\n', limit = 2)
        return lines.getOrNull(0)?.takeIf { it.isNotBlank() } to lines.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** Frees the native engine. Subsequent calls are no-ops that allow every request. */
    override fun close() {
        val h = handle
        handle = 0L
        synchronized(documents) { documents.clear() }
        if (h != 0L) runCatching { nativeFree(h) }
    }

    companion object {
        private const val TAG = "NativeFilter"

        /** False when the .so could not be loaded; every filter feature then no-ops. */
        val isAvailable: Boolean = try {
            System.loadLibrary("mybrowser_adblock")
            true
        } catch (e: UnsatisfiedLinkError) {
            // Happens on an ABI the crate was not cross-compiled for. Not fatal: the browser
            // works without filtering.
            Log.w(TAG, "filter engine unavailable, ad blocking disabled", e)
            false
        }

        /** Creates an empty engine, or null if the native library is missing. */
        fun createOrNull(): NativeFilter? {
            if (!isAvailable) return null
            val handle = runCatching { nativeNew() }.getOrDefault(0L)
            if (handle == 0L) {
                Log.w(TAG, "nativeNew returned a null handle")
                return null
            }
            return NativeFilter(handle)
        }

        @JvmStatic private external fun nativeNew(): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeAddList(handle: Long, text: String): Int
        @JvmStatic private external fun nativeShouldBlock(
            handle: Long,
            requestUrl: String,
            documentUrl: String,
            resourceType: Int,
        ): Boolean
        @JvmStatic private external fun nativeRuleCount(handle: Long): Int
        @JvmStatic private external fun nativeUnsupportedCount(handle: Long): Int
        @JvmStatic private external fun nativePrepareDocument(handle: Long, url: String): Long
        @JvmStatic private external fun nativeCheckDocument(handle: Long, url: String, document: Long, type: Int): Int
        @JvmStatic private external fun nativeExplainList(text: String, url: String, document: String, type: Int): String?
        @JvmStatic private external fun nativeCosmeticRuleCount(handle: Long): Int
        @JvmStatic private external fun nativeCosmeticCss(handle: Long, url: String): String?
    }
}
