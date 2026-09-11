package com.mybrowser.backup

import android.graphics.BitmapFactory
import android.util.Base64
import com.mybrowser.R
import com.mybrowser.core.UrlUtils
import com.mybrowser.data.BookmarkFolder
import com.mybrowser.data.BookmarkFolders
import com.mybrowser.filter.FilterListFormat
import com.mybrowser.reading.ReadingArticle
import com.mybrowser.site.SiteCapability
import com.mybrowser.site.SiteOrigin
import com.mybrowser.site.SitePermission
import com.mybrowser.userscript.UserScriptMetadata
import com.mybrowser.userscript.UserScriptStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class BackupSection(val label: Int) {
    BOOKMARKS(R.string.backup_bookmarks), SETTINGS(R.string.backup_settings), HOME(R.string.backup_home),
    SITES(R.string.backup_sites), SCRIPTS(R.string.backup_scripts), READING(R.string.backup_reading), FILTERS(R.string.backup_filters)
}
data class RestoreChoice(val sections: Set<BackupSection>, val replace: Boolean = false, val permissions: Boolean = false)

/** A versioned, bounded data format; file names and preference keys come from this allowlist. */
object BackupFormat {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val VERSION = 1
    val preferenceKeys = mapOf(
        "browser_preferences" to mapOf("theme" to "string", "reading_text_zoom" to "int", "protect_private_screens" to "boolean",
            "bottom_address_bar" to "boolean", "swipe_tabs" to "boolean", "video_controls" to "boolean", "video_vertical" to "boolean",
            "video_auto_pip" to "boolean", "video_background" to "boolean",
            "video_seek" to "boolean", "video_hold" to "boolean", "video_boost" to "float", "video_landscape" to "boolean",
            "video_remember_speed" to "boolean", "video_speed" to "float"),
        "search_engines" to mapOf("current_engine_id" to "string", "custom_engines" to "string"),
        "download_settings" to mapOf("thread_count" to "int", "unmetered_only" to "boolean"),
        "filter_settings" to mapOf("enabled" to "boolean", "auto_update" to "boolean"),
        "browser_settings" to mapOf("homepage_mode" to "string", "restore_last_session" to "boolean", "homepage" to "string", "homepage_shortcuts" to "string"),
        "site_settings" to mapOf("sites" to "string"),
    )
    fun prefs(section: BackupSection): Set<String> = when (section) {
        BackupSection.SETTINGS -> setOf("browser_preferences", "search_engines", "download_settings", "filter_settings")
        BackupSection.HOME -> setOf("browser_settings")
        BackupSection.SITES -> setOf("site_settings")
        else -> emptySet()
    }
    fun allowedFile(section: BackupSection, name: String): Boolean = when (section) {
        BackupSection.HOME -> Regex("homepage_icons/[A-Za-z0-9_-]{1,64}\\.png").matches(name)
        BackupSection.SCRIPTS -> name == "userscripts/scripts.json"
        BackupSection.READING -> name in setOf("reading-list.json", "reading-positions.json")
        BackupSection.FILTERS -> name == "filter_subscriptions/subscriptions.json" || Regex("filter_subscriptions/[a-z0-9-]+-[a-f0-9]{64}\\.txt").matches(name)
        else -> false
    }
    fun create(sections: JSONObject) = JSONObject().put("format", "pure-browser-backup").put("version", VERSION)
        .put("createdAt", System.currentTimeMillis()).put("sections", sections)
    fun decode(bytes: ByteArray): JSONObject {
        require(bytes.size <= MAX_BYTES)
        return JSONObject(utf8(bytes)).also(::validate)
    }
    fun encode(document: JSONObject): ByteArray = document.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Backup exceeds 32 MiB" }
    }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    fun fileBytes(section: JSONObject, name: String): ByteArray? = section.optJSONObject("files")?.optString(name)
        ?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.DEFAULT) }
    fun fileArray(section: JSONObject, name: String): JSONArray = fileBytes(section, name)?.let { JSONArray(utf8(it)) } ?: JSONArray()
    fun putArray(section: JSONObject, name: String, array: JSONArray) {
        val files = section.optJSONObject("files") ?: JSONObject().also { section.put("files", it) }
        files.put(name, Base64.encodeToString(array.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
    }
    fun prefValue(section: JSONObject, name: String, key: String): String? = section.optJSONObject("preferences")
        ?.optJSONObject(name)?.optJSONObject(key)?.optString("value")
    fun putPref(section: JSONObject, name: String, key: String, value: String) {
        val prefs = section.optJSONObject("preferences") ?: JSONObject().also { section.put("preferences", it) }
        val store = prefs.optJSONObject(name) ?: JSONObject().also { prefs.put(name, it) }
        store.put(key, JSONObject().put("type", "string").put("value", value))
    }

    fun validate(document: JSONObject) {
        require(document.getString("format") == "pure-browser-backup" && document.getInt("version") == VERSION) { "Unsupported backup version" }
        require(document.optLong("createdAt") >= 0)
        require(encode(document).size <= MAX_BYTES)
        val sections = document.getJSONObject("sections")
        require(sections.length() in 1..BackupSection.entries.size)
        var totalFileBytes = 0L
        sections.keys().forEach { key ->
            val section = BackupSection.valueOf(key)
            val value = sections.getJSONObject(key)
            val preferences = value.optJSONObject("preferences") ?: JSONObject()
            preferences.keys().forEach { name ->
                require(name in prefs(section))
                val entries = preferences.getJSONObject(name)
                entries.keys().forEach { pref ->
                    val item = entries.getJSONObject(pref)
                    val type = preferenceKeys.getValue(name)[pref]
                    require(type != null && item.getString("type") == type)
                    val data = item.get("value")
                    require(when (type) {
                        "boolean" -> data is Boolean
                        "int" -> (data is Int || data is Long) && (data as Number).toLong() in Int.MIN_VALUE..Int.MAX_VALUE
                        "float" -> data is Number && data.toFloat().isFinite()
                        else -> data is String && data.length <= 512 * 1024
                    })
                }
            }
            val files = value.optJSONObject("files") ?: JSONObject()
            require(files.length() <= 64)
            files.keys().forEach { name ->
                require(allowedFile(section, name)) { "Unknown backup file" }
                val bytes = requireNotNull(fileBytes(value, name))
                totalFileBytes += bytes.size
                require(totalFileBytes <= MAX_BYTES)
                if (section == BackupSection.HOME) {
                    require(bytes.size <= 512 * 1024)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
                }
            }
            when (section) {
                BackupSection.BOOKMARKS -> validateBookmarks(value)
                BackupSection.SCRIPTS -> validateScripts(fileArray(value, "userscripts/scripts.json"))
                BackupSection.READING -> {
                    val articles = fileArray(value, "reading-list.json")
                    require(articles.length() <= 50)
                    repeat(articles.length()) { ReadingArticle.parse(articles.getJSONObject(it)) }
                    val positions = fileArray(value, "reading-positions.json")
                    require(positions.length() <= 200)
                    repeat(positions.length()) { i ->
                        val item = positions.getJSONObject(i); requireHttp(item.getString("url"))
                        require(item.getInt("index") in 0..600 && item.getInt("offset") in 0..500_000)
                    }
                }
                BackupSection.HOME -> validateHome(value)
                BackupSection.SITES -> validateSites(value)
                BackupSection.SETTINGS -> validateSearch(value)
                BackupSection.FILTERS -> validateFilters(value)
            }
        }
    }

    fun bookmarkFolders(value: JSONObject): List<BookmarkFolder> {
        val folders = value.getJSONArray("folders")
        require(folders.length() <= BookmarkFolders.MAX_FOLDERS)
        return List(folders.length()) { i -> folders.getJSONObject(i).let {
            BookmarkFolder(it.getLong("id"), it.getLong("parentId"), it.getString("title"), it.optLong("position"))
        } }.also { parsed ->
            BookmarkFolders.validate(parsed)
            require(parsed.all { it.id < Long.MAX_VALUE - 10_000 })
        }
    }
    private fun validateBookmarks(value: JSONObject) {
        val folders = bookmarkFolders(value)
        val rows = value.getJSONArray("bookmarks")
        require(rows.length() <= 5000)
        val ids = HashSet<Long>(); val urls = HashSet<String>()
        repeat(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val id = row.getLong("id"); val url = row.getString("url")
            require(id > 0 && id < Long.MAX_VALUE - 10_000 && ids.add(id) && urls.add(url)); requireHttp(url)
            require(row.getString("title").length <= 512 && row.getLong("createdAt") >= 0)
            BookmarkFolders.path(row.optLong("folderId"), folders)
        }
    }
    private fun validateScripts(scripts: JSONArray) {
        require(scripts.length() <= UserScriptStore.MAX_SCRIPTS)
        val ids = HashSet<String>(); var total = 0L
        repeat(scripts.length()) { i ->
            val script = scripts.getJSONObject(i)
            val source = script.getString("source")
            val metadata = UserScriptMetadata.parse(source)
            require(ids.add(metadata.id))
            total += source.toByteArray().size
            val dependencies = script.optJSONArray("requires") ?: JSONArray()
            val resources = com.mybrowser.userscript.ScriptResource.readMap(script.optJSONObject("resources") ?: JSONObject())
            require(metadata.resources.keys.containsAll(resources.keys))
            if (script.optBoolean("enabled")) require(resources.keys == metadata.resources.keys)
            total += resources.values.sumOf { it.bytes().size.toLong() }
            require(dependencies.length() <= 8)
            if (script.optBoolean("enabled")) require(metadata.supported && dependencies.length() == metadata.requires.size)
            repeat(dependencies.length()) { n ->
                val bytes = dependencies.getString(n).toByteArray()
                require(bytes.size <= UserScriptStore.MAX_REQUIRE_BYTES); total += bytes.size
            }
        }
        require(total <= UserScriptStore.MAX_TOTAL_BYTES)
    }
    private fun validateHome(value: JSONObject) {
        prefValue(value, "browser_settings", "homepage")?.let(::requireHttp)
        val shortcuts = JSONArray(prefValue(value, "browser_settings", "homepage_shortcuts") ?: "[]")
        require(shortcuts.length() <= 24)
        val ids = HashSet<String>(); val urls = HashSet<String>()
        repeat(shortcuts.length()) { i ->
            val row = shortcuts.getJSONObject(i)
            require(row.getString("title").length <= 128)
            val id = row.getString("id"); require(Regex("[A-Za-z0-9_-]{1,64}").matches(id) && ids.add(id))
            val url = row.getString("url"); requireHttp(url); require(urls.add(url))
            if (!row.isNull("icon")) require(allowedFile(BackupSection.HOME, "homepage_icons/" + row.getString("icon")))
        }
    }
    private fun validateSites(value: JSONObject) {
        val sites = JSONObject(prefValue(value, "site_settings", "sites") ?: "{}")
        require(sites.length() <= 256)
        sites.keys().forEach { origin ->
            require(SiteOrigin.of(origin) == origin)
            val item = sites.getJSONObject(origin)
            for (key in SiteCapability.entries.map { it.name } + "externalApps") {
                if (item.has(key)) SitePermission.valueOf(item.getString(key))
            }
        }
    }
    private fun validateSearch(value: JSONObject) {
        val engines = JSONArray(prefValue(value, "search_engines", "custom_engines") ?: "[]")
        require(engines.length() <= 12)
        repeat(engines.length()) { i ->
            val row = engines.getJSONObject(i)
            require(row.getString("id").length in 1..128 && row.getString("name").length in 1..64)
            val url = row.getString("url")
            require(url.length <= 2048 && Regex("\\{query}|%s").findAll(url).count() == 1)
            requireHttp(url.replace("{query}", "test").replace("%s", "test"))
        }
    }
    private fun validateFilters(value: JSONObject) {
        val lists = fileArray(value, "filter_subscriptions/subscriptions.json")
        require(lists.length() <= 35)
        val ids = HashSet<String>(); val urls = HashSet<String>(); var total = 0L
        repeat(lists.length()) { i ->
            val row = lists.getJSONObject(i)
            require(Regex("[a-z0-9-]{1,64}").matches(row.getString("id")) && ids.add(row.getString("id")))
            require(row.getString("name").length in 1..128); requireHttp(row.getString("url"))
            require(urls.add(row.getString("url")))
            val name = row.optString("file").takeIf { it.isNotBlank() && it != "null" }
            if (name != null) {
                require(allowedFile(BackupSection.FILTERS, "filter_subscriptions/$name"))
                val bytes = requireNotNull(fileBytes(value, "filter_subscriptions/$name"))
                require(bytes.size <= FilterListFormat.MAX_BYTES)
                total += bytes.size
                require(total <= FilterListFormat.MAX_TOTAL_BYTES)
                FilterListFormat.validate(utf8(bytes))
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(name == row.getString("id") + "-$hash.txt")
            }
        }
    }
    private fun requireHttp(url: String) { require(url.length <= 8192 && UrlUtils.isHttpUrl(url)) }
}
