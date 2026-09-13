package com.mybrowser.core

import com.mybrowser.R
import android.content.res.Resources
import android.os.SystemClock
import android.webkit.WebResourceRequest
import com.mybrowser.filter.NativeFilter.ResourceType
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
    val documentUrl: String = "",
    val resourceType: Int = com.mybrowser.filter.NativeFilter.ResourceType.OTHER.ordinal,
) {
    fun statusText(resources: Resources): String = when {
            blocked -> resources.getString(R.string.ui_blocked)
            statusCode != null -> statusCode.toString()
            errorCode != null -> resources.getString(R.string.ui_error, errorCode)
            else -> "—"
        }

    fun sizeText(resources: Resources): String = sizeBytes?.let(::formatBytes) ?: resources.getString(R.string.ui_unknown_size)

    fun durationText(resources: Resources): String = durationMs?.let { "$it ms" } ?: resources.getString(R.string.ui_in_progress)

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

    private val nextId = AtomicLong(1L)
    private val buffer = BufferedLog<NetworkRequestLog>(maxEntries)
    // Protected by buffer.edit's lock. Chromium may deliver the main request and its
    // subresources before the UI thread receives onPageStarted.
    private var awaitingPageStart = false
    private var syntheticMainFrameId: Long? = null
    private var timelineUrl = ""
    val entries = buffer.entries
    fun setVisible(value: Boolean) = buffer.setVisible(value)
    fun snapshot(): List<NetworkRequestLog> = buffer.snapshot()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Confirms an intercepted navigation, or starts one with no request callback yet. */
    fun beginPage(url: String) {
        val normalized = url.trim().take(MAX_URL_LENGTH)
        if (normalized.isEmpty()) {
            clear()
            return
        }
        buffer.edit { ring ->
            val intercepted = awaitingPageStart
            awaitingPageStart = false
            syntheticMainFrameId = null
            timelineUrl = normalized
            if (!intercepted) ring.clear()
            // Do not erase speculative resources just because the main-thread callback
            // arrived later. A redirect may add a final document URL to the same timeline.
            if (intercepted && ring.indexOfLast { it.isForMainFrame && it.url == normalized } >= 0) return@edit
            val entry = NetworkRequestLog(
                id = nextId.getAndIncrement(),
                method = "GET",
                url = normalized,
                isForMainFrame = true,
                documentUrl = normalized,
                resourceType = ResourceType.DOCUMENT.ordinal,
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            ring.add(entry)
            syntheticMainFrameId = entry.id
        }
    }

    fun clear() {
        buffer.edit {
            awaitingPageStart = false
            syntheticMainFrameId = null
            timelineUrl = ""
            buffer.clear()
        }
    }

    /** Records a request before the filter gets a chance to return a replacement response. */
    fun recordRequest(request: WebResourceRequest, documentUrl: String = "",
        type: ResourceType = com.mybrowser.filter.FilterController.classify(request)): Long {
        val url = request.url.toString().trim().take(MAX_URL_LENGTH)
        val method = request.method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        val now = SystemClock.elapsedRealtime()

        return buffer.edit { ring ->
            if (request.isForMainFrame) {
                val existingIndex = ring.indexOfLast {
                    it.id == syntheticMainFrameId && it.url == url
                }
                syntheticMainFrameId = null
                if (existingIndex >= 0) {
                    val entry = ring[existingIndex].copy(method = method)
                    ring[existingIndex] = entry
                    return@edit entry.id
                }
                // The request starts the timeline when it wins the callback race. Each
                // reload is a new navigation, even when the URL and method are identical.
                ring.clear()
                awaitingPageStart = true
                timelineUrl = url
            }

            val entry = NetworkRequestLog(
                id = nextId.getAndIncrement(),
                method = method,
                url = url,
                isForMainFrame = request.isForMainFrame,
                startedAtElapsedMs = now,
                documentUrl = timelineUrl.ifEmpty { documentUrl.take(MAX_URL_LENGTH) },
                resourceType = type.ordinal,
            )
            ring.add(entry)
            entry.id
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
        return buffer.edit { ring ->
            val index = ring.indexOfLast { it.id == id }
            if (index < 0) return@edit
            ring[index] = transform(ring[index])
        }
    }

    private fun updateLatest(
        request: WebResourceRequest,
        transform: (NetworkRequestLog) -> NetworkRequestLog,
    ) {
        val url = request.url.toString().trim().take(MAX_URL_LENGTH)
        val method = request.method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        return buffer.edit { ring ->
            val index = ring.indexOfLast {
                it.url == url && it.method == method &&
                    it.statusCode == null && !it.blocked && it.errorCode == null
            }.takeIf { it >= 0 } ?: ring.indexOfLast {
                it.url == url && it.method == method
            }
            if (index < 0) return@edit
            ring[index] = transform(ring[index])
        }
    }

    private fun updateLatestByUrl(
        url: String,
        mainFrameOnly: Boolean,
        transform: (NetworkRequestLog) -> NetworkRequestLog,
    ) {
        val boundedUrl = url.trim().take(MAX_URL_LENGTH)
        return buffer.edit { ring ->
            if (mainFrameOnly && timelineUrl == boundedUrl) {
                syntheticMainFrameId = null
                awaitingPageStart = false
            }
            val index = ring.indexOfLast {
                it.url == boundedUrl && (!mainFrameOnly || it.isForMainFrame) &&
                    it.statusCode == null && !it.blocked && it.errorCode == null
            }
            if (index < 0) return@edit
            ring[index] = transform(ring[index])
        }
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
        const val MAX_URL_LENGTH = 8_192
        const val MAX_METHOD_LENGTH = 16
        const val MAX_ERROR_LENGTH = 512
    }
}
