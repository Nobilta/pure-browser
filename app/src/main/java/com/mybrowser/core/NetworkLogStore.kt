package com.mybrowser.core

import com.mybrowser.R
import android.content.res.Resources
import android.os.SystemClock
import android.webkit.WebResourceRequest
import com.mybrowser.devtools.DevToolsBudgets
import java.util.concurrent.atomic.AtomicLong

/**
 * A small, bounded request timeline for the in-app developer tools.
 *
 * WebView does not expose successful response callbacks from
 * [WebViewClient.shouldInterceptRequest] without taking ownership of the response body.
 * Recording at request time and completing entries when an HTTP/load error arrives keeps
 * the browser's normal streaming path intact. A request with no completion callback is
 * reported as an unknown status; it is never assumed to be 200 and never left "in progress"
 * after the document has finished.
 *
 * Enhanced capture adds page-script records alongside these WebView records. They carry
 * separate ids and source flags: no two records are merged by URL, because the native
 * callback and the page wrapper have no shared request id.
 */
data class NetworkRequestLog(
    val id: Long,
    val method: String,
    val url: String,
    val statusCode: Int? = null,
    val statusText: String? = null,
    val blocked: Boolean = false,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    val errorCode: Int? = null,
    val errorDescription: String? = null,
    val isForMainFrame: Boolean = false,
    val startedAtElapsedMs: Long = 0L,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val documentUrl: String = "",
    val resourceType: Int = ResourceType.OTHER.ordinal,
    /** True for records created from the document-start fetch/XHR wrapper. */
    val pageScript: Boolean = false,
    /** The page-side request id, stable across net-start/net-response/net-end messages. */
    val captureId: String = "",
    /** A response/error callback arrived, or the document finished. */
    val completed: Boolean = false,
    /** No further callback is expected for this record. Unknown status is final. */
    val settled: Boolean = false,
    val pageLoadMs: Long? = null,
) {
    fun statusText(resources: Resources): String = when {
        blocked -> resources.getString(R.string.ui_blocked)
        statusCode != null -> buildString {
            append(statusCode)
            statusText?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        }
        errorCode != null -> resources.getString(R.string.ui_error, errorCode)
        cancelled -> resources.getString(R.string.dev_network_cancelled)
        failed -> resources.getString(R.string.dev_network_failed)
        else -> resources.getString(R.string.dev_network_status_unknown)
    }

    fun sizeText(resources: Resources): String = sizeBytes?.let(::formatBytes) ?: resources.getString(R.string.ui_unknown_size)

    fun durationText(resources: Resources): String = durationMs?.let { "$it ms" } ?: run {
        if (settled || completed) resources.getString(R.string.dev_duration_unknown)
        else resources.getString(R.string.ui_in_progress)
    }
}

