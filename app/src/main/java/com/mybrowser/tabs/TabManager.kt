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
class TabManager(private val maxTabs: Int = MAX_TABS) {

    init {
        require(maxTabs in 1..MAX_TABS) { "maxTabs must be between 1 and $MAX_TABS" }
    }

    private val _tabs = mutableStateListOf<TabState>()
    private val activationOrder = mutableListOf<String>()

    private fun rememberSelection() {
        currentTab?.id?.let { activationOrder.remove(it); activationOrder.add(it) }
    }

    var currentIndex by mutableIntStateOf(-1)
        private set

    /** Observable revision for mutations to fields inside [TabState]. */
    var revision by mutableIntStateOf(0)
        private set

    val tabs: List<TabState> get() = _tabs
    val currentTab: TabState? get() = _tabs.getOrNull(currentIndex)
    val count: Int get() = _tabs.size
    val canCreateTab: Boolean get() = _tabs.size < maxTabs
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    fun scheduleSaveMetadata(context: Context, preferenceName: String) {
        pendingSave?.let(saveHandler::removeCallbacks)
        pendingSave = Runnable { saveMetadata(context.applicationContext, preferenceName) }.also { saveHandler.postDelayed(it, 350) }
    }

    init {
        createTab()
    }

    /** Background tabs remain metadata until selected. At the cap, returns the current id. */
    fun createTab(url: String = "", select: Boolean = true, title: String = "", openerTabId: String? = null): String {
        if (_tabs.size >= maxTabs) return currentTab?.id.orEmpty()
        val tab = TabState(id = UUID.randomUUID().toString(), url = safeTabUrl(url), title = title.take(MAX_TAB_TITLE_LENGTH),
            group = currentTab?.group.orEmpty(), openerTabId = openerTabId?.takeIf { id -> _tabs.any { it.id == id } })
        _tabs += tab
        if (select || currentIndex < 0) { currentIndex = _tabs.lastIndex; rememberSelection() }
        changed()
        return tab.id
    }

    fun switchToIndex(index: Int): TabState? {
        if (index !in _tabs.indices) return null
        currentIndex = index
        rememberSelection()
        changed()
        return _tabs[index]
    }

    fun move(id: String, offset: Int) {
        val from = _tabs.indexOfFirst { it.id == id }
        if (from < 0) return
        val current = currentTab?.id
        val destination = (from + offset.coerceIn(-1, 1)).coerceIn(_tabs.indices)
        if (destination == from) return
        val tab = _tabs.removeAt(from); _tabs.add(destination, tab)
        currentIndex = _tabs.indexOfFirst { it.id == current }
        changed()
    }

    fun setGroup(id: String, name: String) {
        _tabs.firstOrNull { it.id == id }?.group = sanitizePersistedText(name, 40)
        changed()
    }

    fun closeTab(index: Int): TabState? {
        if (index !in _tabs.indices) return currentTab
        val selectedId = currentTab?.id
        val removed = _tabs.removeAt(index)
        releaseBitmaps(removed)
        activationOrder.remove(removed.id)
        _tabs.forEach { if (it.openerTabId == removed.id) it.openerTabId = null }
        if (_tabs.isEmpty()) {
            currentIndex = -1
            createTab()
        } else {
            val nextId = if (removed.id != selectedId) selectedId else {
                removed.openerTabId?.takeIf { id -> _tabs.any { it.id == id } }
                    ?: activationOrder.lastOrNull { id -> _tabs.any { it.id == id } }
                    ?: _tabs[index.coerceAtMost(_tabs.lastIndex)].id
            }
            currentIndex = _tabs.indexOfFirst { it.id == nextId }.coerceAtLeast(0)
            rememberSelection()
        }
        changed()
        return currentTab
    }

    /** Closes every tab except the selected one. */
    fun closeOtherTabs() {
        val current = currentTab ?: return
        _tabs.forEach { tab ->
            if (tab.id != current.id) {
                releaseBitmaps(tab)
            }
        }
        _tabs.clear()
        activationOrder.clear()
        current.openerTabId = null
        _tabs += current
        currentIndex = 0
        rememberSelection()
        changed()
    }

    /** Clears this stack and creates one fresh tab for close-all or private-mode exit. */
    fun clearAllTabs() {
        _tabs.forEach(::releaseBitmaps)
        _tabs.clear()
        activationOrder.clear()
        currentIndex = -1
        createTab()
        changed()
    }

    fun saveCurrentState(webView: WebView) {
        val tab = currentTab ?: return
        runCatching {
            tab.savedState = Bundle().takeIf { webView.saveState(it) != null }
        }.onFailure {
            // Losing this bundle only costs a document restore later; silence would make the
            // regression invisible in a bug report.
            android.util.Log.w("TabManager", "Could not save the page state for " + tab.id, it)
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

    /** Saves URL/title metadata so normal tabs survive process death. */
    fun saveMetadata(context: Context, preferenceName: String) {
        pendingSave?.let(saveHandler::removeCallbacks)
        pendingSave = null
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
                    .put("title", sanitizePersistedText(tab.title, MAX_TAB_TITLE_LENGTH)).put("group", tab.group),
            )
        }
        return Bundle().apply {
            putString(KEY_TABS, array.toString())
            putInt(KEY_CURRENT, currentIndex)
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
                            group = sanitizePersistedText(obj.optString("group"), 40),
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
        activationOrder.clear()
        rememberSelection()
        changed()
        return true
    }

    fun cleanup() {
        pendingSave?.run()
        _tabs.forEach(::releaseBitmaps)
        _tabs.clear()
        activationOrder.clear()
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
