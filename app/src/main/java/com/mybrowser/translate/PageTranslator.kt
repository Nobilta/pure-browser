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

data class TranslationPageItem(val id: Int, val text: String, val visible: Boolean = true)
data class TranslationPage(
    val items: List<TranslationPageItem>,
    val totalCharacters: Int,
    val truncated: Boolean,
)

data class TranslationComparisonSession(
    val totalCharacters: Int,
    val totalGroups: Int,
    val truncated: Boolean,
)

data class TranslationComparisonBatch(
    val items: List<TranslationPageItem>,
    val totalCharacters: Int,
    val doneCharacters: Int,
    val totalGroups: Int,
    val doneGroups: Int,
    val pending: Int,
    val complete: Boolean,
)

/** Reads and replaces text nodes without handing the page's HTML back to the renderer. */
class PageTranslator(context: Context) {
    private val script: String = context.applicationContext.assets
        .open("translation.js").bufferedReader(Charsets.UTF_8).use { it.readText() }

    suspend fun collect(webView: WebView): TranslationPage = withContext(Dispatchers.Main.immediate) {
        evaluate(webView, script)
        decodePage(evaluate(webView, "window.__pureTranslateCollect ? window.__pureTranslateCollect() : null"))
    }

    suspend fun collectNewReplace(webView: WebView): TranslationPage =
        withContext(Dispatchers.Main.immediate) {
            decodePage(evaluate(
                webView,
                "window.__pureTranslateReplaceCollectNew ? window.__pureTranslateReplaceCollectNew() : null",
            ))
        }

    suspend fun startComparison(webView: WebView): TranslationComparisonSession =
        withContext(Dispatchers.Main.immediate) {
            evaluate(webView, script)
            val raw = evaluate(
                webView,
                "window.__pureTranslateCompareStart ? window.__pureTranslateCompareStart() : null",
            ) ?: throw TranslationException("Translation helper is unavailable")
            val json = decodeString(raw) ?: throw TranslationException("Translation helper returned no data")
            val root = runCatching { JSONObject(json) }.getOrElse {
                throw TranslationException("Translation helper returned malformed data", it)
            }
            TranslationComparisonSession(
                totalCharacters = root.optInt("totalCharacters"),
                totalGroups = root.optInt("totalGroups"),
                truncated = root.optBoolean("truncated"),
            )
        }

