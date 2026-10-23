package com.mybrowser.translate

import android.text.Html
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class TranslationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Direct device-to-provider translation; no developer-run proxy or shared API key. */
class TranslationClient(private val settings: TranslationSettings) {

    suspend fun translate(text: String, target: String = settings.targetLanguage): String =
        translateAll(listOf(text), target).single()

    /**
     * Translates in provider-appropriate batches. MyMemory is deliberately single-short-text only:
     * its free endpoint has a small request cap and is not suitable for full-page translation.
     */
    suspend fun translateAll(
        texts: List<String>,
        target: String = settings.targetLanguage,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<String> = withContext(Dispatchers.IO) {
        require(texts.isNotEmpty())
        when (settings.provider) {
            TranslationProvider.MYMEMORY -> translateBatched(
                texts = texts,
                target = target,
                maxTextChars = MYMEMORY_MAX_CHARS,
                maxChunkChars = MYMEMORY_CHUNK_CHARS,
                translateOne = { text, lang -> translateMyMemory(text, lang) },
                autoSourceOf = if (settings.sourceLanguage == "auto") ::detectSourceLanguage else null,
                onProgress = onProgress,
            )
            TranslationProvider.GOOGLE -> translateBatched(
                texts = texts,
                target = target,
                maxTextChars = GOOGLE_MAX_TEXT_CHARS,
                maxChunkChars = GOOGLE_CHUNK_CHARS,
                maxTextBytes = GOOGLE_MAX_URL_BYTES,
                translateOne = { text, lang -> translateGoogle(text, lang) },
                onProgress = onProgress,
            )
            TranslationProvider.AZURE -> {
                if (settings.azureKey.isBlank()) throw TranslationException("Azure API key is missing")
                val output = ArrayList<String>(texts.size)
                val chunks = chunk(texts, maxItems = 40, maxChars = 10_000)
                chunks.forEachIndexed { index, chunk ->
                    currentCoroutineContext().ensureActive()
                    output += translateAzure(chunk, target)
                    onProgress(index + 1, chunks.size)
                }
                output
            }
            TranslationProvider.DEEPL -> {
                if (settings.deeplKey.isBlank()) throw TranslationException("DeepL API key is missing")
                val output = ArrayList<String>(texts.size)
                val chunks = chunk(texts, maxItems = 40, maxChars = 20_000)
                chunks.forEachIndexed { index, chunk ->
                    currentCoroutineContext().ensureActive()
                    output += translateDeepL(chunk, target)
                    onProgress(index + 1, chunks.size)
                }
                output
            }
            TranslationProvider.LIBRETRANSLATE -> {
                val endpoint = settings.libreEndpoint.trim()
                if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                    throw TranslationException("LibreTranslate endpoint is missing")
                }
                val output = ArrayList<String>(texts.size)
                texts.forEachIndexed { index, text ->
                    currentCoroutineContext().ensureActive()
                    output += if (text.isBlank()) "" else translateLibre(text, target, endpoint)
                    onProgress(index + 1, texts.size)
                }
                output
            }
        }
    }

