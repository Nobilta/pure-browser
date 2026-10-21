package com.mybrowser.translate

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

data class TranslationPageItem(val id: Int, val text: String)
data class TranslationPage(
    val items: List<TranslationPageItem>,
    val totalCharacters: Int,
    val truncated: Boolean,
)

/** Reads and replaces text nodes without handing the page's HTML back to the renderer. */
class PageTranslator(context: Context) {
    private val script: String = context.applicationContext.assets
        .open("translation.js").bufferedReader(Charsets.UTF_8).use { it.readText() }

    suspend fun collect(webView: WebView): TranslationPage = withContext(Dispatchers.Main.immediate) {
        evaluate(webView, script)
        val raw = evaluate(webView, "window.__pureTranslateCollect ? window.__pureTranslateCollect() : null")
            ?: throw TranslationException("Translation helper is unavailable")
        val json = decodeString(raw) ?: throw TranslationException("Translation helper returned no data")
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw TranslationException("Translation helper returned malformed data", it)
        }
        val array = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text")
                if (text.isNotBlank()) add(TranslationPageItem(item.optInt("id"), text))
            }
        }
        TranslationPage(items, root.optInt("total"), root.optBoolean("truncated"))
    }

    suspend fun apply(webView: WebView, translated: List<Pair<Int, String>>) =
        withContext(Dispatchers.Main.immediate) {
            val array = JSONArray()
            translated.forEach { (id, text) -> array.put(JSONObject().put("id", id).put("text", text)) }
            evaluate(webView, "window.__pureTranslateApply && window.__pureTranslateApply($array)")
        }

    suspend fun restore(webView: WebView) = withContext(Dispatchers.Main.immediate) {
        evaluate(webView, "window.__pureTranslateRestore && window.__pureTranslateRestore()")
    }

    suspend fun selection(webView: WebView): String = withContext(Dispatchers.Main.immediate) {
        val raw = evaluate(webView, "window.__pureTranslateSelection ? window.__pureTranslateSelection() : " +
            "(window.getSelection ? String(window.getSelection()) : '')")
        decodeString(raw).orEmpty().trim()
    }

    private suspend fun evaluate(webView: WebView, code: String): String? = withTimeout(12_000) {
        suspendCancellableCoroutine { continuation ->
            runCatching { webView.evaluateJavascript(code) { value -> if (continuation.isActive) continuation.resume(value) } }
                .onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    private fun decodeString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }
}
