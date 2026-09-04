package com.mybrowser.search

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages search engine selection and custom search engines.
 */
class SearchEngineManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("search_engines", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_CURRENT_ENGINE_ID = "current_engine_id"
        private const val PREF_CUSTOM_ENGINES = "custom_engines"
        private const val DEFAULT_ENGINE_ID = "baidu"
        private const val MAX_CUSTOM_ENGINES = 12
        private const val MAX_NAME_LENGTH = 64
        private const val MAX_TEMPLATE_LENGTH = 2_048
        private val BUILTIN_IDS = SearchEngine.BUILTIN_ENGINES.mapTo(HashSet()) { it.id }
        private val BUILTIN_NAMES = SearchEngine.BUILTIN_ENGINES
            .mapTo(HashSet()) { it.name.lowercase() }
        private const val MAX_PERSISTED_JSON_LENGTH = 64 * 1024
    }

    /**
     * Gets the currently selected search engine.
     */
    fun getCurrentEngine(): SearchEngine {
        val id = prefs.getString(PREF_CURRENT_ENGINE_ID, DEFAULT_ENGINE_ID) ?: DEFAULT_ENGINE_ID
        return getEngineById(id) ?: SearchEngine.BAIDU
    }

    /**
     * Sets the current search engine.
     */
    fun setCurrentEngine(engine: SearchEngine) {
        setCurrentEngineById(engine.id)
    }

    /**
     * Sets the current search engine by ID.
     */
    fun setCurrentEngineById(id: String) {
        // Never persist an ID that cannot be resolved.  A malformed preference should
        // fall back to the built-in default on the next read rather than making every
        // caller handle a dangling engine object.
        if (getEngineById(id) != null) {
            prefs.edit { putString(PREF_CURRENT_ENGINE_ID, id) }
        } else {
            prefs.edit { remove(PREF_CURRENT_ENGINE_ID) }
        }
    }

    /**
     * Gets all available search engines (built-in + custom).
     */
    fun getAvailableEngines(): List<SearchEngine> {
        return SearchEngine.BUILTIN_ENGINES + getCustomEngines()
    }

    /**
     * Gets all available search engines (built-in + custom).
     */
    fun getAllEngines(): List<SearchEngine> {
        return getAvailableEngines()
    }

    /**
     * Gets a search engine by its ID.
     */
    fun getEngineById(id: String): SearchEngine? {
        return getAllEngines().find { it.id == id }
    }

    /**
     * Adds a custom search engine.
     */
    fun addCustomEngine(name: String, searchUrlTemplate: String): SearchEngine {
        val cleanName = name.trim()
        val cleanTemplate = searchUrlTemplate.trim()
        validateCustomEngine(cleanName, cleanTemplate)

        val existing = getCustomEngines()
        require(existing.size < MAX_CUSTOM_ENGINES) {
            "Too many custom search engines (maximum $MAX_CUSTOM_ENGINES)"
        }
        require(cleanName.lowercase() !in BUILTIN_NAMES &&
            existing.none { it.name.equals(cleanName, ignoreCase = true) }) {
            "A custom search engine with this name already exists"
        }

        val id = "custom_${System.currentTimeMillis()}_${(0..9999).random()}"
        val engine = SearchEngine(
            id = id,
            name = cleanName,
            searchUrlTemplate = cleanTemplate,
            isCustom = true,
        )

        saveCustomEngines(existing + engine)

        return engine
    }

    /**
     * Removes a custom search engine.
     */
    fun removeCustomEngine(id: String) {
        val engines = getCustomEngines().filter { it.id != id }
        saveCustomEngines(engines)

        // If removed engine was current, reset to default
        if (prefs.getString(PREF_CURRENT_ENGINE_ID, null) == id) {
            setCurrentEngineById(DEFAULT_ENGINE_ID)
        }
    }

    private fun getCustomEngines(): List<SearchEngine> {
        val json = prefs.getString(PREF_CUSTOM_ENGINES, null) ?: return emptyList()
        if (json.length > MAX_PERSISTED_JSON_LENGTH) return emptyList()
        // Simple JSON parsing - format: [{id,name,url}]
        return try {
            parseCustomEnginesJson(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomEngines(engines: List<SearchEngine>) {
        val json = buildCustomEnginesJson(engines)
        prefs.edit { putString(PREF_CUSTOM_ENGINES, json) }
    }

    private fun parseCustomEnginesJson(json: String): List<SearchEngine> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val rawName = item.optString("name").trim()
                val rawUrl = item.optString("url").trim()
                if (rawName.length > MAX_NAME_LENGTH || rawUrl.length > MAX_TEMPLATE_LENGTH) {
                    continue
                }
                val name = rawName
                val url = rawUrl
                if (id.isNotEmpty() && name.isNotEmpty() && isValidTemplate(url)) {
                    // Ignore duplicate IDs/names from a hand-edited or truncated preference
                    // blob.  Built-ins are resolved first, so a custom entry also may not
                    // shadow one of their IDs.
                    if (BUILTIN_IDS.contains(id) || name.lowercase() in BUILTIN_NAMES ||
                        any { it.id == id || it.name.equals(name, true) }
                    ) {
                        continue
                    }
                    add(
                        SearchEngine(
                            id = id,
                            name = name,
                            searchUrlTemplate = url,
                            isCustom = true,
                        ),
                    )
                }
                if (size >= MAX_CUSTOM_ENGINES) break
            }
        }
    }

    private fun buildCustomEnginesJson(engines: List<SearchEngine>): String {
        val array = JSONArray()
        engines.take(MAX_CUSTOM_ENGINES).forEach { engine ->
            array.put(
                JSONObject()
                    .put("id", engine.id)
                    .put("name", engine.name)
                    .put("url", engine.searchUrlTemplate),
            )
        }
        return array.toString()
    }

    private fun validateCustomEngine(name: String, template: String) {
        require(name.isNotEmpty()) { "Search engine name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) {
            "Search engine name is too long (maximum $MAX_NAME_LENGTH characters)"
        }
        require(template.length <= MAX_TEMPLATE_LENGTH) {
            "Search URL is too long (maximum $MAX_TEMPLATE_LENGTH characters)"
        }
        require(isValidTemplate(template)) {
            "Search URL must be an HTTP(S) URL with exactly one {query} or %s placeholder"
        }
    }

    private fun isValidTemplate(template: String): Boolean {
        if (template.isEmpty() || template.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        val braceCount = template.windowed(7, partialWindows = true)
            .count { it == "{query}" }
        val percentCount = template.windowed(2, partialWindows = true)
            .count { it == "%s" }
        if (braceCount + percentCount != 1) return false

        val probe = template
            .replace("{query}", "query")
            .replace("%s", "query")
        val parsed = runCatching { probe.toUri() }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()
    }

}
