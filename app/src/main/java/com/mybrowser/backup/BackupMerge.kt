package com.mybrowser.backup

import com.mybrowser.data.BookmarkFolders
import com.mybrowser.site.SiteCapability
import com.mybrowser.userscript.UserScriptMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object BackupMerge {
    fun plan(current: JSONObject, incoming: JSONObject, choice: RestoreChoice): JSONObject {
        require(choice.sections.isNotEmpty())
        val old = current.getJSONObject("sections")
        val source = incoming.getJSONObject("sections")
        val result = JSONObject()
        choice.sections.forEach { kind ->
            val original = old.getJSONObject(kind.name)
            val next = JSONObject(source.getJSONObject(kind.name).toString())
            if (kind == BackupSection.SCRIPTS) {
                val scripts = BackupFormat.fileArray(next, "userscripts/scripts.json")
                repeat(scripts.length()) { scripts.getJSONObject(it).put("enabled", false) }
                BackupFormat.putArray(next, "userscripts/scripts.json", scripts)
            }
            if (kind == BackupSection.SITES && !choice.permissions) {
                val sites = JSONObject(BackupFormat.prefValue(next, "site_settings", "sites") ?: "{}")
                val previous = JSONObject(BackupFormat.prefValue(original, "site_settings", "sites") ?: "{}")
                sites.keys().forEach { origin ->
                    val value = sites.getJSONObject(origin)
                    for (permission in SiteCapability.entries.map { it.name } + "externalApps") {
                        value.put(permission, previous.optJSONObject(origin)?.optString(permission, "ASK") ?: "ASK")
                    }
                }
                BackupFormat.putPref(next, "site_settings", "sites", sites.toString())
            }
            result.put(kind.name, if (choice.replace) next else merge(kind, original, next))
        }
        return BackupFormat.create(result).also(BackupFormat::validate)
    }

    private fun merge(kind: BackupSection, old: JSONObject, next: JSONObject): JSONObject {
        if (kind == BackupSection.BOOKMARKS) return mergeBookmarks(old, next)
        val result = JSONObject(next.toString())
        val files = result.optJSONObject("files") ?: JSONObject().also { result.put("files", it) }
        old.optJSONObject("files")?.let { previous -> previous.keys().forEach { files.put(it, previous.get(it)) } }
        val prefs = result.optJSONObject("preferences") ?: JSONObject().also { result.put("preferences", it) }
        old.optJSONObject("preferences")?.let { previous -> previous.keys().forEach { name ->
            val entries = prefs.optJSONObject(name) ?: JSONObject().also { prefs.put(name, it) }
            previous.getJSONObject(name).let { values -> values.keys().forEach { entries.put(it, values.get(it)) } }
        } }
        when (kind) {
            BackupSection.HOME -> {
                val previous = JSONArray(BackupFormat.prefValue(old, "browser_settings", "homepage_shortcuts") ?: "[]")
                val added = JSONArray(BackupFormat.prefValue(next, "browser_settings", "homepage_shortcuts") ?: "[]")
                val ids = (0 until previous.length()).mapTo(HashSet()) { previous.getJSONObject(it).getString("id") }
                repeat(added.length()) { i ->
                    val row = added.getJSONObject(i)
                    if (!ids.add(row.getString("id"))) row.put("id", UUID.randomUUID().toString())
                    if (!row.isNull("icon")) {
                        val name = row.getString("icon")
                        val bytes = next.optJSONObject("files")?.optString("homepage_icons/$name")
                        if (!bytes.isNullOrEmpty()) {
                            val target = "${UUID.randomUUID()}.png"
                            files.put("homepage_icons/$target", bytes); row.put("icon", target)
                        } else row.put("icon", JSONObject.NULL)
                    }
                }
                BackupFormat.putPref(result, "browser_settings", "homepage_shortcuts", mergeRows(previous, added) { it.getString("url") }.toString())
                // Keep only referenced icons. This also bounds repeated merges of the same archive.
                val kept = JSONArray(BackupFormat.prefValue(result, "browser_settings", "homepage_shortcuts"))
                val names = (0 until kept.length()).map { "homepage_icons/" + kept.getJSONObject(it).optString("icon") }.toSet()
                files.keys().asSequence().toList().filter { it !in names }.forEach(files::remove)
            }
            BackupSection.SITES -> {
                val sites = JSONObject(BackupFormat.prefValue(next, "site_settings", "sites") ?: "{}")
                val previous = JSONObject(BackupFormat.prefValue(old, "site_settings", "sites") ?: "{}")
                previous.keys().forEach { sites.put(it, previous.get(it)) }
                BackupFormat.putPref(result, "site_settings", "sites", sites.toString())
            }
            BackupSection.SETTINGS -> {
                val engines = mergeRows(JSONArray(BackupFormat.prefValue(old, "search_engines", "custom_engines") ?: "[]"),
                    JSONArray(BackupFormat.prefValue(next, "search_engines", "custom_engines") ?: "[]")) { it.getString("name").lowercase() }
                val ids = HashSet<String>()
                repeat(engines.length()) { i ->
                    val engine = engines.getJSONObject(i)
                    if (!ids.add(engine.getString("id"))) engine.put("id", "custom_" + UUID.randomUUID())
                }
                BackupFormat.putPref(result, "search_engines", "custom_engines", engines.toString())
            }
            BackupSection.SCRIPTS -> BackupFormat.putArray(result, "userscripts/scripts.json", mergeRows(
                BackupFormat.fileArray(old, "userscripts/scripts.json"), BackupFormat.fileArray(next, "userscripts/scripts.json")) {
                UserScriptMetadata.parse(it.getString("source")).id
            })
            BackupSection.READING -> for (name in listOf("reading-list.json", "reading-positions.json")) {
                BackupFormat.putArray(result, name, mergeRows(BackupFormat.fileArray(old, name), BackupFormat.fileArray(next, name)) { it.getString("url") })
            }
            BackupSection.FILTERS -> {
                val rows = mergeRows(BackupFormat.fileArray(old, "filter_subscriptions/subscriptions.json"),
                    BackupFormat.fileArray(next, "filter_subscriptions/subscriptions.json")) { it.getString("url") }
                // Built-in IDs are stable; custom IDs are derived from URLs. A foreign
                // archive reusing an ID for a different URL is rejected before writes.
                BackupFormat.putArray(result, "filter_subscriptions/subscriptions.json", rows)
                BackupStorage.pruneFiles(kind, result)
            }
            else -> Unit
        }
        return result
    }

    private fun mergeRows(first: JSONArray, second: JSONArray, key: (JSONObject) -> String): JSONArray {
        val seen = HashSet<String>(); val result = JSONArray()
        for (source in listOf(first, second)) repeat(source.length()) { i ->
            val row = source.getJSONObject(i)
            if (seen.add(key(row))) result.put(row)
        }
        return result
    }

    private fun mergeBookmarks(old: JSONObject, next: JSONObject): JSONObject {
        val result = JSONObject(old.toString())
        val folders = BackupFormat.bookmarkFolders(old).toMutableList()
        val incoming = BackupFormat.bookmarkFolders(next)
        val map = hashMapOf(0L to 0L)
        val folderJson = result.getJSONArray("folders")
        var folderId = (folders.maxOfOrNull { it.id } ?: 0) + 1
        incoming.sortedBy { BookmarkFolders.path(it.id, incoming).size }.forEach { folder ->
            val parent = map.getValue(folder.parentId)
            val known = folders.firstOrNull { it.parentId == parent && it.title == folder.title }
            val id = known?.id ?: folderId++
            map[folder.id] = id
            if (known == null) {
                val added = folder.copy(id = id, parentId = parent)
                folders.add(added)
                folderJson.put(JSONObject().put("id", id).put("parentId", parent).put("title", folder.title).put("position", folder.position))
            }
        }
        val records = result.getJSONArray("bookmarks")
        val urls = (0 until records.length()).mapTo(HashSet()) { records.getJSONObject(it).getString("url") }
        var id = (0 until records.length()).maxOfOrNull { records.getJSONObject(it).getLong("id") }?.plus(1) ?: 1
        val additions = next.getJSONArray("bookmarks")
        repeat(additions.length()) { i ->
            val row = JSONObject(additions.getJSONObject(i).toString())
            if (urls.add(row.getString("url"))) {
                row.put("id", id++).put("folderId", map.getValue(row.optLong("folderId")))
                records.put(row)
            }
        }
        return result
    }
}
