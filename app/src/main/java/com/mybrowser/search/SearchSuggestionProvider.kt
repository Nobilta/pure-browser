package com.mybrowser.search

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Suggest endpoints for the built-in engines (protocol fixtures are covered in tests):
 *
 *  - Baidu — `https://www.baidu.com/su?wd={query}&json=1` answers with the restricted
 *    JSONP wrapper `window.baidu.sug({"q":…,"s":[…]})`; the `s` array holds plain terms.
 *  - Google — `https://www.google.com/complete/search?client=firefox&q={query}` answers
 *    with the osjson shape `["query",["term",…],[],{…}]`.
 *  - DuckDuckGo — `https://duckduckgo.com/ac/?q={query}&type=list` answers with the
 *    same osjson shape (also consumed as response[1] by SearXNG's DDG adapter).
 *  - Bing — `https://api.bing.com/osjson.aspx?query={query}` answers with the same
 *    osjson shape as Google. Only the paid Azure Autosuggest API was retired in
 *    2025-08; this consumer endpoint needs no key. No engine falls back to another.
 *
 * Custom engines may configure an HTTPS OpenSearch JSON template instead
 * (`[query,[term,…]]`).
 */
enum class SuggestFormat { BAIDU_JSONP, OPEN_SEARCH_JSON }

data class SuggestEndpoint(val urlTemplate: String, val format: SuggestFormat)

/**
 * Online search-suggest client.
 *
 * Requests carry no cookies, no Referer and no page identity: the connection uses the
 * process default [java.net.CookieHandler], which this app never installs, so nothing
 * from any WebView session leaks into a suggest call. Failures of any kind surface as
 * an empty list — local suggestions and manual search must keep working.
 *
 * Normal and incognito results use separate caches; the incognito one is wiped when a
 * private session ends. Nothing is persisted to disk.
 */
class SearchSuggestionProvider internal constructor(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val deadlineMillis: Long = TOTAL_DEADLINE_MS.toLong(),
) {

    companion object {
        /** Per-engine built-in endpoints; keyed by engine id. */
        val BUILTIN_ENDPOINTS: Map<String, SuggestEndpoint> = mapOf(
            "baidu" to SuggestEndpoint(
                "https://www.baidu.com/su?wd={query}&json=1",
                SuggestFormat.BAIDU_JSONP,
            ),
            "google" to SuggestEndpoint(
                "https://www.google.com/complete/search?client=firefox&q={query}",
                SuggestFormat.OPEN_SEARCH_JSON,
            ),
            "bing" to SuggestEndpoint(
                "https://api.bing.com/osjson.aspx?query={query}",
                SuggestFormat.OPEN_SEARCH_JSON,
            ),
            "duckduckgo" to SuggestEndpoint(
                "https://duckduckgo.com/ac/?q={query}&type=list",
                SuggestFormat.OPEN_SEARCH_JSON,
            ),
        )

        private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val TIMEOUT_MS = 2_000
        const val TOTAL_DEADLINE_MS = 6_000
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_TERMS = 10
        const val MAX_TERM_LENGTH = 100
        const val MAX_CACHE_ENTRIES = 100
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val MAX_SUGGEST_TEMPLATE_LENGTH = 2_048

        /**
         * Validates a custom engine's suggest template: HTTPS only, a real host, at most
         * [MAX_SUGGEST_TEMPLATE_LENGTH] characters and exactly one `{query}`/`%s`
         * placeholder. Blank input means "no suggest URL", which is always allowed.
         */
        fun isValidSuggestTemplate(template: String?): Boolean {
            if (template == null) return true
            val clean = template.trim()
            if (clean.isEmpty()) return true
            if (clean.length > MAX_SUGGEST_TEMPLATE_LENGTH) return false
            if (clean.any { it.isWhitespace() || it.isISOControl() }) return false
            val braceCount = clean.windowed(7, partialWindows = true).count { it == "{query}" }
            val percentCount = clean.windowed(2, partialWindows = true).count { it == "%s" }
            if (braceCount + percentCount != 1) return false
            val probe = clean.replace("{query}", "query").replace("%s", "query")
            val parsed = runCatching { Uri.parse(probe) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase() ?: return false
            return scheme == "https" && !parsed.host.isNullOrBlank()
        }

        /**
         * Parses the osjson/OpenSearch JSON shape `["query",["term",…]]`. Extra trailing
         * elements (Google's metadata objects, DuckDuckGo variants) are ignored, and any
         * malformed body yields an empty list rather than an exception.
         */
        fun parseOpenSearchJson(body: String): List<String> {
            val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
            if (array.length() < 2) return emptyList()
            val terms = array.optJSONArray(1) ?: return emptyList()
            return extractTerms(terms)
        }

        /**
         * Parses Baidu's restricted JSONP wrapper. Only the exact `window.baidu.sug(`
         * prefix is recognized; anything else is rejected rather than evaled.
         */
        fun parseBaiduJsonp(body: String): List<String> {
            val trimmed = body.trim()
            val prefix = "window.baidu.sug("
            if (!trimmed.startsWith(prefix)) return emptyList()
            var json = trimmed.removePrefix(prefix).trim()
            if (json.endsWith(";")) json = json.dropLast(1).trim()
            if (!json.endsWith(")")) return emptyList()
            json = json.dropLast(1)
            val payload = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            val terms = payload.optJSONArray("s") ?: return emptyList()
            return extractTerms(terms)
        }

        private fun extractTerms(terms: JSONArray): List<String> {
            val result = mutableListOf<String>()
            for (index in 0 until terms.length()) {
                if (result.size >= MAX_TERMS) break
                // Only real strings count; optString would stringify nested objects.
                val term = terms.opt(index) as? String ?: continue
                val clean = term.trim()
                if (clean.isEmpty() || clean.length > MAX_TERM_LENGTH) continue
                if (clean.any { it.isISOControl() || it == '\uFFFD' }) continue
                result += clean
            }
            return result
        }
    }

    private data class CacheEntry(val terms: List<String>, val at: Long)

    private class SuggestCache {
        // Access-ordered LRU keyed by engine id + query.
        private val map = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

        fun get(key: String, now: Long): List<String>? {
            synchronized(map) {
                val entry = map[key] ?: return null
                if (now - entry.at > CACHE_TTL_MS) {
                    map.remove(key)
                    return null
                }
                return entry.terms
            }
        }

        fun put(key: String, terms: List<String>, now: Long) {
            synchronized(map) {
                map[key] = CacheEntry(terms, now)
                while (map.size > MAX_CACHE_ENTRIES) {
                    map.remove(map.keys.first())
                }
            }
        }

        fun clear() = synchronized(map) { map.clear() }
    }

    // Session rotation, request invalidation and cache publication share this lock.
    // Each private session also owns a distinct cache, so old workers cannot reach a
    // new session even if a transport ignores disconnect until its read timeout.
    private val stateLock = Any()
    private class Session {
        val cache = SuggestCache()
        val requests = mutableSetOf<Request>()
    }
    private class Request(val session: Session) {
        var stopped = false
        var connection: HttpURLConnection? = null
    }
    private val normalSession = Session()
    private var privateSession = Session()

    private fun cancel(request: Request) {
        val connection = synchronized(stateLock) {
            request.stopped = true
            request.connection.also { request.connection = null }
        }
        // Some platform connections wait for a read lock in disconnect(). Never make
        // input cancellation or a privacy-mode switch wait on that lock on the UI thread.
        if (connection != null) networkScope.launch { runCatching { connection.disconnect() } }
    }

    /** The endpoint that would be used for [engine], or null when the engine has none. */
    fun endpointFor(engine: SearchEngine): SuggestEndpoint? {
        BUILTIN_ENDPOINTS[engine.id]?.let { return it }
        val custom = engine.suggestUrl?.trim().orEmpty()
        if (custom.isEmpty()) return null
        return SuggestEndpoint(custom, SuggestFormat.OPEN_SEARCH_JSON)
    }

    /** Cancellation and the total deadline cover connection, redirects, headers and body. */
    suspend fun fetch(engine: SearchEngine, query: String, isPrivate: Boolean): List<String> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() || cleanQuery.length > MAX_TERM_LENGTH) return emptyList()
        val endpoint = endpointFor(engine) ?: return emptyList()
        val url = endpoint.urlTemplate
            .replace("{query}", Uri.encode(cleanQuery))
            .replace("%s", Uri.encode(cleanQuery))
        val cacheKey = "${engine.id}::$url"
        return withTimeoutOrNull(deadlineMillis) {
            suspendCancellableCoroutine { continuation ->
                val request = synchronized(stateLock) {
                    val session = if (isPrivate) privateSession else normalSession
                    val cached = session.cache.get(cacheKey, System.currentTimeMillis())
                    if (cached != null) {
                        continuation.resume(cached)
                        return@suspendCancellableCoroutine
                    }
                    Request(session).also { session.requests += it }
                }
                continuation.invokeOnCancellation { cancel(request) }
                networkScope.launch {
                    try {
                        val terms = try {
                            val body = download(url, request, endpoint.format)
                            when (endpoint.format) {
                                SuggestFormat.BAIDU_JSONP -> parseBaiduJsonp(body)
                                SuggestFormat.OPEN_SEARCH_JSON -> parseOpenSearchJson(body)
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                        synchronized(stateLock) {
                            val valid = !request.stopped && continuation.isActive &&
                                (!isPrivate || request.session === privateSession)
                            if (valid && terms.isNotEmpty()) {
                                request.session.cache.put(cacheKey, terms, System.currentTimeMillis())
                            }
                            continuation.resume(if (valid) terms else emptyList())
                        }
                    } finally {
                        synchronized(stateLock) { request.session.requests -= request }
                    }
                }
            }
        } ?: emptyList()
    }

    /** Ends the private cache lifetime and cancels every request owned by that session. */
    fun rotatePrivateSession() {
        val requests = synchronized(stateLock) {
            val previous = privateSession
            privateSession = Session()
            previous.cache.clear()
            previous.requests.toList().onEach { it.stopped = true }
        }
        requests.forEach(::cancel)
    }

    private fun download(url: String, request: Request, format: SuggestFormat): String {
        synchronized(stateLock) { if (request.stopped) throw IOException("Request cancelled") }
        val connection = openConnection(URL(url))
        try {
            synchronized(stateLock) {
                if (request.stopped) throw IOException("Request cancelled")
                request.connection = connection
            }
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, text/javascript, */*")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val chunks = java.io.ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    synchronized(stateLock) { if (request.stopped) throw IOException("Request cancelled") }
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (chunks.size() + read > MAX_RESPONSE_BYTES) throw IOException("Response exceeds size cap")
                    chunks.write(buffer, 0, read)
                }
            }
            // Baidu currently serves GBK. Honor an explicit charset for every engine;
            // malformed/unsupported encodings fail closed instead of caching replacement text.
            val charsetName = Regex("""(?i)(?:^|;)\s*charset\s*=\s*["']?([^;\s"']+)""")
                .find(connection.contentType.orEmpty())?.groupValues?.get(1)
            val charset = if (charsetName != null) Charset.forName(charsetName)
                else if (format == SuggestFormat.BAIDU_JSONP) Charset.forName("GBK") else Charsets.UTF_8
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(chunks.toByteArray())).toString()
        } finally {
            synchronized(stateLock) { request.connection = null }
            connection.disconnect()
        }
    }
}
