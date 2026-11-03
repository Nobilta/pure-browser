package com.mybrowser.devtools

import com.mybrowser.R
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Which capture path produced a record. The two are never merged by URL alone. */
enum class NetworkSource(val label: Int) {
    WEBVIEW(R.string.dev_enhanced_source_webview),
    PAGE_SCRIPT(R.string.dev_enhanced_source_page_script),
}

enum class BodyPhase { REQUEST, RESPONSE }

/** The user-visible state of a request or response body. */
enum class BodyState {
    COLLECTING,
    EMPTY,
    NOT_COLLECTED,
    UNAVAILABLE,
    FAILED,
    TRUNCATED,
    EVICTED,
    COLLECTED;

    val label: Int get() = when (this) {
        COLLECTING -> R.string.dev_body_collecting
        EMPTY -> R.string.dev_body_empty
        NOT_COLLECTED -> R.string.dev_body_not_collected
        UNAVAILABLE -> R.string.dev_body_unsupported
        FAILED -> R.string.dev_body_failed
        TRUNCATED -> R.string.dev_body_truncated
        EVICTED -> R.string.dev_body_evicted
        COLLECTED -> R.string.dev_body_collected
    }
}

/** Machine-readable reasons. The interface localizes them; the page never supplies prose. */
enum class BodyReason(val label: Int) {
    SIZE_LIMIT(R.string.dev_reason_size_limit),
    TIME_LIMIT(R.string.dev_reason_time_limit),
    STREAM(R.string.dev_reason_stream),
    OPAQUE(R.string.dev_reason_opaque),
    BINARY(R.string.dev_reason_binary),
    REQUEST_STREAM(R.string.dev_reason_request_stream),
    REQUEST_FILE(R.string.dev_reason_request_file),
    NO_BODY(R.string.dev_reason_no_body),
    NOT_ATTEMPTED(R.string.dev_reason_not_attempted),
    READ_ERROR(R.string.dev_reason_read_error),
    CAPABILITY(R.string.dev_reason_capability),
    EVICTED(R.string.dev_reason_evicted),
    NETWORK(R.string.dev_reason_network),
    ABORTED(R.string.dev_reason_aborted),
    TIMED_OUT(R.string.dev_reason_timed_out),
    HEADER_UNAVAILABLE(R.string.dev_reason_header_unavailable),
    ACCESSOR(R.string.dev_reason_accessor),
    NONE(0);

    val labelOrNull: Int? get() = label.takeIf { it != 0 }

    companion object {
        fun of(value: String?): BodyReason = when (value) {
            "size_limit" -> SIZE_LIMIT
            "time_limit" -> TIME_LIMIT
            "stream" -> STREAM
            "opaque" -> OPAQUE
            "binary" -> BINARY
            "request_stream" -> REQUEST_STREAM
            "request_file" -> REQUEST_FILE
            "no_body" -> NO_BODY
            "not_attempted" -> NOT_ATTEMPTED
            "read_error" -> READ_ERROR
            "capability" -> CAPABILITY
            "evicted" -> EVICTED
            "network" -> NETWORK
            "aborted" -> ABORTED
            "timed_out" -> TIMED_OUT
            "header_unavailable" -> HEADER_UNAVAILABLE
            "accessor" -> ACCESSOR
            else -> NONE
        }

        fun ofState(value: String?): BodyState = when (value) {
            "COLLECTING" -> BodyState.COLLECTING
            "EMPTY" -> BodyState.EMPTY
            "UNAVAILABLE" -> BodyState.UNAVAILABLE
            "FAILED" -> BodyState.FAILED
            "TRUNCATED" -> BodyState.TRUNCATED
            "EVICTED" -> BodyState.EVICTED
            "COLLECTED" -> BodyState.COLLECTED
            else -> BodyState.NOT_COLLECTED
        }
    }
}

data class BodyPreview(
    val state: BodyState = BodyState.NOT_COLLECTED,
    val text: String = "",
    val bytes: Long = 0,
    val chars: Int = 0,
    val contentType: String = "",
    val reason: BodyReason = BodyReason.NOT_ATTEMPTED,
    val binary: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isEmpty() && state !in setOf(BodyState.COLLECTING, BodyState.TRUNCATED)
}

data class HeaderEntry(val name: String, val value: String)

/** A query parameter keeps both its decoded view and the exact encoded segment. */
data class QueryParameter(
    val name: String,
    val value: String?,
    val encodedName: String,
    val encodedValue: String?,
    val raw: String,
) {
    val hasEmptyValue: Boolean get() = value != null && value.isEmpty()
    val encodedDiffers: Boolean get() = raw != displaySegment()
    private fun displaySegment(): String = when (value) {
        null -> name
        else -> "$name=$value"
    }
}

