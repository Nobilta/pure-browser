package com.mybrowser.filter

import android.util.Log
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

    /**
     * Resource kinds the engine understands. Ordinals cross the JNI boundary as ints, so the
     * declaration order here is load-bearing — it must match `resource_from_ordinal` in
     * `lib.rs`. Append new kinds at the end.
     */
    enum class ResourceType {
        DOCUMENT,
        SUBDOCUMENT,
        SCRIPT,
        STYLESHEET,
        IMAGE,
        FONT,
        MEDIA,
        XML_HTTP_REQUEST,
        PING,
        WEB_SOCKET,
        OTHER,
    }

    /** Number of network rules currently loaded. */
    val ruleCount: Int
        get() = if (handle == 0L) 0 else runCatching { nativeRuleCount(handle) }.getOrDefault(0)

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
     * Returns false whenever the answer is not a confident yes — closed engine, malformed
     * input, a panic on the native side. A filter list must never be able to take a page
     * down, so every failure mode degrades to allowing the request.
     */
    fun shouldBlock(requestUrl: String, documentUrl: String, type: ResourceType): Boolean {
        if (handle == 0L) return false
        return runCatching { nativeShouldBlock(handle, requestUrl, documentUrl, type.ordinal) }
            .getOrDefault(false)
    }

    /** Frees the native engine. Subsequent calls are no-ops that allow every request. */
    override fun close() {
        val h = handle
        handle = 0L
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
    }
}
