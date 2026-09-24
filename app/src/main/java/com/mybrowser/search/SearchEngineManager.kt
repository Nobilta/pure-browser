package com.mybrowser.search

import com.mybrowser.data.commitConfirmed
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        private const val TAG = "SearchEngineManager"
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
     * Adds a custom search engine. [suggestUrlTemplate] is an optional HTTPS OpenSearch
     * JSON template used for online suggestions; null or blank disables online
     * suggestions for this engine without affecting submit-search.
     */
    fun addCustomEngine(
        name: String,
        searchUrlTemplate: String,
        suggestUrlTemplate: String? = null,
    ): SearchEngine {
        val cleanName = name.trim()
        val cleanTemplate = searchUrlTemplate.trim()
        val cleanSuggest = suggestUrlTemplate?.trim().orEmpty().ifEmpty { null }
        validateCustomEngine(cleanName, cleanTemplate, cleanSuggest)

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
            suggestUrl = cleanSuggest,
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

    /**
     * Runs every rule a custom engine list must satisfy, without writing anything:
     * settings imports call this to reject a bad file before any group is applied.
     */
    fun isValidCustomEngineList(engines: List<SearchEngine>): Boolean {
        if (engines.size > MAX_CUSTOM_ENGINES) return false
        if (engines.any { !Regex("custom_[A-Za-z0-9_]{1,60}").matches(it.id) || !it.isCustom || it.name.isBlank() }) return false
        if (engines.map { it.id }.toSet().size != engines.size) return false
        if (engines.map { it.name.lowercase() }.toSet().size != engines.size) return false
        for (engine in engines) {
            if (engine.name.length > MAX_NAME_LENGTH || engine.searchUrlTemplate.length > MAX_TEMPLATE_LENGTH) return false
            if (!isValidTemplate(engine.searchUrlTemplate)) return false
            if (!SearchSuggestionProvider.isValidSuggestTemplate(engine.suggestUrl)) return false
        }
        if (engines.any { it.id in BUILTIN_IDS || it.name.lowercase() in BUILTIN_NAMES }) return false
        return buildCustomEnginesJson(engines).length <= MAX_PERSISTED_JSON_LENGTH
    }

    /** The custom list and its selected id become durable in the same file edit. */
    fun importSettings(customEngines: List<SearchEngine>?, currentEngineId: String?) {
        require(customEngines == null || isValidCustomEngineList(customEngines)) { "Invalid custom engine list" }
        val finalEngines = SearchEngine.BUILTIN_ENGINES + (customEngines ?: getCustomEngines())
        val finalId = currentEngineId ?: getCurrentEngine().id
        require(finalEngines.any { it.id == finalId }) { "Current engine does not resolve" }
        prefs.commitConfirmed(buildMap {
            customEngines?.let { put(PREF_CUSTOM_ENGINES, buildCustomEnginesJson(it)) }
            put(PREF_CURRENT_ENGINE_ID, finalId)
        })
    }

    fun replaceCustomEngines(engines: List<SearchEngine>): Boolean {
        if (!isValidCustomEngineList(engines)) return false
        saveCustomEngines(engines)
        // A dangling current id (removed by the replacement) falls back via the normal rule.
        if (prefs.getString(PREF_CURRENT_ENGINE_ID, null)?.let { getEngineById(it) == null } == true) {
            setCurrentEngineById(DEFAULT_ENGINE_ID)
        }
        return true
    }

    private fun getCustomEngines(): List<SearchEngine> {
        val json = prefs.getString(PREF_CUSTOM_ENGINES, null) ?: return emptyList()
        if (json.length > MAX_PERSISTED_JSON_LENGTH) {
            Log.w(TAG, "Stored custom engines exceed $MAX_PERSISTED_JSON_LENGTH characters; ignoring them")
            return emptyList()
        }
        // Simple JSON parsing - format: [{id,name,url}]
        return try {
            parseCustomEnginesJson(json)
        } catch (e: Exception) {
            // Reporting none hides the engines until something rewrites the preference, so the
            // reason has to reach the log; nothing here deletes the stored value.
            Log.w(TAG, "Unreadable custom engines; ignoring them", e)
            emptyList()
        }
    }

    private fun saveCustomEngines(engines: List<SearchEngine>) {
        require(isValidCustomEngineList(engines)) { "Invalid or oversized custom engine list" }
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
                val rawSuggest = item.optString("suggestUrl").trim()
                if (rawName.length > MAX_NAME_LENGTH || rawUrl.length > MAX_TEMPLATE_LENGTH) {
                    continue
                }
                if (rawSuggest.isNotEmpty() &&
                    (rawSuggest.length > MAX_TEMPLATE_LENGTH ||
                        !SearchSuggestionProvider.isValidSuggestTemplate(rawSuggest))
                ) {
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
                            suggestUrl = rawSuggest.ifEmpty { null },
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
                    .put("url", engine.searchUrlTemplate)
                    .apply { engine.suggestUrl?.let { put("suggestUrl", it) } },
            )
        }
        return array.toString()
    }

    private fun validateCustomEngine(name: String, template: String, suggestTemplate: String?) {
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
        if (suggestTemplate != null) {
            require(SearchSuggestionProvider.isValidSuggestTemplate(suggestTemplate)) {
                "Suggest URL must be HTTPS with a host and exactly one {query} or %s placeholder"
            }
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