/** Thread-safe, bounded state used by WebView worker callbacks and Compose. */
class NetworkLogStore(
    maxEntries: Int = DevToolsBudgets.NETWORK_ENTRIES_PER_DOCUMENT,
    private val onEvicted: (NetworkRequestLog) -> Unit = {},
) {

    private val nextId = AtomicLong(1L)
    private val buffer = BufferedLog<NetworkRequestLog>(maxEntries)
    // Protected by buffer.edit's lock. Chromium may deliver the main request and its
    // subresources before the UI thread receives onPageStarted.
    private var awaitingPageStart = false
    private var syntheticMainFrameId: Long? = null
    private var timelineUrl = ""
    private var pageStartedAtElapsedMs = 0L
    /** A request that starts after onPageFinished has no later finish callback to settle it. */
    @Volatile private var pageFinished = false
    val entries = buffer.entries
    fun setVisible(value: Boolean) = buffer.setVisible(value)
    fun snapshot(): List<NetworkRequestLog> = buffer.snapshot()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Confirms an intercepted navigation, or starts one with no request callback yet. */
    fun beginPage(url: String) {
        val normalized = url.trim().take(DevToolsBudgets.URL_CHARS)
        if (normalized.isEmpty()) {
            clear()
            return
        }
        buffer.edit { ring ->
            val intercepted = awaitingPageStart
            awaitingPageStart = false
            syntheticMainFrameId = null
            timelineUrl = normalized
            pageStartedAtElapsedMs = SystemClock.elapsedRealtime()
            pageFinished = false
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
            evict(ring.add(entry))
            syntheticMainFrameId = entry.id
        }
    }

    fun clear() {
        buffer.edit {
            awaitingPageStart = false
            syntheticMainFrameId = null
            timelineUrl = ""
            // Clearing the log is not a navigation: a delayed request on a finished
            // document must still settle as unknown instead of going back to "in progress".
            buffer.clear()
        }
    }

    /** Records a request before the filter gets a chance to return a replacement response. */
    fun recordRequest(request: WebResourceRequest, documentUrl: String = "",
        type: ResourceType = classifyResourceType(request)): Long {
        val url = request.url.toString().trim().take(DevToolsBudgets.URL_CHARS)
        val method = request.method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        val now = SystemClock.elapsedRealtime()

        return buffer.edit { ring ->
            if (request.isForMainFrame) {
                pageFinished = false
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
                pageStartedAtElapsedMs = now
            }

            val entry = NetworkRequestLog(
                id = nextId.getAndIncrement(),
                method = method,
                url = url,
                isForMainFrame = request.isForMainFrame,
                startedAtElapsedMs = now,
                documentUrl = timelineUrl.ifEmpty { documentUrl.take(DevToolsBudgets.URL_CHARS) },
                resourceType = type.ordinal,
                // Successful native subresources have no completion callback. If the page
                // already finished, there is no future event that could settle this one.
                settled = pageFinished && !request.isForMainFrame,
            )
            evict(ring.add(entry))
            entry.id
        }
    }

    /** Creates a record for a page-script request; the capture id keeps later events together. */
    fun recordPageScript(
        captureId: String,
        method: String,
        url: String,
        resourceType: ResourceType,
        isForMainFrame: Boolean,
        documentUrl: String,
    ): Long = buffer.edit { ring ->
        val entry = NetworkRequestLog(
            id = nextId.getAndIncrement(),
            method = method.take(MAX_METHOD_LENGTH).ifBlank { "GET" },
            url = url.take(DevToolsBudgets.URL_CHARS),
            isForMainFrame = isForMainFrame,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
            documentUrl = documentUrl.take(DevToolsBudgets.URL_CHARS),
            resourceType = resourceType.ordinal,
            pageScript = true,
            captureId = captureId,
        )
        evict(ring.add(entry))
        entry.id
    }

    fun updatePageScript(captureId: String, transform: (NetworkRequestLog) -> NetworkRequestLog) {
        buffer.edit { ring ->
            val index = ring.indexOfLast { it.pageScript && it.captureId == captureId }
            if (index < 0) return@edit
            ring[index] = transform(ring[index])
        }
    }

    /** Finds the newest WebView record matching a request, for attaching response details. */
    fun latestIdFor(url: String, method: String): Long? = buffer.edit { ring ->
        val boundedUrl = url.trim().take(DevToolsBudgets.URL_CHARS)
        val boundedMethod = method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        val unfinished = ring.indexOfLast {
            !it.pageScript && it.url == boundedUrl && it.method == boundedMethod &&
                it.statusCode == null && !it.blocked && it.errorCode == null && !it.settled
        }
        val index = if (unfinished >= 0) unfinished else ring.indexOfLast {
            !it.pageScript && it.url == boundedUrl && it.method == boundedMethod
        }
        if (index >= 0) ring[index].id else null
    }

    /** Stops page-script records from reading as "in progress" when capture is disabled. */
    fun settlePageScripts() {
        buffer.edit { ring ->
            val now = SystemClock.elapsedRealtime()
            for (index in 0 until ring.size) {
                val entry = ring[index]
                if (!entry.pageScript || entry.settled) continue
                ring[index] = entry.copy(
                    completed = true,
                    settled = true,
                    durationMs = (now - entry.startedAtElapsedMs).coerceAtLeast(0L),
                )
            }
        }
    }

    fun updateById(id: Long, transform: (NetworkRequestLog) -> NetworkRequestLog) {
        buffer.edit { ring ->
            val index = ring.indexOfLast { it.id == id }
            if (index < 0) return@edit
            ring[index] = transform(ring[index])
        }
    }

    fun markBlocked(id: Long) {
        updateById(id) { entry ->
            entry.copy(
                blocked = true,
                completed = true,
                settled = true,
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
                statusText = reasonPhrase?.trim()?.take(MAX_STATUS_TEXT_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                errorDescription = reasonPhrase?.trim()?.take(MAX_ERROR_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                completed = true,
                settled = true,
                durationMs = elapsedSince(entry),
                sizeBytes = responseHeaders.contentLengthOrNull() ?: entry.sizeBytes,
            )
        }
    }

    fun markResourceError(request: WebResourceRequest, errorCode: Int, description: String?) {
        updateLatest(request) { entry ->
            entry.copy(
                errorCode = errorCode,
                failed = true,
                errorDescription = description?.trim()?.take(MAX_ERROR_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                completed = true,
                settled = true,
                durationMs = elapsedSince(entry),
            )
        }
    }

    /**
     * The document finished. A main-frame entry gets the page-load duration, which is not a
     * request duration; every WebView record without a callback becomes finally unknown.
     */
    fun markPageFinished(url: String, pageLoadMs: Long? = null) {
        val boundedUrl = url.trim().take(DevToolsBudgets.URL_CHARS)
        val finishedAt = SystemClock.elapsedRealtime()
        pageFinished = true
        buffer.edit { ring ->
            if (timelineUrl == boundedUrl) {
                syntheticMainFrameId = null
                awaitingPageStart = false
            }
            for (index in 0 until ring.size) {
                val entry = ring[index]
                if (entry.pageScript) continue
                if (entry.settled) continue
                ring[index] = if (entry.isForMainFrame && entry.url == boundedUrl) {
                    val load = pageLoadMs ?: (finishedAt - pageStartedAtElapsedMs).coerceAtLeast(0L)
                    // Page load and request duration are different measurements. This record has
                    // no request completion callback, so only the page load duration is known.
                    entry.copy(completed = true, settled = true, pageLoadMs = load)
                } else {
                    entry.copy(settled = true)
                }
            }
        }
    }

    fun markMainFrameError(url: String, errorCode: Int, description: String?) {
        pageFinished = true
        updateLatestByUrl(url, mainFrameOnly = true) { entry ->
            entry.copy(
                errorCode = errorCode,
                failed = true,
                errorDescription = description?.trim()?.take(MAX_ERROR_LENGTH)
                    ?.takeIf { it.isNotEmpty() },
                completed = true,
                settled = true,
                durationMs = elapsedSince(entry),
            )
        }
    }

    private fun evict(evicted: NetworkRequestLog?) {
        evicted?.let(onEvicted)
    }

    private fun updateLatest(
        request: WebResourceRequest,
        transform: (NetworkRequestLog) -> NetworkRequestLog,
    ) {
        val url = request.url.toString().trim().take(DevToolsBudgets.URL_CHARS)
        val method = request.method.ifBlank { "GET" }.uppercase().take(MAX_METHOD_LENGTH)
        return buffer.edit { ring ->
            val index = ring.indexOfLast {
                it.url == url && it.method == method && !it.pageScript &&
                    it.statusCode == null && !it.blocked && it.errorCode == null
            }.takeIf { it >= 0 } ?: ring.indexOfLast {
                it.url == url && it.method == method && !it.pageScript
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
        val boundedUrl = url.trim().take(DevToolsBudgets.URL_CHARS)
        return buffer.edit { ring ->
            if (mainFrameOnly && timelineUrl == boundedUrl) {
                syntheticMainFrameId = null
                awaitingPageStart = false
            }
            val index = ring.indexOfLast {
                it.url == boundedUrl && (!mainFrameOnly || it.isForMainFrame) && !it.pageScript &&
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
        const val MAX_METHOD_LENGTH = 16
        const val MAX_ERROR_LENGTH = 512
        const val MAX_STATUS_TEXT_LENGTH = 128
    }
}
