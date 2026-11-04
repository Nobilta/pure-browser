package com.mybrowser.devtools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads the current DOM serialization in bounded pages.
 *
 * The earlier source view asked for the first million characters and permanently discarded
 * everything after it. This reader asks the page for one page at a time, so a longer document
 * stays reachable; only the page on screen is held by the UI.
 */
internal class SourceReader(private val session: DevToolsSession) {
    suspend fun info(): PageResult<SourceInfo> {
        val namespace = session.namespace() ?: return PageResult.Unavailable
        val expected = session.documentId
        val raw = session.evaluateInDocument(DevToolsJs.callFunction(namespace, "sourceInfo"))
            ?: return PageResult.Unavailable
        if (!session.isCurrentDocument(expected)) return PageResult.Unavailable
        if (raw == DevToolsJs.UNAVAILABLE) return PageResult.Unavailable
        val decoded = DevToolsJs.decode(raw) ?: return PageResult.Failed(null)
        if (decoded == DevToolsJs.UNAVAILABLE) return PageResult.Unavailable
        return withContext(Dispatchers.Default) {
            val json = runCatching { JSONObject(decoded) }.getOrNull()
                ?: return@withContext PageResult.Failed(null)
            json.optString("error").takeIf { it.isNotBlank() }?.let {
                return@withContext PageResult.Failed(it.take(256))
            }
            val info = SourceInfo(
                totalChars = json.optInt("totalChars", 0).coerceAtLeast(0),
                totalLines = json.optInt("totalLines", 0).coerceAtLeast(0),
                url = json.optString("url").take(DevToolsBudgets.URL_CHARS),
                title = json.optString("title").take(512),
                sourceId = json.optInt("sourceId", 0),
            )
            PageResult.Ok(info)
        }
    }

    /** Drops only the page-side source string; DOM node ids stay valid. */
    suspend fun resetCache(): Boolean {
        val namespace = session.namespace() ?: return false
        val expected = session.documentId
        val raw = session.evaluateInDocument(DevToolsJs.call(namespace, "a.resetSource();return true;"))
        return raw != null && session.isCurrentDocument(expected)
    }

    suspend fun page(offset: Int): PageResult<SourcePage> {
        val namespace = session.namespace() ?: return PageResult.Unavailable
        val expected = session.documentId
        val safeOffset = offset.coerceAtLeast(0)
        val raw = session.evaluateInDocument(
            DevToolsJs.callFunction(
                namespace, "sourcePage", safeOffset.toString(),
                DevToolsBudgets.SOURCE_PAGE_CHARS.toString(),
                DevToolsBudgets.SOURCE_PAGE_LINES.toString(),
            )
        ) ?: return PageResult.Unavailable
        if (!session.isCurrentDocument(expected)) return PageResult.Unavailable
        if (raw == DevToolsJs.UNAVAILABLE) return PageResult.Unavailable
        val decoded = DevToolsJs.decode(raw) ?: return PageResult.Failed(null)
        if (decoded == DevToolsJs.UNAVAILABLE) return PageResult.Unavailable
        return withContext(Dispatchers.Default) {
            val json = runCatching { JSONObject(decoded) }.getOrNull()
                ?: return@withContext PageResult.Failed(null)
            json.optString("error").takeIf { it.isNotBlank() }?.let {
                return@withContext PageResult.Failed(it.take(256))
            }
            val page = SourcePage(
                offset = json.optInt("offset", safeOffset),
                end = json.optInt("end", safeOffset),
                text = json.optString("text"),
                startLine = json.optInt("startLine", 0),
                endLine = json.optInt("endLine", 0),
                totalChars = json.optInt("totalChars", 0),
                totalLines = json.optInt("totalLines", 0),
                nextOffset = if (json.isNull("next")) null else json.optInt("next"),
                continued = json.optBoolean("continued"),
                sourceId = json.optInt("sourceId", 0),
            )
            if (page.end <= page.offset && page.totalChars > page.offset) {
                PageResult.Failed(null)
            } else PageResult.Ok(page)
        }
    }

    /** Streams every page to the caller; used by export so the whole document is never held. */
    suspend fun forEachPage(onPage: suspend (SourcePage) -> Unit): Boolean {
        val expectedDocument = session.documentId
        val startInfo = (info() as? PageResult.Ok)?.value ?: return false
        val expectedSource = startInfo.sourceId
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (session.documentId != expectedDocument) return false
            val result = page(offset)
            val page = (result as? PageResult.Ok)?.value ?: return false
            if (expectedSource > 0 && page.sourceId != expectedSource) return false
            onPage(page)
            val next = page.nextOffset ?: return true
            if (next <= offset) return false
            offset = next
        }
    }
}
