package com.mybrowser.translate

import android.text.Html
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
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
            TranslationProvider.MYMEMORY -> {
                if (texts.size != 1) throw TranslationException("MyMemory only supports short selections")
                val text = texts.single().trim()
                if (text.isEmpty()) return@withContext listOf("")
                if (text.toByteArray(Charsets.UTF_8).size > MYMEMORY_MAX_BYTES) {
                    throw TranslationException("MyMemory accepts at most 500 bytes per request")
                }
                currentCoroutineContext().ensureActive()
                listOf(translateMyMemory(text, target))
            }
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

    private suspend fun translateMyMemory(text: String, target: String): String {
        val source = if (settings.sourceLanguage == "auto") detectSourceLanguage(text)
        else settings.sourceLanguage
        if (source == target) throw TranslationException("Source and target languages are the same")
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
                throw TranslationException(errorMessage(text).ifBlank { "HTTP $code" })
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
        "zh-CN", "zh-TW" -> "ZH"
        else -> target.substringBefore('-').uppercase()
    }

    private fun libreLanguage(language: String): String = when (language) {
        "auto" -> "auto"
        "zh-CN", "zh-TW" -> "zh"
        else -> language.substringBefore('-')
    }

    private companion object {
        const val MYMEMORY_MAX_BYTES = 500
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
    return "en"
}