    suspend fun takeReady(webView: WebView, maxGroups: Int = 16): TranslationComparisonBatch =
        withContext(Dispatchers.Main.immediate) {
            val raw = evaluate(
                webView,
                "window.__pureTranslateTakeReady ? window.__pureTranslateTakeReady($maxGroups, 6000) : null",
            ) ?: throw TranslationException("Translation helper is unavailable")
            val json = decodeString(raw) ?: throw TranslationException("Translation helper returned no data")
            val root = runCatching { JSONObject(json) }.getOrElse {
                throw TranslationException("Translation helper returned malformed data", it)
            }
            val array = root.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isNotBlank()) {
                        add(TranslationPageItem(item.optInt("id"), text, visible = true))
                    }
                }
            }
            TranslationComparisonBatch(
                items = items,
                totalCharacters = root.optInt("totalCharacters"),
                doneCharacters = root.optInt("doneCharacters"),
                totalGroups = root.optInt("totalGroups"),
                doneGroups = root.optInt("doneGroups"),
                pending = root.optInt("pending"),
                complete = root.optBoolean("complete"),
            )
        }

    suspend fun requeueComparison(webView: WebView, ids: List<Int>) =
        withContext(Dispatchers.Main.immediate) {
            if (ids.isEmpty()) return@withContext
            val array = JSONArray()
            ids.forEach { array.put(it) }
            evaluate(webView, "window.__pureTranslateRequeue && window.__pureTranslateRequeue($array)")
        }

    suspend fun comparisonStatus(webView: WebView): TranslationComparisonBatch =
        withContext(Dispatchers.Main.immediate) {
            val raw = evaluate(
                webView,
                "window.__pureTranslateCompareStatus ? window.__pureTranslateCompareStatus() : null",
            ) ?: throw TranslationException("Translation helper is unavailable")
            val json = decodeString(raw) ?: throw TranslationException("Translation helper returned no data")
            val root = runCatching { JSONObject(json) }.getOrElse {
                throw TranslationException("Translation helper returned malformed data", it)
            }
            TranslationComparisonBatch(
                items = emptyList(),
                totalCharacters = root.optInt("totalCharacters"),
                doneCharacters = root.optInt("doneCharacters"),
                totalGroups = root.optInt("totalGroups"),
                doneGroups = root.optInt("doneGroups"),
                pending = root.optInt("pending"),
                complete = root.optBoolean("complete"),
            )
        }

    suspend fun apply(webView: WebView, translated: List<Pair<Int, String>>) =
        withContext(Dispatchers.Main.immediate) {
            val array = JSONArray()
            translated.forEach { (id, text) -> array.put(JSONObject().put("id", id).put("text", text)) }
            evaluate(webView, "window.__pureTranslateApply && window.__pureTranslateApply($array)")
        }

    suspend fun applyComparison(webView: WebView, translated: List<Pair<Int, String>>, targetLanguage: String) =
        withContext(Dispatchers.Main.immediate) {
            val array = JSONArray()
            translated.forEach { (id, text) -> array.put(JSONObject().put("id", id).put("text", text)) }
            evaluate(
                webView,
                "window.__pureTranslateCompareApply && window.__pureTranslateCompareApply(" +
                    "$array, ${JSONObject.quote(targetLanguage)})",
            )
        }

    suspend fun visibleIds(webView: WebView, mode: TranslationPageMode): Set<Int> =
        withContext(Dispatchers.Main.immediate) {
            val raw = evaluate(
                webView,
                "window.__pureTranslateVisibleIds ? window.__pureTranslateVisibleIds(" +
                    JSONObject.quote(if (mode == TranslationPageMode.COMPARE) "compare" else "replace") + ") : '[]'",
            ) ?: return@withContext emptySet()
            val decoded = decodeString(raw) ?: return@withContext emptySet()
            runCatching {
                val array = JSONArray(decoded)
                buildSet { for (index in 0 until array.length()) add(array.optInt(index)) }
            }.getOrDefault(emptySet())
        }

    suspend fun restore(webView: WebView) = withContext(Dispatchers.Main.immediate) {
        evaluate(webView, "window.__pureTranslateRestore && window.__pureTranslateRestore()")
    }

    suspend fun restoreComparison(webView: WebView) = withContext(Dispatchers.Main.immediate) {
        evaluate(webView, "window.__pureTranslateCompareRestore && window.__pureTranslateCompareRestore()")
    }

    suspend fun translatedMode(webView: WebView): TranslationPageMode? = withContext(Dispatchers.Main.immediate) {
        val raw = evaluate(
            webView,
            "window.__pureTranslateTranslatedMode ? window.__pureTranslateTranslatedMode() : 'none'",
        )
        when (decodeString(raw)) {
            "compare" -> TranslationPageMode.COMPARE
            "replace" -> TranslationPageMode.REPLACE
            else -> null
        }
    }

    suspend fun isTranslated(webView: WebView): Boolean = translatedMode(webView) != null

    suspend fun selection(webView: WebView): String = withContext(Dispatchers.Main.immediate) {
        val raw = evaluate(webView, "window.__pureTranslateSelection ? window.__pureTranslateSelection() : " +
            "(window.getSelection ? String(window.getSelection()) : '')")
        decodeString(raw).orEmpty().trim()
    }

    private fun decodePage(raw: String?): TranslationPage {
        val json = decodeString(raw) ?: throw TranslationException("Translation helper returned no data")
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw TranslationException("Translation helper returned malformed data", it)
        }
        val array = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text")
                if (text.isNotBlank()) {
                    add(TranslationPageItem(item.optInt("id"), text, item.optBoolean("visible", true)))
                }
            }
        }
        return TranslationPage(items, root.optInt("total"), root.optBoolean("truncated"))
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