data class NetworkRequestDetail(
    val id: Long,
    val documentId: Long,
    val source: NetworkSource,
    val captureId: String = "",
    val methodKnown: Boolean = true,
    val method: String = "GET",
    val url: String = "",
    val resourceType: Int = 0,
    val statusCode: Int? = null,
    val statusText: String = "",
    val durationMs: Long? = null,
    val pageLoadMs: Long? = null,
    val sizeBytes: Long? = null,
    val isForMainFrame: Boolean = false,
    val blocked: Boolean = false,
    val errorCode: Int? = null,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    val failure: String? = null,
    val failureReason: BodyReason = BodyReason.NONE,
    val completed: Boolean = false,
    val requestHeaders: List<HeaderEntry> = emptyList(),
    val requestHeadersCollected: Boolean = false,
    val requestHeadersReason: BodyReason = BodyReason.NOT_ATTEMPTED,
    val requestBody: BodyPreview = BodyPreview(),
    val responseHeaders: List<HeaderEntry> = emptyList(),
    val responseHeadersCollected: Boolean = false,
    val responseHeadersReason: BodyReason = BodyReason.NOT_ATTEMPTED,
    val responseBody: BodyPreview = BodyPreview(),
) {
    val queryParameters: List<QueryParameter> get() = parseQuery(url)
}

/** Parses a query string without collapsing duplicate keys, empty values or original encoding. */
internal fun parseQuery(url: String): List<QueryParameter> {
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return emptyList()
    return query.split('&').mapNotNull { segment ->
        if (segment.isEmpty()) return@mapNotNull null
        val equals = segment.indexOf('=')
        val encodedName = if (equals < 0) segment else segment.substring(0, equals)
        val encodedValue = if (equals < 0) null else segment.substring(equals + 1)
        QueryParameter(
            name = decodeQueryPart(encodedName),
            value = encodedValue?.let(::decodeQueryPart),
            encodedName = encodedName.take(DevToolsBudgets.HEADER_VALUE_CHARS),
            encodedValue = encodedValue?.take(DevToolsBudgets.HEADER_VALUE_CHARS),
            raw = segment.take(DevToolsBudgets.HEADER_VALUE_CHARS * 2),
        )
    }
}

private fun decodeQueryPart(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

/** A DOM node as returned by the page-side pager. */
enum class DomNodeKind(val label: Int) {
    ELEMENT(0),
    TEXT(R.string.dev_dom_text_node),
    COMMENT(R.string.dev_dom_comment_node),
    DOCTYPE(0),
    OTHER(0),
}

data class DomAttribute(val name: String, val value: String)

data class DomNode(
    val id: String,
    val kind: DomNodeKind,
    val name: String,
    val attributes: List<DomAttribute>,
    val text: String,
    val textTruncated: Boolean,
    val textLength: Int,
    val childCount: Int,
) {
    val displayName: String
        get() = when (kind) {
            DomNodeKind.ELEMENT -> name
            DomNodeKind.TEXT -> "#text"
            DomNodeKind.COMMENT -> "#comment"
            DomNodeKind.DOCTYPE -> "!DOCTYPE"
            DomNodeKind.OTHER -> name.ifBlank { "#node" }
        }
}

data class DomChildrenPage(val nodes: List<DomNode>, val start: Int, val total: Int)
data class DomPathEntry(val id: String, val index: Int)
data class DomSearchResult(val id: String, val name: String, val preview: String, val match: String,
    val path: List<DomPathEntry>)
data class DomSearchResults(val results: List<DomSearchResult>, val truncated: Boolean)

data class SourceInfo(val totalChars: Int, val totalLines: Int, val url: String, val title: String)

data class SourcePage(
    val offset: Int,
    val end: Int,
    val text: String,
    val startLine: Int,
    val endLine: Int,
    val totalChars: Int,
    val totalLines: Int,
    val nextOffset: Int?,
    val continued: Boolean,
)

/** Cookie attributes are nullable: the API may not expose them, and "absent" is not "false". */
data class CookieRow(
    val name: String,
    val value: String,
    val attributes: Map<String, String?>,
) {
    fun attribute(name: String): String? = attributes[name.lowercase()]
    val domain: String? get() = attribute("domain")
    val path: String? get() = attribute("path")
    val expires: String? get() = attribute("expires") ?: attribute("max-age")
    val secure: String? get() = attribute("secure")
    val httpOnly: String? get() = attribute("httponly")
    val sameSite: String? get() = attribute("samesite")
}

data class CookieResult(
    val cookies: List<CookieRow>,
    val richAttributes: Boolean,
    val notice: String? = null,
)

data class StorageEntry(
    val name: String,
    val value: String,
    val valueChars: Int,
    val truncated: Boolean,
)

data class StorageSnapshot(
    val origin: String,
    val entries: List<StorageEntry>,
    val total: Int,
    val truncated: Boolean,
    val nextOffset: Int? = null,
    /** Key-set fingerprint at the time this page was read; detects inserts/removals. */
    val keysToken: String = "",
)

data class StorageValue(
    val name: String,
    val value: String,
    val offset: Int,
    val totalChars: Int,
    val truncated: Boolean,
)

data class StorageResult<T>(val data: T? = null, val error: String? = null) {
    val ok: Boolean get() = data != null && error == null
}
