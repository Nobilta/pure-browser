package com.mybrowser.core

import android.os.SystemClock
import android.webkit.WebResourceRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * A small, bounded request timeline for the in-app developer tools.
 *
 * WebView does not expose successful response callbacks from
 * [WebViewClient.shouldInterceptRequest] without taking ownership of the response body.
 * Recording at request time and completing entries when an HTTP/load error arrives keeps
 * the browser's normal streaming path intact. Successful subresources therefore have an
 * unknown status/size until Chromium reports more information; that is preferable to
 * buffering every image and script just to make a diagnostics panel look complete.
 */
data class NetworkRequestLog(
    val id: Long,
    val method: String,
    val url: String,
    val statusCode: Int? = null,
    val blocked: Boolean = false,
    val errorCode: Int? = null,
    val errorDescription: String? = null,
    val isForMainFrame: Boolean = false,
    val startedAtElapsedMs: Long = 0L,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
) {
    val statusText: String
        get() = when {
            blocked -> "已拦截"
            statusCode != null -> statusCode.toString()
            errorCode != null -> "错误 $errorCode"
            else -> "—"
        }

    val sizeText: String
        get() = sizeBytes?.let(::formatBytes) ?: "大小未知"

    val durationText: String
        get() = durationMs?.let { "$it ms" } ?: "进行中"

    private companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var index = -1
            while (value >= 1024.0 && index < units.lastIndex) {
                value /= 1024.0
                index++
            }
            return if (value >= 10 || value == value.toLong().toDouble()) {
                "%.0f %s".format(java.util.Locale.US, value, units[index])
            } else {
                "%.1f %s".format(java.util.Locale.US, value, units[index])
            }
        }
    }
}

/** Thread-safe, bounded state used by WebView worker callbacks and Compose. */
class NetworkLogStore(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val _entries = MutableStateFlow<List<NetworkRequestLog>>(emptyList())
    val entries: StateFlow<List<NetworkRequestLog>> = _entries.asStateFlow()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Starts a new document timeline and adds its main-frame navigation. */
    fun beginPage(url: String) {
        val normalized = url.trim().take(MAX_URL_LENGTH)
        if (normalized.isEmpty()) {
            clear()
            return
        }
        synchronized(lock) {
            _entries.value = listOf(
                NetworkRequestLog(
                    id = nextId.getAndIncrement(),
                    method = "GET",
                    url = normalized,
                    isForMainFrame = true,
                    startedAtElapsedMs = SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    /** Records a request before the filter gets a chance to return a replacement response. */
    fun recordRequest(request: WebResourceRequest): Long {
        val url = request.url.toString().trim().take(MAX_URL_LENGTH)
        val method = request.method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        val now = SystemClock.elapsedRealtime()

        synchronized(lock) {
            // beginPage() cannot see the WebResourceRequest itself. Coalesce the synthetic
            // main-frame row with Chromium's corresponding callback when it arrives shortly
            // afterwards, instead of showing the document twice.
            if (request.isForMainFrame) {
                val existingIndex = _entries.value.indexOfLast {
                    it.isForMainFrame && it.method == method && it.url == url &&
                        it.statusCode == null && !it.blocked &&
                        now - it.startedAtElapsedMs in 0..MAIN_FRAME_COALESCE_MS
                }
                if (existingIndex >= 0) return _entries.value[existingIndex].id
            }

            val entry = NetworkRequestLog(
                id = nextId.getAndIncrement(),
                method = method,
                url = url,
                isForMainFrame = request.isForMainFrame,
                startedAtElapsedMs = now,
            )
            publishLocked(_entries.value + entry)
            return entry.id
        }
    }

    fun markBlocked(id: Long) {
        updateById(id) { entry ->
            entry.copy(
                statusCode = 200,
                blocked = true,
                durationMs = elapsedSince(entry),
            )
        }
    }

    fun markHttpError(
        request: WebResourceRequest,
        statusCode: Int,
        reasonPhrase: String?,
        responseHeaders: Map<String, String>? = null,
    ) {
        updateLatest(request) { entry ->
            entry.copy(
                statusCode = statusCode.takeIf { it > 0 },
                errorDescription = reasonPhrase?.trim()?.take(MAX_ERROR_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                durationMs = elapsedSince(entry),
                sizeBytes = responseHeaders.contentLengthOrNull() ?: entry.sizeBytes,
            )
        }
    }

    fun markResourceError(request: WebResourceRequest, errorCode: Int, description: String?) {
        updateLatest(request) { entry ->
            entry.copy(
                errorCode = errorCode,
                errorDescription = description?.trim()?.take(MAX_ERROR_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                durationMs = elapsedSince(entry),
            )
        }
    }

    fun markMainFrameFinished(url: String) {
        updateLatestByUrl(url, mainFrameOnly = true) { entry ->
            entry.copy(
                statusCode = entry.statusCode ?: 200,
                durationMs = elapsedSince(entry),
            )
        }
    }

    fun markMainFrameError(url: String, errorCode: Int, description: String?) {
        updateLatestByUrl(url, mainFrameOnly = true) { entry ->
            entry.copy(
                errorCode = errorCode,
                errorDescription = description?.trim()?.take(MAX_ERROR_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                durationMs = elapsedSince(entry),
            )
        }
    }

    private fun updateById(id: Long, transform: (NetworkRequestLog) -> NetworkRequestLog) {
        synchronized(lock) {
            val index = _entries.value.indexOfLast { it.id == id }
            if (index < 0) return
            val next = _entries.value.toMutableList()
            next[index] = transform(next[index])
            _entries.value = next
        }
    }

    private fun updateLatest(
        request: WebResourceRequest,
        transform: (NetworkRequestLog) -> NetworkRequestLog,
    ) {
        val url = request.url.toString().trim().take(MAX_URL_LENGTH)
        val method = request.method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        synchronized(lock) {
            val index = _entries.value.indexOfLast {
                it.url == url && it.method == method &&
                    it.statusCode == null && !it.blocked && it.errorCode == null
            }.takeIf { it >= 0 } ?: _entries.value.indexOfLast {
                it.url == url && it.method == method
            }
            if (index < 0) return
            val next = _entries.value.toMutableList()
            next[index] = transform(next[index])
            _entries.value = next
        }
    }

    private fun updateLatestByUrl(
        url: String,
        mainFrameOnly: Boolean,
        transform: (NetworkRequestLog) -> NetworkRequestLog,
    ) {
        val boundedUrl = url.trim().take(MAX_URL_LENGTH)
        synchronized(lock) {
            val index = _entries.value.indexOfLast {
                it.url == boundedUrl && (!mainFrameOnly || it.isForMainFrame) &&
                    it.statusCode == null && !it.blocked && it.errorCode == null
            }
            if (index < 0) return
            val next = _entries.value.toMutableList()
            next[index] = transform(next[index])
            _entries.value = next
        }
    }

    private fun publishLocked(entries: List<NetworkRequestLog>) {
        _entries.value = if (entries.size <= maxEntries) entries else entries.takeLast(maxEntries)
    }

    private fun elapsedSince(entry: NetworkRequestLog): Long =
        (SystemClock.elapsedRealtime() - entry.startedAtElapsedMs).coerceAtLeast(0L)

    private fun Map<String, String>?.contentLengthOrNull(): Long? =
        this?.entries
            ?.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 200
        const val MAIN_FRAME_COALESCE_MS = 2_000L
        const val MAX_URL_LENGTH = 8_192
        const val MAX_METHOD_LENGTH = 16
        const val MAX_ERROR_LENGTH = 512
    }
}