    /**
     * Batches page text into provider-sized requests while preserving one output per input.
     * A marker between pieces survives both providers and is removed before the result is
     * returned; a provider that changes or drops a marker is rejected instead of silently
     * shifting translations between text nodes.
     */
    private suspend fun translateBatched(
        texts: List<String>,
        target: String,
        maxTextChars: Int,
        maxChunkChars: Int,
        maxTextBytes: Int? = null,
        translateOne: suspend (String, String) -> String,
        autoSourceOf: ((String) -> String)? = null,
        onProgress: (Int, Int) -> Unit,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        data class Piece(val item: Int, val part: Int, val text: String, val source: String?)

        val parts = List(texts.size) { ArrayList<String>() }
        val pieces = ArrayList<Piece>()
        texts.forEachIndexed { item, text ->
            if (text.isBlank()) return@forEachIndexed
            splitForUrl(text, maxTextChars, maxTextBytes).forEachIndexed { part, chunk ->
                parts[item] += ""
                pieces += Piece(item, part, chunk, autoSourceOf?.invoke(chunk))
            }
        }
        if (pieces.isEmpty()) return texts.map { "" }

        val chunks = ArrayList<List<Piece>>()
        var current = ArrayList<Piece>()
        var currentChars = 0
        for (piece in pieces) {
            val extra = piece.text.length + MARKER_OVERHEAD
            val sourceChanged = current.isNotEmpty() && autoSourceOf != null &&
                current.first().source != piece.source
            if (current.isNotEmpty() && (currentChars + extra > maxChunkChars || sourceChanged)) {
                chunks += current
                current = ArrayList()
                currentChars = 0
            }
            current += piece
            currentChars += extra
        }
        if (current.isNotEmpty()) chunks += current

        chunks.forEachIndexed { chunkIndex, chunk ->
            currentCoroutineContext().ensureActive()
            val translated: List<String> = if (chunk.size == 1) {
                listOf(translateOne(chunk.single().text, target))
            } else {
                val request = buildString {
                    chunk.forEachIndexed { index, piece ->
                        if (index > 0) append("\n[[[").append(index - 1).append("]]]\n")
                        append(piece.text)
                    }
                }
                parseMarkedTranslation(translateOne(request, target), chunk.size)
            }
            chunk.forEachIndexed { index, piece ->
                parts[piece.item][piece.part] = translated[index]
            }
            onProgress(chunkIndex + 1, chunks.size)
            if (chunkIndex + 1 < chunks.size) delay(BATCH_DELAY_MS)
        }
        return parts.map { itemParts -> itemParts.filter { it.isNotBlank() }.joinToString(" ").trim() }
    }

