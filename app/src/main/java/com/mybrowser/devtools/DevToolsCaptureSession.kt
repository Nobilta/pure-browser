package com.mybrowser.devtools

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mybrowser.core.NetworkLogStore
import com.mybrowser.core.ResourceType
import com.mybrowser.core.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

enum class CaptureNotice { NONE, UNSUPPORTED, HTTP_ONLY, SCRIPT_MISSING, INSTALL_FAILED }

data class EnhancedCaptureState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val stopped: Boolean = false,
    val ready: Boolean = false,
    val installing: Boolean = false,
    val documentId: Long = 0L,
    val documentUrl: String = "",
    val notice: CaptureNotice = CaptureNotice.NONE,
    val errorDetail: String? = null,
    val pageScriptRecords: Int = 0,
)

/**
 * One tab's enhanced capture lifecycle.
 *
 * The document-start registration applies to future documents, so enabling happens in the
 * toolbar action *before* the page reload. Every navigation starts a fresh document id; JS
 * messages are accepted only when they carry the current session token and a document
 * instance registered for the current document. Everything is memory-only and is dropped
 * when this WebView is detached.
 */
@SuppressLint("RequiresFeature")
internal class DevToolsSession(
    val view: WebView,
) {
    val network = NetworkLogStore(onEvicted = { details.remove(it.id) })
    val details = NetworkDetailStore()
    val dom = DomReader(this)
    val source = SourceReader(this)
    val storage = StorageReader(this)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(EnhancedCaptureState(supported = capabilitiesSupported()))
    val state = mutableState.asStateFlow()

    @Volatile var documentId: Long = 0L
        private set
    @Volatile var documentUrl: String = ""
        private set
    @Volatile var closed: Boolean = false
        private set

    private var enabled = false
    private var scriptHandler: ScriptHandler? = null
    private var bridgeInstalled = false
    private var bridgeName = ""
    private var namespace = ""
    private var secret = ""
    private var mainDocumentInstance: String? = null
    private val frameDocuments = HashSet<String>()
    private val captureToLog = HashMap<String, Long>()
    private var pageScriptRecords = 0
    private var ready = false
    private var installing = false
    private var stopped = false
    /**
     * The new document's document-start script ran before WebViewClient.onPageStarted.
     * The later page-start callback must confirm that document, not clear its records.
     */
    private var expectingPageStartAfterInit = false
    /** onPageStarted began this document; the next init belongs to it and must not clear it. */
    private var pageStartedSinceInit = false
    private var notice = CaptureNotice.NONE
    private var errorDetail: String? = null

    private val scriptSource: String? by lazy {
        runCatching {
            view.context.assets.open("devtools-capture.js").bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    fun capabilitiesSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }.getOrDefault(false)

    fun isCurrentDocument(id: Long): Boolean = !closed && id == documentId

    /** True while the tab can inspect the document that loaded the capture script. */
    fun canInspectDocument(): Boolean = !closed && (enabled || stopped) && documentId > 0

    fun setNetworkVisible(value: Boolean) {
        network.setVisible(value)
        details.setVisible(value)
    }

    /** Clearing the visible list also releases every body held for it. */
    fun clearNetwork() {
        network.clear()
        details.clear()
        captureToLog.clear()
        pageScriptRecords = 0
        publish()
    }

    fun enable(): Boolean {
        if (closed) return false
        if (enabled) return true
        if (!capabilitiesSupported()) {
            notice = CaptureNotice.UNSUPPORTED
            publish()
            return false
        }
        if (!UrlUtils.isHttpUrl(view.url.orEmpty())) {
            notice = CaptureNotice.HTTP_ONLY
            publish()
            return false
        }
        val program = scriptSource ?: run {
            notice = CaptureNotice.INSTALL_FAILED
            errorDetail = "capture asset missing"
            publish()
            return false
        }
        installing = true
        publish()
        val nextBridge = "__pureDevTools_" + UUID.randomUUID().toString().replace("-", "")
        val nextNamespace = "__pureDevToolsDom_" + UUID.randomUUID().toString().replace("-", "")
        val nextSecret = UUID.randomUUID().toString().replace("-", "")
        bridgeName = nextBridge
        val prepared = program
            .replace("__BRIDGE__", nextBridge)
            .replace("__TOKEN__", nextSecret)
            .replace("__NAMESPACE__", nextNamespace)
        try {
            installBridge(nextBridge)
            namespace = nextNamespace
            secret = nextSecret
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(view, prepared, setOf("*"))
            enabled = true
            stopped = false
            pageStartedSinceInit = false
            installing = false
            notice = CaptureNotice.NONE
            errorDetail = null
            publish()
            return true
        } catch (error: Exception) {
            runCatching { scriptHandler?.remove() }
            scriptHandler = null
            removeBridge()
            enabled = false
            installing = false
            notice = CaptureNotice.INSTALL_FAILED
            errorDetail = error.message?.take(256)
            publish()
            return false
        }
    }

    /** Stops future collection. Already captured records and the current DOM remain readable. */
    fun disable() {
        if (closed || !enabled) return
        if (ready) {
            runCatching {
                view.evaluateJavascript(
                    "(function(){try{var a=window[${quote(namespace)}];if(a&&a.__pureDevTools)a.stop();}catch(e){}})()",
                    null,
                )
            }
        }
        runCatching { scriptHandler?.remove() }
        scriptHandler = null
        removeBridge()
        enabled = false
        stopped = true
        ready = false
        pageStartedSinceInit = false
        mainDocumentInstance = null
        frameDocuments.clear()
        network.settlePageScripts()
        details.abortCollecting()
        publish()
    }

    fun onDocumentStarted(url: String) {
        if (closed) return
        if (expectingPageStartAfterInit && ready && sameDocumentStart(url, documentUrl)) {
            // Document-start capture already began this document. Keep what it collected;
            // clearing here was how a reload lost every request made during initialization.
            expectingPageStartAfterInit = false
            pageStartedSinceInit = false
            publish()
            return
        }
        beginDocument(url)
        pageStartedSinceInit = true
        publish()
    }

    private fun beginDocument(url: String) {
        documentId++
        documentUrl = url.take(DevToolsBudgets.URL_CHARS)
        ready = false
        stopped = false
        notice = CaptureNotice.NONE
        errorDetail = null
        mainDocumentInstance = null
        frameDocuments.clear()
        captureToLog.clear()
        pageScriptRecords = 0
        installing = false
        expectingPageStartAfterInit = false
        pageStartedSinceInit = false
        // The main-document request can arrive on Chromium's worker before document-start
        // capture runs. beginPage keeps that entry; keep its headers/detail with it.
        val preserved = details.snapshot()
        network.beginPage(url)
        details.clear()
        val remaining = network.snapshot().mapTo(HashSet()) { it.id }
        preserved.filter { it.id in remaining }.forEach {
            details.put(it.copy(documentId = documentId))
        }
        dom.invalidate()
        source.invalidate()
    }

    fun onPageFinished(url: String) {
        if (closed) return
        network.markPageFinished(url)
        if (enabled && !ready) notice = CaptureNotice.SCRIPT_MISSING
        val entries = network.snapshot()
        details.updateAll { detail ->
            val entry = entries.firstOrNull { it.id == detail.id }
            if (entry != null && entry.isForMainFrame && entry.pageLoadMs != null) {
                detail.copy(pageLoadMs = entry.pageLoadMs, durationMs = entry.durationMs,
                    completed = entry.completed, statusCode = entry.statusCode)
            } else detail
        }
        publish()
    }

    fun onPageError(url: String, code: Int, description: String?) {
        if (closed) return
        network.markMainFrameError(url, code, description)
        network.snapshot().lastOrNull { it.isForMainFrame && it.url == url }?.let { entry ->
            details.update(entry.id) {
                it.copy(errorCode = code, failed = true, failure = description, completed = true)
            }
        }
    }

    fun recordNativeRequest(
        request: android.webkit.WebResourceRequest,
        documentUrl: String,
        type: ResourceType,
    ): Long {
        if (closed) return -1L
        val id = network.recordRequest(request, documentUrl, type)
        val headers = runCatching {
            request.requestHeaders.orEmpty().entries.take(DevToolsBudgets.HEADER_COUNT).map {
                HeaderEntry(
                    it.key.take(256),
                    it.value.take(DevToolsBudgets.HEADER_VALUE_CHARS),
                )
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
        details.put(
            NetworkRequestDetail(
                id = id,
                documentId = documentId,
                source = NetworkSource.WEBVIEW,
                method = request.method.ifBlank { "GET" }.uppercase(),
                url = request.url.toString().take(DevToolsBudgets.URL_CHARS),
                resourceType = type.ordinal,
                isForMainFrame = request.isForMainFrame,
                requestHeaders = headers,
                requestHeadersCollected = headers.isNotEmpty(),
                requestHeadersReason = if (headers.isEmpty()) BodyReason.NOT_ATTEMPTED else BodyReason.NONE,
                requestBody = BodyPreview(reason = BodyReason.CAPABILITY),
                responseHeadersReason = BodyReason.CAPABILITY,
                responseBody = BodyPreview(reason = BodyReason.CAPABILITY),
            )
        )
        return id
    }

    fun recordNativeHttpError(
        request: android.webkit.WebResourceRequest,
        statusCode: Int,
        reasonPhrase: String?,
        responseHeaders: Map<String, String>?,
    ) {
        if (closed) return
        val id = network.latestIdFor(request.url.toString(), request.method)
        network.markHttpError(request, statusCode, reasonPhrase, responseHeaders)
        if (id == null) return
        val headers = responseHeaders.orEmpty().entries.take(DevToolsBudgets.HEADER_COUNT).map {
            HeaderEntry(it.key.take(256), it.value.take(DevToolsBudgets.HEADER_VALUE_CHARS))
        }
        details.update(id) { detail ->
            detail.copy(
                statusCode = statusCode,
                statusText = reasonPhrase.orEmpty().take(128),
                sizeBytes = responseHeaders.contentLength(),
                completed = true,
                responseHeaders = headers,
                responseHeadersCollected = headers.isNotEmpty(),
                responseHeadersReason = if (headers.isEmpty()) BodyReason.NOT_ATTEMPTED else BodyReason.NONE,
                responseBody = BodyPreview(reason = BodyReason.CAPABILITY),
            )
        }
    }

    fun recordNativeResourceError(request: android.webkit.WebResourceRequest, code: Int, description: String?) {
        if (closed) return
        val id = network.latestIdFor(request.url.toString(), request.method)
        network.markResourceError(request, code, description)
        if (id == null) return
        details.update(id) { it.copy(errorCode = code, failed = true, failure = description, completed = true) }
    }

    fun markNativeBlocked(id: Long) {
        if (closed || id < 0) return
        network.markBlocked(id)
        details.update(id) { it.copy(blocked = true, completed = true, failure = "blocked by filter") }
    }

    // ---- Page-script messages -------------------------------------------------

    fun onBridgeMessage(
        rawMessage: String?,
        sourceOrigin: Uri,
    ) {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onBridgeMessage(rawMessage, sourceOrigin) }
            return
        }
        if (!enabled) return
        val raw = rawMessage ?: return
        if (raw.length > DevToolsBudgets.BRIDGE_MESSAGE_CHARS) return
        if (sourceOrigin.scheme !in HTTP_SCHEMES) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optString("token") != secret) return
        val documentInstance = json.optString("doc").take(128)
        if (documentInstance.isBlank()) return
        val type = json.optString("type")
        if (type == "init") {
            val mainFrame = json.optBoolean("isMainFrame")
            val initUrl = json.optString("url").take(DevToolsBudgets.URL_CHARS)
            if (mainFrame) {
                if (documentInstance == mainDocumentInstance) {
                    ready = true
                    publish()
                    return
                }
                if (initUrl.isBlank()) return
                if (pageStartedSinceInit && documentUrl.isNotBlank() &&
                    (initUrl == documentUrl || sameDocumentStart(initUrl, documentUrl))) {
                    // onPageStarted already began and cleared this document; adopt its
                    // instance without clearing the initialization requests again.
                    mainDocumentInstance = documentInstance
                    frameDocuments += documentInstance
                    pageStartedSinceInit = false
                    ready = true
                    notice = CaptureNotice.NONE
                    publish()
                    return
                }
                // Document-start runs before onPageStarted on current WebView providers.
                beginDocument(initUrl)
                mainDocumentInstance = documentInstance
                frameDocuments += documentInstance
                ready = true
                expectingPageStartAfterInit = true
                notice = CaptureNotice.NONE
                publish()
                return
            }
            if (frameDocuments.size < 256) frameDocuments += documentInstance
            return
        }
        if (documentInstance !in frameDocuments) return
        val captureId = json.optString("id").take(128)
        when (type) {
            "net-start" -> onPageScriptStart(json, captureId)
            "net-request" -> onPageScriptRequest(json, captureId)
            "net-response" -> onPageScriptResponse(json, captureId)
            "body-meta" -> onPageScriptBodyMeta(json, captureId)
            "body-chunk" -> onPageScriptBodyChunk(json, captureId)
            "body-end" -> onPageScriptBodyEnd(json, captureId)
            "net-end" -> onPageScriptEnd(json, captureId)
            "dom-ready" -> {
                ready = true
                publish()
            }
        }
    }

    private fun onPageScriptStart(json: JSONObject, captureId: String) {
        if (captureId.isBlank()) return
        val methodKnown = json.optBoolean("methodKnown", true)
        val method = if (methodKnown) json.optString("method", "GET").uppercase().take(16) else ""
        val url = json.optString("url").take(DevToolsBudgets.URL_CHARS)
        if (url.isBlank()) return
        val id = network.recordPageScript(
            captureId = captureId,
            method = method,
            url = url,
            resourceType = ResourceType.XML_HTTP_REQUEST,
            isForMainFrame = false,
            documentUrl = documentUrl,
            methodKnown = methodKnown,
        )
        captureToLog[captureId] = id
        while (captureToLog.size > DevToolsBudgets.NETWORK_ENTRIES_PER_DOCUMENT * 2) {
            captureToLog.remove(captureToLog.keys.first())
        }
        pageScriptRecords++
        details.put(
            NetworkRequestDetail(
                id = id,
                documentId = documentId,
                source = NetworkSource.PAGE_SCRIPT,
                captureId = captureId,
                methodKnown = methodKnown,
                method = method,
                url = url,
                resourceType = ResourceType.XML_HTTP_REQUEST.ordinal,
            )
        )
        publish()
    }

    private fun onPageScriptRequest(json: JSONObject, captureId: String) {
        val id = captureToLog[captureId] ?: return
        val headers = headers(json.optJSONArray("headers"))
        details.update(id) {
            it.copy(requestHeaders = headers, requestHeadersCollected = true,
                requestHeadersReason = if (headers.isEmpty()) BodyReason.HEADER_UNAVAILABLE else BodyReason.NONE)
        }
    }

    private fun onPageScriptResponse(json: JSONObject, captureId: String) {
        val id = captureToLog[captureId] ?: return
        val status = json.optInt("status", 0).takeIf { it > 0 }
        val statusText = json.optString("statusText").take(128)
        val headers = headers(json.optJSONArray("headers"))
        val length = json.optLong("contentLength", 0L).takeIf { it > 0 }
        network.updatePageScript(captureId) {
            it.copy(statusCode = status, statusText = statusText, sizeBytes = length ?: it.sizeBytes,
                completed = false)
        }
        details.update(id) {
            it.copy(
                statusCode = status,
                statusText = statusText,
                sizeBytes = length ?: it.sizeBytes,
                responseHeaders = headers,
                responseHeadersCollected = true,
                responseHeadersReason = if (headers.isEmpty()) BodyReason.HEADER_UNAVAILABLE else BodyReason.NONE,
            )
        }
    }

    private fun onPageScriptBodyMeta(json: JSONObject, captureId: String) {
        val id = captureToLog[captureId] ?: return
        val phase = bodyPhase(json.optString("phase")) ?: return
        val state = BodyReason.ofState(json.optString("state"))
        val bytes = json.optLong("bytes", 0L).coerceAtLeast(0L)
        val chars = json.optInt("chars", 0).coerceAtLeast(0)
        val contentType = json.optString("contentType").take(256)
        val reason = BodyReason.of(json.optString("reason"))
        val binary = json.optBoolean("binary")
        details.update(id) { detail ->
            val body = BodyPreview(
                state = state,
                text = if (state == BodyState.COLLECTING) bodyOf(detail, phase).text else "",
                bytes = bytes,
                chars = chars,
                contentType = contentType,
                reason = reason,
                binary = binary,
            )
            detail.withBody(phase, body)
        }
    }

    private fun onPageScriptBodyChunk(json: JSONObject, captureId: String) {
        val id = captureToLog[captureId] ?: return
        val phase = bodyPhase(json.optString("phase")) ?: return
        val chunk = json.optString("text")
        if (chunk.isEmpty()) return
        details.update(id) { detail ->
            val body = bodyOf(detail, phase)
            if (body.state in setOf(BodyState.UNAVAILABLE, BodyState.EVICTED)) return@update detail
            val room = DevToolsBudgets.BODY_PREVIEW_CHARS - body.text.length
            if (room <= 0) return@update detail
            val text = body.text + chunk.take(room)
            detail.withBody(phase, body.copy(
                state = if (chunk.length > room) BodyState.TRUNCATED else BodyState.COLLECTING,
                text = text,
                chars = text.length,
                reason = if (chunk.length > room) BodyReason.SIZE_LIMIT else body.reason,
            ))
        }
    }

    private fun onPageScriptBodyEnd(json: JSONObject, captureId: String) {
        val id = captureToLog[captureId] ?: return
        val phase = bodyPhase(json.optString("phase")) ?: return
        val state = BodyReason.ofState(json.optString("state"))
        val bytes = json.optLong("bytes", 0L).coerceAtLeast(0L).coerceAtMost(1L shl 40)
        val chars = json.optInt("chars", 0).coerceAtLeast(0)
        val reason = BodyReason.of(json.optString("reason"))
        details.update(id) { detail ->
            val body = bodyOf(detail, phase)
            detail.withBody(phase, body.copy(
                state = state,
                text = if (state in setOf(BodyState.EMPTY, BodyState.UNAVAILABLE, BodyState.FAILED,
                    BodyState.NOT_COLLECTED, BodyState.EVICTED)) "" else body.text,
                bytes = bytes,
                chars = chars,
                reason = reason,
            ))
        }
    }

    private fun onPageScriptEnd(json: JSONObject, captureId: String) {
        val id = captureToLog[captureId] ?: return
        val outcome = json.optString("outcome")
        val duration = json.optLong("durationMs", -1L).takeIf { it >= 0 }
        val size = json.optLong("sizeBytes", -1L).takeIf { it >= 0 }
        val failure = json.optString("error").take(512).takeIf { it.isNotBlank() }
        val failureReason = BodyReason.of(json.optString("reason"))
        network.updatePageScript(captureId) {
            it.copy(
                completed = true,
                settled = true,
                failed = outcome == "failed",
                cancelled = outcome == "cancelled",
                durationMs = duration ?: it.durationMs,
                sizeBytes = size ?: it.sizeBytes,
                errorDescription = failure?.takeIf { text -> text.isNotBlank() },
            )
        }
        details.update(id) {
            it.copy(
                completed = true,
                failed = outcome == "failed",
                cancelled = outcome == "cancelled",
                failure = failure?.takeIf { text -> text.isNotBlank() },
                failureReason = failureReason,
                durationMs = duration ?: it.durationMs,
                sizeBytes = size ?: it.sizeBytes,
            )
        }
    }

    // ---- Helpers --------------------------------------------------------------

    private fun publish() {
        mutableState.value = EnhancedCaptureState(
            supported = capabilitiesSupported(),
            enabled = enabled,
            stopped = stopped,
            ready = ready,
            installing = installing,
            documentId = documentId,
            documentUrl = documentUrl,
            notice = notice,
            errorDetail = errorDetail,
            pageScriptRecords = pageScriptRecords,
        )
    }

    private fun installBridge(name: String) {
        WebViewCompat.addWebMessageListener(
            view, name, setOf("*"),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy,
                ) {
                    if (view !== this@DevToolsSession.view) return
                    onBridgeMessage(message.data, sourceOrigin)
                }
            },
        )
        bridgeInstalled = true
    }

    private fun removeBridge() {
        if (!bridgeInstalled) return
        runCatching { WebViewCompat.removeWebMessageListener(view, bridgeName) }
        bridgeInstalled = false
    }

    private fun headers(array: JSONArray?): List<HeaderEntry> {
        if (array == null) return emptyList()
        val result = ArrayList<HeaderEntry>(minOf(array.length(), DevToolsBudgets.HEADER_COUNT))
        for (index in 0 until minOf(array.length(), DevToolsBudgets.HEADER_COUNT)) {
            val pair = array.optJSONArray(index) ?: continue
            val name = pair.optString(0).take(256)
            val value = pair.optString(1).take(DevToolsBudgets.HEADER_VALUE_CHARS)
            if (name.isNotEmpty()) result += HeaderEntry(name, value)
        }
        return result
    }

    private fun bodyPhase(value: String): BodyPhase? = when (value) {
        "request" -> BodyPhase.REQUEST
        "response" -> BodyPhase.RESPONSE
        else -> null
    }

    private fun bodyOf(detail: NetworkRequestDetail, phase: BodyPhase): BodyPreview =
        if (phase == BodyPhase.REQUEST) detail.requestBody else detail.responseBody

    private fun NetworkRequestDetail.withBody(phase: BodyPhase, body: BodyPreview): NetworkRequestDetail =
        if (phase == BodyPhase.REQUEST) copy(requestBody = body) else copy(responseBody = body)

    private fun Map<String, String>?.contentLength(): Long? = this?.entries
        ?.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
        ?.value?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

    private fun quote(value: String): String = JSONObject.quote(value)

    /** Fragment-only changes do not create a new document. */
    private fun sameDocumentStart(left: String, right: String): Boolean =
        left == right || left.substringBefore('#') == right.substringBefore('#')

    /** Runs JavaScript in the main frame and drops results from an obsolete document. */
    internal suspend fun evaluateInDocument(script: String): String? {
        val expected = documentId
        if (closed) return null
        // Export writes on IO, but evaluateJavascript belongs to the WebView's UI thread.
        val raw = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    view.evaluateJavascript(script) { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
        return if (!closed && documentId == expected) raw else null
    }

    internal fun namespace(): String? =
        if (!closed && (enabled || stopped) && namespace.isNotBlank()) namespace else null

    fun close() {
        if (closed) return
        if (enabled) disable()
        closed = true
        scriptHandler?.let { runCatching { it.remove() } }
        scriptHandler = null
        removeBridge()
        network.clear()
        details.clear()
        dom.invalidate()
        source.invalidate()
        publish()
    }

    private companion object {
        val HTTP_SCHEMES = setOf("http", "https")
    }
}
