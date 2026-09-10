package com.mybrowser.tabs

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import com.mybrowser.core.UrlUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Owns one tab stack and the lightweight state associated with each tab.
 *
 * MainActivity keeps separate instances for normal and incognito mode. Keeping this class
 * single-stack avoids the old double-normal/incognito state machine, which could leave a
 * tab in the wrong list when the Activity switched managers. The list is a Compose
 * SnapshotStateList and [revision] changes whenever mutable tab fields change, so tab
 * sheets update immediately after a title, favicon, or close operation.
 */
class TabManager(
    private val maxTabs: Int = MAX_TABS,
    private val rememberClosedTabs: Boolean = true,
) {

    init {
        require(maxTabs in 1..MAX_TABS) { "maxTabs must be between 1 and $MAX_TABS" }
    }

    private val _tabs = mutableStateListOf<TabState>()
    private val _recentlyClosed = mutableStateListOf<ClosedTab>()
    val recentlyClosed: List<ClosedTab> get() = _recentlyClosed

    var currentIndex by mutableIntStateOf(-1)
        private set

    /** Observable revision for mutations to fields inside [TabState]. */
    var revision by mutableIntStateOf(0)
        private set

    val tabs: List<TabState> get() = _tabs
    val currentTab: TabState? get() = _tabs.getOrNull(currentIndex)
    val count: Int get() = _tabs.size
    val canCreateTab: Boolean get() = _tabs.size < maxTabs

    init {
        createTab()
    }

    /** Compatibility helper for callers that used to toggle a manager's internal mode. */
    fun setIncognitoMode(enabled: Boolean) {
        if (!enabled) return
        if (_tabs.isEmpty()) createTab()
    }

    /** Background tabs remain metadata until selected. At the cap, returns the current id. */
    fun createTab(url: String = "", select: Boolean = true, title: String = ""): String {
        if (_tabs.size >= maxTabs) return currentTab?.id.orEmpty()
        val tab = TabState(id = UUID.randomUUID().toString(), url = safeTabUrl(url), title = title.take(MAX_TAB_TITLE_LENGTH))
        _tabs += tab
        if (select || currentIndex < 0) currentIndex = _tabs.lastIndex
        changed()
        return tab.id
    }

    fun switchToIndex(index: Int): TabState? {
        if (index !in _tabs.indices) return null
        currentIndex = index
        changed()
        return _tabs[index]
    }

    fun switchToId(id: String): TabState? {
        val index = _tabs.indexOfFirst { it.id == id }
        return if (index < 0) null else switchToIndex(index)
    }

    fun closeTab(index: Int): TabState? {
        if (index !in _tabs.indices) return currentTab
        val removed = _tabs.removeAt(index)
        rememberClosed(removed, index)
        releaseBitmaps(removed)

        when {
            _tabs.isEmpty() -> {
                currentIndex = -1
                createTab()
            }
            index < currentIndex -> currentIndex -= 1
            index == currentIndex && currentIndex >= _tabs.size -> currentIndex = _tabs.lastIndex
        }
        changed()
        return currentTab
    }

    fun closeTabById(id: String): TabState? =
        _tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::closeTab) ?: currentTab

    /** Closes every tab except the selected one. */
    fun closeOtherTabs() {
        val current = currentTab ?: return
        _tabs.forEachIndexed { index, tab ->
            if (tab.id != current.id) {
                rememberClosed(tab, index)
                releaseBitmaps(tab)
            }
        }
        _tabs.clear()
        _tabs += current
        currentIndex = 0
        changed()
    }

    /** Clears this stack and creates one fresh tab. Used when leaving incognito mode. */
    fun clearAllTabs(remember: Boolean = false) {
        if (remember) _tabs.forEachIndexed { index, tab -> rememberClosed(tab, index) }
        _tabs.forEach(::releaseBitmaps)
        _tabs.clear()
        currentIndex = -1
        createTab()
        changed()
    }

    private fun rememberClosed(tab: TabState, index: Int) {
        if (!rememberClosedTabs || !UrlUtils.isHttpUrl(tab.url)) return
        _recentlyClosed.add(0, ClosedTab(UUID.randomUUID().toString(), safeTabUrl(tab.url),
            sanitizePersistedText(tab.title, MAX_TAB_TITLE_LENGTH), index))
        while (_recentlyClosed.size > MAX_RECENTLY_CLOSED) _recentlyClosed.removeAt(_recentlyClosed.lastIndex)
    }

    fun reopenClosed(id: String): TabState? {
        val entry = _recentlyClosed.firstOrNull { it.id == id } ?: return null
        val replaceBlank = _tabs.size == 1 && _tabs[0].url.let { it.isBlank() || it == ABOUT_BLANK }
        if (!canCreateTab && !replaceBlank) return null
        if (replaceBlank) { releaseBitmaps(_tabs[0]); _tabs.clear() }
        val index = entry.index.coerceIn(0, _tabs.size)
        val tab = TabState(UUID.randomUUID().toString(), entry.url, entry.title)
        _tabs.add(index, tab)
        currentIndex = index
        _recentlyClosed.remove(entry)
        changed()
        return tab
    }

    fun clearRecentlyClosed() { _recentlyClosed.clear(); changed() }

    fun saveRecentlyClosed(context: Context) {
        if (!rememberClosedTabs) return
        context.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_RECENT, recentJson())
        }
    }

    fun restoreRecentlyClosed(context: Context) {
        if (!rememberClosedTabs) return
        restoreRecentJson(context.getSharedPreferences(RECENT_PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, null))
    }

    private fun recentJson(): String = JSONArray().apply {
        _recentlyClosed.forEach { entry -> put(JSONObject().put("id", entry.id).put("url", entry.url)
            .put("title", entry.title).put("index", entry.index)) }
    }.toString()

    private fun restoreRecentJson(raw: String?) {
        if (!rememberClosedTabs || raw == null || raw.length > MAX_PERSISTED_JSON_LENGTH) return
        val restored = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_RECENTLY_CLOSED)) {
                    val entry = array.optJSONObject(index) ?: continue
                    val url = safeTabUrl(entry.optString("url"))
                    if (!UrlUtils.isHttpUrl(url)) continue
                    add(ClosedTab(UUID.randomUUID().toString(), url,
                        sanitizePersistedText(entry.optString("title"), MAX_TAB_TITLE_LENGTH),
                        entry.optInt("index").coerceIn(0, MAX_TABS - 1)))
                }
            }
        }.getOrDefault(emptyList())
        _recentlyClosed.clear(); _recentlyClosed.addAll(restored); changed()
    }

    fun saveCurrentState(webView: WebView) {
        val tab = currentTab ?: return
        runCatching {
            tab.savedState = Bundle().takeIf { webView.saveState(it) != null }
        }
        webView.url?.takeIf { it.isNotBlank() }?.let { tab.url = it }
        webView.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
        changed()
    }

    fun loadCurrentState(webView: WebView): Boolean {
        val state = currentTab?.savedState ?: return false
        val restored = runCatching { webView.restoreState(state) != null }.getOrDefault(false)
        if (!restored) {
            currentTab?.savedState = null
            changed()
        }
        return restored
    }

    /** Keep only the small display bitmap; encoding a duplicate cache copy blocked tab switching. */
    fun captureCurrentThumbnail(webView: WebView, width: Int, height: Int) {
        val tab = currentTab ?: return
        if (webView.width <= 0 || webView.height <= 0 || width <= 0 || height <= 0) return
        val scale = width.toFloat() / webView.width.toFloat()
        val targetHeight = (webView.height * scale).toInt().coerceIn(1, height)
        val bitmap = runCatching {
            createBitmap(width, targetHeight, Bitmap.Config.RGB_565).also { output ->
                android.graphics.Canvas(output).apply {
                    scale(scale, scale)
                    webView.draw(this)
                }
            }
        }.getOrNull() ?: return

        tab.thumbnail = bitmap
        changed()
    }

    fun getThumbnail(tabId: String): Bitmap? =
        _tabs.find { it.id == tabId }?.thumbnail

    /** Saves URL/title metadata so normal tabs survive process death. */
    fun saveMetadata(context: Context, preferenceName: String) {
        val snapshot = snapshotMetadata()
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit {
            putString(KEY_TABS, snapshot.getString(KEY_TABS))
            putInt(KEY_CURRENT, snapshot.getInt(KEY_CURRENT))
        }
    }

    fun clearMetadata(context: Context, preferenceName: String) {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit { clear() }
    }

    fun snapshotMetadata(): Bundle {
        val array = JSONArray()
        _tabs.forEach { tab ->
            array.put(
                JSONObject()
                    .put("id", tab.id.take(MAX_TAB_ID_LENGTH))
                    .put("url", safeTabUrl(tab.url))
                    .put("title", sanitizePersistedText(tab.title, MAX_TAB_TITLE_LENGTH)),
            )
        }
        return Bundle().apply {
            putString(KEY_TABS, array.toString())
            putInt(KEY_CURRENT, currentIndex)
            if (rememberClosedTabs) putString(KEY_RECENT, recentJson())
        }
    }

    /** Restores metadata; returns false when no usable saved tabs exist. */
    fun restoreMetadata(context: Context, preferenceName: String): Boolean {
        val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TABS, null) ?: return false
        return restoreMetadata(Bundle().apply {
            putString(KEY_TABS, raw)
            putInt(KEY_CURRENT, prefs.getInt(KEY_CURRENT, 0))
        })
    }

    fun restoreMetadata(snapshot: Bundle): Boolean {
        val raw = snapshot.getString(KEY_TABS) ?: return false
        if (raw.length > MAX_PERSISTED_JSON_LENGTH) return false
        val restored = runCatching {
            val array = JSONArray(raw)
            val ids = mutableSetOf<String>()
            buildList {
                for (i in 0 until minOf(array.length(), maxTabs)) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = safeTabUrl(obj.optString("url"))
                    val title = sanitizePersistedText(obj.optString("title"), MAX_TAB_TITLE_LENGTH)
                    val id = obj.optString("id")
                        .takeIf { TAB_ID_PATTERN.matches(it) && ids.add(it) }
                        ?: UUID.randomUUID().toString()
                    add(
                        TabState(
                            id = id.take(MAX_TAB_ID_LENGTH),
                            url = url,
                            title = title,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        if (restored.isEmpty()) return false

        _tabs.forEach(::releaseBitmaps)
        _tabs.clear()
        _tabs += restored.take(maxTabs)
        currentIndex = snapshot.getInt(KEY_CURRENT, 0).coerceIn(_tabs.indices)
        restoreRecentJson(snapshot.getString(KEY_RECENT))
        changed()
        return true
    }

    fun cleanup() {
        _tabs.forEach(::releaseBitmaps)
        _tabs.clear()
        _recentlyClosed.clear()
        currentIndex = -1
    }

    /** Marks a mutation to a TabState's mutable fields so Compose refreshes tab chrome. */
    fun notifyChanged() {
        changed()
    }

    private fun releaseBitmaps(tab: TabState) {
        // Compose/RenderThread may still be drawing the previous snapshot. Let bitmap
        // references expire naturally instead of recycling memory under a pending frame.
        tab.favicon = null
        tab.thumbnail = null
    }

    private fun changed() {
        revision++
    }

    private fun sanitizePersistedText(value: String, maxLength: Int): String = value
        .filterNot { it.isISOControl() }
        .trim()
        .take(maxLength)

    /** A preference blob must never turn into javascript:/file: navigation on restore. */
    private fun safeTabUrl(value: String): String {
        val clean = sanitizePersistedText(value, MAX_TAB_URL_LENGTH)
        return when {
            clean.isEmpty() || clean == ABOUT_BLANK -> clean
            UrlUtils.isHttpUrl(clean) -> clean
            else -> ""
        }
    }

    private companion object {
        const val MAX_TABS = 32
        const val MAX_RECENTLY_CLOSED = 20
        const val RECENT_PREFS = "recently_closed_tabs"
        const val KEY_RECENT = "recent"
        const val MAX_TAB_ID_LENGTH = 64
        const val MAX_TAB_URL_LENGTH = 8_192
        const val MAX_TAB_TITLE_LENGTH = 512
        const val MAX_PERSISTED_JSON_LENGTH = 256 * 1024
        val TAB_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        const val ABOUT_BLANK = "about:blank"
        const val KEY_TABS = "tabs"
        const val KEY_CURRENT = "current"
    }
}