    /** Splits at a nearby space/punctuation boundary, never inside a UTF-16 surrogate pair. */
    internal fun splitForProvider(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val result = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val cut = maxOf(window.lastIndexOf(' '), window.lastIndexOf('\n'), window.lastIndexOf(". "))
                if (cut >= maxChars / 2) end = start + cut + 1
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                if (end <= start) end = minOf(start + maxChars, text.length)
            }
            val part = text.substring(start, end).trim()
            if (part.isNotEmpty()) result += part
            start = end
            while (start < text.length && text[start].isWhitespace()) start++
        }
        return result.ifEmpty { listOf(text.take(maxChars)) }
    }

    /**
     * Provider text limits are in characters, but a GET URL limit is in percent-encoded UTF-8
     * bytes. CJK text expands by roughly 9 bytes per character, so 1,800 characters can still
     * produce a 16 KB URI. Split on a character boundary first, then tighten only the pieces
     * whose encoded form would exceed the URL budget.
     */
    internal fun splitForUrl(text: String, maxChars: Int, maxBytes: Int?): List<String> {
        val parts = splitForProvider(text, maxChars)
        if (maxBytes == null) return parts
        val result = ArrayList<String>()
        for (part in parts) {
            if (URLEncoder.encode(part, "UTF-8").length <= maxBytes) {
                result += part
                continue
            }
            var start = 0
            while (start < part.length) {
                var low = start + 1
                var high = part.length
                var best = start + 1
                while (low <= high) {
                    var mid = (low + high) / 2
                    if (mid < part.length && Character.isHighSurrogate(part[mid - 1])) mid--
                    val candidate = part.substring(start, mid)
                    if (URLEncoder.encode(candidate, "UTF-8").length <= maxBytes) {
                        best = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                if (best <= start) best = minOf(start + 1, part.length)
                val piece = part.substring(start, best).trim()
                if (piece.isNotEmpty()) result += piece
                start = best
                while (start < part.length && part[start].isWhitespace()) start++
            }
        }
        return result.ifEmpty { listOf(text.take(maxChars)) }
    }

    internal fun parseMarkedTranslation(raw: String, expected: Int): List<String> {
        if (expected <= 1) return listOf(raw.trim())
        val marker = Regex("""\[\[\[\s*(\d+)\s*\]\]\]""")
        val matches = marker.findAll(raw).toList()
        if (matches.size != expected - 1) {
            throw TranslationException("Translation service changed page markers")
        }
        val result = ArrayList<String>(expected)
        var cursor = 0
        var expectedIndex = 0
        for (match in matches) {
            if (match.groupValues[1].toIntOrNull() != expectedIndex) {
                throw TranslationException("Translation service returned page markers out of order")
            }
            result += raw.substring(cursor, match.range.first).trim()
            cursor = match.range.last + 1
            expectedIndex++
        }
        result += raw.substring(cursor).trim()
        if (result.size != expected || result.any { it.isEmpty() }) {
            throw TranslationException("Translation service returned a partial page translation")
        }
        return result
    }

    private suspend fun translateGoogle(text: String, target: String): String {
        val source = if (settings.sourceLanguage == "auto") "auto" else settings.sourceLanguage
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + encode(source) +
                "&tl=" + encode(target) + "&dt=t&q=" + encode(text),
        )
        val response = request(
            url.openConnection() as HttpURLConnection,
            "GET",
            null,
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"),
        ) as? JSONArray
            ?: throw TranslationException("Google returned an unexpected response")
        val segments = response.optJSONArray(0)
            ?: throw TranslationException("Google returned no translation")
        val translated = buildString {
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                append(segment.optString(0))
            }
        }.trim()
        if (translated.isEmpty()) throw TranslationException("Google returned no translation")
        return translated
    }

    private suspend fun translateMyMemory(text: String, target: String): String {
        val source = if (settings.sourceLanguage == "auto") detectSourceLanguage(text)
        else settings.sourceLanguage
        // A mixed-language page can contain nodes already in the target language. Returning
        // those unchanged is better than failing the whole page on one such node.
        if (source == target) return text
        val url = URL(
            "https://api.mymemory.translated.net/get?q=" + encode(text) +
                "&langpair=" + encode("$source|${myMemoryTarget(target)}"),
        )
        val json = request(url.openConnection() as HttpURLConnection, "GET", null, emptyMap()) as? JSONObject
            ?: throw TranslationException("MyMemory returned an unexpected response")
        if (json.optBoolean("quotaFinished")) throw TranslationException("MyMemory quota exceeded")
        val status = json.optString("responseStatus")
        if (status.isNotEmpty() && status != "200") {
            throw TranslationException(json.optString("responseDetails").ifBlank { "MyMemory returned HTTP $status" })
        }
        val data = json.optJSONObject("responseData")
            ?: throw TranslationException("MyMemory returned no translation")
        val translated = data.optString("translatedText").trim()
        if (translated.isEmpty()) {
            throw TranslationException(json.optString("responseDetails").ifBlank { "MyMemory returned no translation" })
        }
        return Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private suspend fun translateAzure(texts: List<String>, target: String): List<String> {
        val query = StringBuilder("api-version=3.0&to=").append(encode(azureTarget(target)))
        if (settings.sourceLanguage != "auto") query.append("&from=").append(encode(azureTarget(settings.sourceLanguage)))
        val body = JSONArray().apply {
            texts.forEach { put(JSONObject().put("Text", it)) }
        }.toString()
        val headers = buildMap {
            put("Ocp-Apim-Subscription-Key", settings.azureKey.trim())
            put("Content-Type", "application/json")
            if (settings.azureRegion.isNotBlank()) put("Ocp-Apim-Subscription-Region", settings.azureRegion.trim())
        }
        val url = URL("https://api.cognitive.microsofttranslator.com/translate?$query")
        val response = request(url.openConnection() as HttpURLConnection, "POST", body, headers)
        return parseAzure(response, texts.size)
    }

    private fun parseAzure(response: Any, expected: Int): List<String> {
        val array = response as? JSONArray ?: throw TranslationException("Azure returned an unexpected response")
        if (array.length() != expected) throw TranslationException("Azure returned a partial translation")
        return (0 until expected).map { index ->
            array.getJSONObject(index).getJSONArray("translations").getJSONObject(0).getString("text")
        }
    }

    private suspend fun translateDeepL(texts: List<String>, target: String): List<String> {
        val endpoint = if (settings.deeplKey.trim().endsWith(":fx")) {
            "https://api-free.deepl.com/v2/translate"
        } else {
            "https://api.deepl.com/v2/translate"
        }
        val body = buildString {
            texts.forEach { append("text=").append(encode(it)).append('&') }
            append("target_lang=").append(encode(deepLTarget(target)))
            if (settings.sourceLanguage != "auto") append("&source_lang=").append(encode(deepLTarget(settings.sourceLanguage)))
        }
        val response = request(
            URL(endpoint).openConnection() as HttpURLConnection,
            "POST",
            body,
            mapOf(
                "Authorization" to "DeepL-Auth-Key " + settings.deeplKey.trim(),
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
        )
        val translations = (response as? JSONObject)?.optJSONArray("translations")
            ?: throw TranslationException("DeepL returned no translation")
        if (translations.length() != texts.size) throw TranslationException("DeepL returned a partial translation")
        return (0 until translations.length()).map { translations.getJSONObject(it).getString("text") }
    }

    private suspend fun translateLibre(text: String, target: String, endpoint: String): String {
        val body = JSONObject().apply {
            put("q", text)
            put("source", libreLanguage(settings.sourceLanguage))
            put("target", libreLanguage(target))
            put("format", "text")
            if (settings.libreKey.isNotBlank()) put("api_key", settings.libreKey.trim())
        }.toString()
        val response = request(
            URL(endpoint.trimEnd('/') + "/translate").openConnection() as HttpURLConnection,
            "POST",
            body,
            mapOf("Content-Type" to "application/json"),
        )
        val translated = (response as? JSONObject)?.optString("translatedText").orEmpty()
        if (translated.isEmpty()) throw TranslationException("LibreTranslate returned no translation")
        return translated
    }

    private suspend fun request(
        connection: HttpURLConnection,
        method: String,
        body: String?,
        headers: Map<String, String>,
    ): Any {
        val job = currentCoroutineContext()[Job]
        // A blocking HttpURLConnection keeps its Job active until the request ends, so a
        // completion hook would run too late. A child started undispatched observes parent
        // cancellation immediately and closes the socket from the finally block.
        val disconnectOnCancel = job?.let { parent ->
            CoroutineScope(parent + Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            currentCoroutineContext().ensureActive()
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(8 * 1024)
                val out = StringBuilder()
                while (out.length < MAX_RESPONSE_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }.orEmpty()
            if (code !in 200..299) {
                val message = when (code) {
                    429 -> "Translation service is rate limiting requests (HTTP 429)"
                    413, 414 -> "Translation request is too long (HTTP $code)"
                    else -> errorMessage(text)
                }
                throw TranslationException(message.ifBlank { "HTTP $code" })
            }
            currentCoroutineContext().ensureActive()
            return runCatching { JSONTokener(text).nextValue() }
                .getOrElse { throw TranslationException("Invalid translation response", it) }
        } catch (error: TranslationException) {
            throw error
        } catch (error: Exception) {
            // A cancellation disconnects the socket, which surfaces here as an IOException.
            // Re-check the job so that cancellation wins over a translated network error.
            currentCoroutineContext().ensureActive()
            throw TranslationException(error.message ?: "Translation request failed", error)
        } finally {
            disconnectOnCancel?.cancel()
            connection.disconnect()
        }
    }

    private fun errorMessage(raw: String): String = runCatching {
        val json = JSONObject(raw)
        json.optJSONObject("error")?.optString("message")
            ?: json.optString("message")
            ?: json.optString("error")
            ?: raw
    }.getOrDefault(raw).take(300)

    private fun chunk(texts: List<String>, maxItems: Int, maxChars: Int): List<List<String>> {
        val result = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (text in texts) {
            if (current.isNotEmpty() && (current.size >= maxItems || chars + text.length > maxChars)) {
                result += current
                current = ArrayList()
                chars = 0
            }
            current += text
            chars += text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun myMemoryTarget(target: String): String = when (target) {
        "zh-CN", "zh-TW" -> target
        else -> target.substringBefore('-')
    }

    private fun azureTarget(target: String): String = when (target) {
        "zh-CN" -> "zh-Hans"
        "zh-TW" -> "zh-Hant"
        else -> target.substringBefore('-')
    }

    private fun deepLTarget(target: String): String = when (target) {
        "zh-CN" -> "ZH"
        "zh-TW" -> "ZH-HANT"
        else -> target.substringBefore('-').uppercase()
    }

    private fun libreLanguage(language: String): String = when (language) {
        "auto" -> "auto"
        "zh-CN", "zh-TW" -> "zh"
        else -> language.substringBefore('-')
    }

    private companion object {
        // MyMemory documents a 500-character request cap; Google's public web endpoint
        // accepts larger inputs but is deliberately chunked well below its practical limit.
        const val MYMEMORY_MAX_CHARS = 500
        const val MYMEMORY_CHUNK_CHARS = 460
        // Google is a web endpoint, not an API with a documented request limit. Keep the
        // encoded URL comfortably below proxy/server URI limits; long pages become several
        // requests instead of one 414.
        const val GOOGLE_MAX_TEXT_CHARS = 1_800
        const val GOOGLE_CHUNK_CHARS = 1_900
        // Leave room for the endpoint, parameters and marker overhead inside a typical 8 KB URI.
        const val GOOGLE_MAX_URL_BYTES = 6_000
        const val MARKER_OVERHEAD = 24
        const val BATCH_DELAY_MS = 150L
        const val MAX_RESPONSE_CHARS = 256 * 1024
    }
}

/** Local fallback language hint for MyMemory, which requires a concrete source pair. */
internal fun detectSourceLanguage(text: String): String {
    if (text.any { it.code in 0x3040..0x30FF }) return "ja"
    if (text.any { it.code in 0xAC00..0xD7AF }) return "ko"
    if (text.any { it.code in 0x4E00..0x9FFF }) return "zh-CN"
    if (text.any { it.code in 0x0400..0x04FF }) return "ru"
    if (text.any { it.code in 0x0600..0x06FF }) return "ar"
    if (text.any { it.code in 0x0590..0x05FF }) return "he"

    // MyMemory cannot auto-detect. A small stop-word profile keeps French, German, Spanish,
    // Portuguese and Italian pages from being sent as English; unknown Latin text still falls
    // back to English rather than guessing a language the user did not choose.
    val lower = text.lowercase()
    val words = lower.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }.toSet()
    // Use only words that are distinctive for one language. Shared articles ("la", "una",
    // "le") made short French/Spanish/Italian text collapse to whichever profile came first.
    val profiles = listOf(
        "fr" to setOf("les", "des", "une", "est", "pour", "avec", "dans", "qui", "pas", "aux", "cette", "été"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "mit", "für", "auf", "eine", "den", "dem", "sich"),
        "es" to setOf("los", "las", "es", "para", "por", "del", "como", "y", "el"),
        "pt" to setOf("os", "as", "uma", "não", "em", "do", "da", "com", "é"),
        "it" to setOf("il", "gli", "è", "per", "che", "non", "della", "si", "come"),
    )
    val scores = profiles.associate { (language, stopWords) -> language to words.count { it in stopWords } }
    val best = scores.values.maxOrNull() ?: 0
    if (best <= 0) {
        return when {
            'ß' in lower -> "de"
            'ñ' in lower || '¿' in lower || '¡' in lower -> "es"
            'ã' in lower || 'õ' in lower -> "pt"
            'œ' in lower || 'æ' in lower -> "fr"
            'ì' in lower || 'ò' in lower -> "it"
            else -> "en"
        }
    }
    return scores.maxByOrNull { it.value }!!.key
}
