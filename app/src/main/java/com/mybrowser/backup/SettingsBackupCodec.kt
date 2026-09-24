package com.mybrowser.backup

import com.mybrowser.core.PlaybackSpeed
import com.mybrowser.core.VideoFit
import com.mybrowser.data.BookmarkFolders
import com.mybrowser.data.BookmarkHtml
import com.mybrowser.data.ImportedHistory
import com.mybrowser.download.MAX_DOWNLOAD_THREADS
import com.mybrowser.download.MIN_DOWNLOAD_THREADS
import com.mybrowser.site.SiteSettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for [SettingsBackup]. Decoding validates every known field strictly — a
 * wrong type, enum or range rejects the whole file — while unknown fields and groups
 * are ignored so newer exporters stay readable.
 */
object SettingsBackupCodec {

    /**
     * The file carries settings plus the browsing library, so the bound follows the
     * bookmark HTML import budget rather than the old settings-only size. Everything
     * inside it is still validated field by field before any write.
     */
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_APP_VERSION_CHARS = 128
    const val MAX_EXPORTED_AT_CHARS = 64
    const val MAX_BUILT_IN_ID_CHARS = 64
    const val MAX_BUILT_IN_ENTRIES = 64

    /** Library bounds mirror the stores that receive the rows, so import cannot fail on size. */
    const val MAX_BACKUP_BOOKMARKS = BookmarkHtml.MAX_BOOKMARKS
    const val MAX_BACKUP_HISTORY = 20_000
    const val MAX_LIBRARY_URL_CHARS = 8_192
    const val MAX_LIBRARY_TITLE_CHARS = 512
    const val MAX_FOLDER_PATH_DEPTH = BookmarkFolders.MAX_DEPTH
    const val MAX_FOLDER_PATH_SEGMENT_CHARS = BookmarkFolders.MAX_NAME
    const val MAX_FOLDER_PATHS = BookmarkFolders.MAX_FOLDERS

    /** 2100-01-01: a crafted file cannot place visits arbitrarily far into the future. */
    const val MAX_VISIT_TIME_MILLIS = 4_102_444_800_000

    fun encode(backup: SettingsBackup): String {
        val root = JSONObject()
            .put("format", backup.format)
            .put("schemaVersion", backup.schemaVersion)
            .put("appVersion", backup.appVersion)
            .put("exportedAt", backup.exportedAt)
            .put("settings", JSONObject())
        val settings = root.getJSONObject("settings")

        backup.settings.browser?.let { browser ->
            val group = JSONObject()
            browser.theme?.let { group.put("theme", it) }
            browser.bottomAddressBar?.let { group.put("bottomAddressBar", it) }
            browser.swipeTabs?.let { group.put("swipeTabs", it) }
            browser.autoCheckUpdates?.let { group.put("autoCheckUpdates", it) }
            browser.incognitoEnabled?.let { group.put("incognitoEnabled", it) }
            browser.searchSuggestionsEnabled?.let { group.put("searchSuggestionsEnabled", it) }
            browser.privateSearchSuggestionsEnabled?.let { group.put("privateSearchSuggestionsEnabled", it) }
            browser.browserFullscreenEnabled?.let { group.put("browserFullscreenEnabled", it) }
            browser.video?.let { video ->
                val v = JSONObject()
                video.automaticPip?.let { v.put("automaticPip", it) }
                video.backgroundPlayback?.let { v.put("backgroundPlayback", it) }
                video.enhancedControls?.let { v.put("enhancedControls", it) }
                video.verticalGestures?.let { v.put("verticalGestures", it) }
                video.horizontalSeek?.let { v.put("horizontalSeek", it) }
                video.holdToBoost?.let { v.put("holdToBoost", it) }
                video.boostRate?.let { v.put("boostRate", it.toDouble()) }
                video.landscapeFullscreen?.let { v.put("landscapeFullscreen", it) }
                video.rememberSpeed?.let { v.put("rememberSpeed", it) }
                video.preferredSpeed?.let { v.put("preferredSpeed", it.toDouble()) }
                group.put("video", v)
            }
            settings.put("browser", group)
        }

        backup.settings.home?.let { home ->
            val group = JSONObject()
            home.mode?.let { group.put("mode", it) }
            home.fixedUrl?.let { group.put("fixedUrl", it) }
            home.restoreLastSession?.let { group.put("restoreLastSession", it) }
            settings.put("home", group)
        }

        backup.settings.search?.let { search ->
            val group = JSONObject()
            search.currentEngineId?.let { group.put("currentEngineId", it) }
            search.customEngines?.let { engines ->
                val array = JSONArray()
                engines.forEach { engine ->
                    val item = JSONObject()
                        .put("id", engine.id)
                        .put("name", engine.name)
                        .put("template", engine.template)
                    engine.suggestUrlTemplate?.let { item.put("suggestUrlTemplate", it) }
                    array.put(item)
                }
                group.put("customEngines", array)
            }
            settings.put("search", group)
        }

        backup.settings.downloads?.let { downloads ->
            val group = JSONObject()
            downloads.threadCount?.let { group.put("threadCount", it) }
            downloads.unmeteredOnly?.let { group.put("unmeteredOnly", it) }
            downloads.directoryModeHint?.let { group.put("directoryModeHint", it) }
            settings.put("downloads", group)
        }

        backup.settings.filtering?.let { filtering ->
            val group = JSONObject()
            filtering.enabled?.let { group.put("enabled", it) }
            filtering.autoUpdate?.let { group.put("autoUpdate", it) }
            filtering.builtIns?.let { builtIns ->
                val array = JSONArray()
                builtIns.forEach { array.put(JSONObject().put("id", it.id).put("enabled", it.enabled)) }
                group.put("builtIns", array)
            }
            filtering.customSubscriptions?.let { subscriptions ->
                val array = JSONArray()
                subscriptions.forEach {
                    array.put(JSONObject().put("name", it.name).put("url", it.url).put("enabled", it.enabled))
                }
                group.put("customSubscriptions", array)
            }
            settings.put("filtering", group)
        }

        backup.settings.sites?.let { sites ->
            val array = JSONArray()
            sites.forEach { site ->
                val preferences = JSONObject()
                val p = site.preferences
                p.filtering?.let { preferences.put("filtering", it) }
                p.javaScript?.let { preferences.put("javaScript", it) }
                p.images?.let { preferences.put("images", it) }
                p.thirdPartyCookies?.let { preferences.put("thirdPartyCookies", it) }
                p.desktop?.let { preferences.put("desktop", it) }
                p.textZoom?.let { preferences.put("textZoom", it) }
                p.webDarkening?.let { preferences.put("webDarkening", it) }
                p.desktopWidth?.let { preferences.put("desktopWidth", it) }
                // Explicit JSON null round-trips "follow the browser default"; absence
                // would silently keep the target device's override instead.
                when (val enhanced = p.enhancedPlayback) {
                    is BackupOptional.Present -> preferences.put(
                        "enhancedPlayback", enhanced.value ?: JSONObject.NULL)
                    BackupOptional.Absent -> Unit
                }
                when (val mirror = p.videoMirror) {
                    is BackupOptional.Present -> preferences.put(
                        "videoMirror", mirror.value ?: JSONObject.NULL)
                    BackupOptional.Absent -> Unit
                }
                when (val fit = p.videoFit) {
                    is BackupOptional.Present -> preferences.put("videoFit", fit.value ?: JSONObject.NULL)
                    BackupOptional.Absent -> Unit
                }
                array.put(JSONObject().put("origin", site.origin).put("preferences", preferences))
            }
            settings.put("sites", array)
        }

        backup.settings.bookmarks?.let { library ->
            val group = JSONObject()
            val folders = JSONArray()
            library.folders.forEach { path -> folders.put(jsonArrayOf(path)) }
            group.put("folders", folders)
            val entries = JSONArray()
            library.entries.forEach { entry ->
                val item = JSONObject().put("title", entry.title).put("url", entry.url)
                if (entry.folderPath.isNotEmpty()) item.put("folderPath", jsonArrayOf(entry.folderPath))
                entries.put(item)
            }
            group.put("entries", entries)
            settings.put("bookmarks", group)
        }

        backup.settings.history?.let { history ->
            val array = JSONArray()
            history.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("title", entry.title)
                        .put("url", entry.url)
                        .put("visitTime", entry.visitTime)
                        .put("visitCount", entry.visitCount),
                )
            }
            settings.put("history", array)
        }

        return root.toString(2)
    }

    private fun jsonArrayOf(values: List<String>): JSONArray = JSONArray().apply { values.forEach { put(it) } }

    fun decode(text: String): SettingsBackup {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw SettingsBackupException("Not a JSON object")
        if (root.optString("format") != SettingsBackup.FORMAT_ID) {
            throw SettingsBackupException("Unknown file format")
        }
        // Strict: a string "1" or a float 1.0 is a type error, not a schema version.
        val schema = if (root.has("schemaVersion")) int(root, "schemaVersion") else -1
        if (schema != SettingsBackup.SCHEMA_VERSION) {
            throw SettingsBackupException("Unsupported schema version $schema")
        }
        if (root.has("settings") && !root.isNull("settings") && root.opt("settings") !is JSONObject) {
            throw SettingsBackupException("settings must be an object")
        }
        val settingsObject = root.optJSONObject("settings") ?: throw SettingsBackupException("Missing settings")

        return SettingsBackup(
            format = SettingsBackup.FORMAT_ID,
            schemaVersion = schema,
            appVersion = boundedMetadata(root, "appVersion", MAX_APP_VERSION_CHARS),
            exportedAt = boundedMetadata(root, "exportedAt", MAX_EXPORTED_AT_CHARS),
            settings = BackupSettings(
                browser = strictGroup(settingsObject, "browser")?.let(::decodeBrowser),
                home = strictGroup(settingsObject, "home")?.let(::decodeHome),
                search = strictGroup(settingsObject, "search")?.let(::decodeSearch),
                downloads = strictGroup(settingsObject, "downloads")?.let(::decodeDownloads),
                filtering = strictGroup(settingsObject, "filtering")?.let(::decodeFiltering),
                sites = strictSites(settingsObject),
                bookmarks = strictGroup(settingsObject, "bookmarks")?.let(::decodeBookmarks),
                history = strictHistory(settingsObject),
            ),
        )
    }

    /**
     * Known group names are type-checked: `browser: false` is a corrupt file, not a
     * group that happens to be absent. Unknown fields stay compatibility-ignored.
     */
    private fun strictGroup(settings: JSONObject, name: String): JSONObject? {
        if (!settings.has(name) || settings.isNull(name)) return null
        return settings.optJSONObject(name)
            ?: throw SettingsBackupException("settings.$name must be an object")
    }

    private fun strictSites(settings: JSONObject): List<BackupSite>? {
        if (!settings.has("sites") || settings.isNull("sites")) return null
        return settings.optJSONArray("sites")?.let(::decodeSites)
            ?: throw SettingsBackupException("settings.sites must be an array")
    }

    private fun decodeBrowser(group: JSONObject): BackupBrowser {
        if (group.has("theme")) {
            val theme = string(group, "theme")
            if (theme !in setOf("SYSTEM", "LIGHT", "DARK")) {
                throw SettingsBackupException("Unknown theme: $theme")
            }
        }
        return BackupBrowser(
            theme = optionalString(group, "theme"),
            bottomAddressBar = optionalBoolean(group, "bottomAddressBar"),
            swipeTabs = optionalBoolean(group, "swipeTabs"),
            autoCheckUpdates = optionalBoolean(group, "autoCheckUpdates"),
            incognitoEnabled = optionalBoolean(group, "incognitoEnabled"),
            searchSuggestionsEnabled = optionalBoolean(group, "searchSuggestionsEnabled"),
            privateSearchSuggestionsEnabled = optionalBoolean(group, "privateSearchSuggestionsEnabled"),
            browserFullscreenEnabled = optionalBoolean(group, "browserFullscreenEnabled"),
            video = optionalObject(group, "video")?.let { video ->
                validateRate(video, "browser.video.boostRate")
                validateRate(video, "browser.video.preferredSpeed")
                BackupVideo(
                    automaticPip = optionalBoolean(video, "automaticPip"),
                    backgroundPlayback = optionalBoolean(video, "backgroundPlayback"),
                    enhancedControls = optionalBoolean(video, "enhancedControls"),
                    verticalGestures = optionalBoolean(video, "verticalGestures"),
                    horizontalSeek = optionalBoolean(video, "horizontalSeek"),
                    holdToBoost = optionalBoolean(video, "holdToBoost"),
                    boostRate = optionalDouble(video, "boostRate")?.toFloat(),
                    landscapeFullscreen = optionalBoolean(video, "landscapeFullscreen"),
                    rememberSpeed = optionalBoolean(video, "rememberSpeed"),
                    preferredSpeed = optionalDouble(video, "preferredSpeed")?.toFloat(),
                )
            },
        )
    }

    private fun decodeHome(group: JSONObject): BackupHome {
        if (group.has("mode")) {
            val mode = string(group, "mode")
            if (mode !in setOf("NAVIGATION", "FIXED_URL")) {
                throw SettingsBackupException("Unknown homepage mode: $mode")
            }
        }
        val home = BackupHome(
            mode = optionalString(group, "mode"),
            fixedUrl = optionalString(group, "fixedUrl"),
            restoreLastSession = optionalBoolean(group, "restoreLastSession"),
        )
        home.fixedUrl?.let {
            if (!com.mybrowser.core.UrlUtils.isHttpUrl(it)) {
                throw SettingsBackupException("Homepage URL is not HTTP(S)")
            }
        }
        return home
    }

    private fun decodeSearch(group: JSONObject): BackupSearch {
        var engines: List<BackupCustomEngine>? = null
        optionalArray(group, "customEngines")?.let { array ->
            if (array.length() > 12) throw SettingsBackupException("Too many custom search engines")
            engines = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index)
                    ?: throw SettingsBackupException("search.customEngines[$index] is not an object")
                val engine = BackupCustomEngine(
                    id = requiredString(item, "id"),
                    name = requiredString(item, "name"),
                    template = requiredString(item, "template"),
                    suggestUrlTemplate = optionalString(item, "suggestUrlTemplate"),
                )
                if (!Regex("custom_[A-Za-z0-9_]{1,60}").matches(engine.id)) {
                    throw SettingsBackupException("Invalid engine id: ${engine.id}")
                }
                if (engine.name.isEmpty() || engine.name.length > 64) {
                    throw SettingsBackupException("Engine name out of range")
                }
                if (engine.template.isEmpty() || engine.template.length > 2_048) {
                    throw SettingsBackupException("Engine template out of range")
                }
                if (!com.mybrowser.search.SearchSuggestionProvider.isValidSuggestTemplate(engine.suggestUrlTemplate)) {
                    throw SettingsBackupException("Invalid suggest URL template")
                }
                engine
            }
            val ids = engines!!.map { it.id }
            val names = engines!!.map { it.name.lowercase() }
            if (ids.size != ids.toSet().size || names.size != names.toSet().size) {
                throw SettingsBackupException("Duplicate engine id or name")
            }
        }
        return BackupSearch(
            currentEngineId = optionalString(group, "currentEngineId"),
            customEngines = engines,
        )
    }

    private fun decodeDownloads(group: JSONObject): BackupDownloads {
        var hint: String? = null
        if (group.has("directoryModeHint")) {
            hint = string(group, "directoryModeHint")
            if (hint !in setOf("SYSTEM_DOWNLOADS", "CUSTOM_DIRECTORY")) {
                throw SettingsBackupException("Unknown directory mode hint: $hint")
            }
        }
        val threads = optionalInt(group, "threadCount")
        if (threads != null && threads !in MIN_DOWNLOAD_THREADS..MAX_DOWNLOAD_THREADS) {
            throw SettingsBackupException("Download thread count out of range")
        }
        return BackupDownloads(
            threadCount = threads,
            unmeteredOnly = optionalBoolean(group, "unmeteredOnly"),
            directoryModeHint = hint,
        )
    }

    private fun decodeFiltering(group: JSONObject): BackupFiltering {
        var builtIns: List<BackupBuiltInSubscription>? = null
        optionalArray(group, "builtIns")?.let { array ->
            if (array.length() > MAX_BUILT_IN_ENTRIES) throw SettingsBackupException("Too many built-in subscriptions")
            builtIns = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index)
                    ?: throw SettingsBackupException("filtering.builtIns[$index] is not an object")
                val id = requiredString(item, "id")
                if (id.isBlank() || id.length > MAX_BUILT_IN_ID_CHARS) {
                    throw SettingsBackupException("Built-in subscription id out of range")
                }
                BackupBuiltInSubscription(
                    id = id,
                    enabled = requiredBoolean(item, "enabled"),
                )
            }
        }
        var customs: List<BackupCustomSubscription>? = null
        optionalArray(group, "customSubscriptions")?.let { array ->
            if (array.length() > 32) throw SettingsBackupException("Too many custom filter subscriptions")
            customs = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index)
                    ?: throw SettingsBackupException("filtering.customSubscriptions[$index] is not an object")
                val subscription = BackupCustomSubscription(
                    name = requiredString(item, "name"),
                    url = requiredString(item, "url"),
                    enabled = requiredBoolean(item, "enabled"),
                )
                if (subscription.name.isEmpty() || subscription.name.length > 128) {
                    throw SettingsBackupException("Subscription name out of range")
                }
                if (!com.mybrowser.core.TextDownloader.isHttpUrl(subscription.url.trim())) {
                    throw SettingsBackupException("Subscription URL is not HTTP(S): ${subscription.url}")
                }
                subscription
            }
            try {
                com.mybrowser.filter.FilterSubscriptions.validateCustomLists(customs!!.map { Triple(it.name, it.url, it.enabled) })
            } catch (error: IllegalArgumentException) {
                throw SettingsBackupException(error.message ?: "Invalid subscriptions")
            }
        }
        return BackupFiltering(
            enabled = optionalBoolean(group, "enabled"),
            autoUpdate = optionalBoolean(group, "autoUpdate"),
            builtIns = builtIns,
            customSubscriptions = customs,
        )
    }

    private fun decodeSites(array: JSONArray): List<BackupSite> {
        if (array.length() > SiteSettingsRepository.MAX_SITES) {
            throw SettingsBackupException("Too many site entries")
        }
        val sites = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw SettingsBackupException("sites[$index] is not an object")
            val origin = requiredString(item, "origin")
            if (com.mybrowser.site.SiteOrigin.of(origin) != origin) {
                throw SettingsBackupException("Invalid site origin: $origin")
            }
            val preferencesObject = item.optJSONObject("preferences")
                ?: throw SettingsBackupException("sites[$index].preferences is missing")
            val zoom = optionalInt(preferencesObject, "textZoom")
            if (zoom != null && zoom !in 50..200) {
                throw SettingsBackupException("Site text zoom out of range")
            }
            val width = optionalInt(preferencesObject, "desktopWidth")
            if (width != null && width !in SiteSettingsRepository.DESKTOP_WIDTHS) {
                throw SettingsBackupException("Site desktop width not supported: $width")
            }
            // An unknown preset is a file this build cannot honour: the names the player sends to
            // the page are the same ones the file carries, and the stored form has to keep them.
            val fit = if (!preferencesObject.has("videoFit")) BackupOptional.Absent
            else if (preferencesObject.isNull("videoFit")) BackupOptional.Present(null)
            else {
                val name = string(preferencesObject, "videoFit")
                if (VideoFit.entries.none { it.name == name }) {
                    throw SettingsBackupException("Site video fit not supported: $name")
                }
                BackupOptional.Present(name)
            }
            BackupSite(
                origin = origin,
                preferences = BackupSitePreferences(
                    filtering = optionalBoolean(preferencesObject, "filtering"),
                    javaScript = optionalBoolean(preferencesObject, "javaScript"),
                    images = optionalBoolean(preferencesObject, "images"),
                    thirdPartyCookies = optionalBoolean(preferencesObject, "thirdPartyCookies"),
                    desktop = optionalBoolean(preferencesObject, "desktop"),
                    textZoom = zoom,
                    webDarkening = optionalBoolean(preferencesObject, "webDarkening"),
                    desktopWidth = width,
                    enhancedPlayback = if (preferencesObject.has("enhancedPlayback")) {
                        BackupOptional.Present(
                            if (preferencesObject.isNull("enhancedPlayback")) null
                            else requiredBoolean(preferencesObject, "enhancedPlayback"))
                    } else BackupOptional.Absent,
                    videoMirror = if (preferencesObject.has("videoMirror")) {
                        BackupOptional.Present(
                            if (preferencesObject.isNull("videoMirror")) null
                            else requiredBoolean(preferencesObject, "videoMirror"))
                    } else BackupOptional.Absent,
                    videoFit = fit,
                ),
            )
        }
        val origins = sites.map { it.origin }
        if (origins.size != origins.toSet().size) {
            throw SettingsBackupException("Duplicate site origin")
        }
        return sites
    }

    private fun decodeBookmarks(group: JSONObject): BackupBookmarks {
        val folders = optionalArray(group, "folders")?.let { array ->
            if (array.length() > MAX_FOLDER_PATHS) throw SettingsBackupException("Too many bookmark folders")
            (0 until array.length()).map { index ->
                decodeFolderPath(array.opt(index), "bookmarks.folders[$index]")
            }
        }.orEmpty()
        val entries = optionalArray(group, "entries")?.let { array ->
            if (array.length() > MAX_BACKUP_BOOKMARKS) throw SettingsBackupException("Too many bookmarks")
            (0 until array.length()).map { index ->
                val item = array.optJSONObject(index)
                    ?: throw SettingsBackupException("bookmarks.entries[$index] is not an object")
                val url = libraryUrl(item, "bookmarks.entries[$index]")
                val title = optionalString(item, "title").orEmpty()
                if (title.length > MAX_LIBRARY_TITLE_CHARS) {
                    throw SettingsBackupException("bookmarks.entries[$index].title is too long")
                }
                BackupBookmark(
                    title = title,
                    url = url,
                    // A missing path means the bookmark bar, exactly as the store models it.
                    folderPath = optionalArray(item, "folderPath")
                        ?.let { decodeFolderPath(it, "bookmarks.entries[$index].folderPath") }
                        .orEmpty(),
                )
            }
        }.orEmpty()
        return BackupBookmarks(folders, entries)
    }

    private fun decodeFolderPath(value: Any?, label: String): List<String> {
        val array = value as? JSONArray ?: throw SettingsBackupException("$label must be an array")
        if (array.length() > MAX_FOLDER_PATH_DEPTH) throw SettingsBackupException("$label is nested too deeply")
        return (0 until array.length()).map { index ->
            val segment = array.opt(index) as? String
                ?: throw SettingsBackupException("$label[$index] must be a string")
            val name = segment.trim()
            if (name.isEmpty() || name.length > MAX_FOLDER_PATH_SEGMENT_CHARS) {
                throw SettingsBackupException("$label[$index] is out of range")
            }
            name
        }
    }

    private fun strictHistory(settings: JSONObject): List<BackupHistoryEntry>? {
        if (!settings.has("history") || settings.isNull("history")) return null
        val array = settings.optJSONArray("history")
            ?: throw SettingsBackupException("settings.history must be an array")
        if (array.length() > MAX_BACKUP_HISTORY) throw SettingsBackupException("Too many history entries")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw SettingsBackupException("history[$index] is not an object")
            val url = libraryUrl(item, "history[$index]")
            val title = optionalString(item, "title").orEmpty()
            if (title.length > MAX_LIBRARY_TITLE_CHARS) {
                throw SettingsBackupException("history[$index].title is too long")
            }
            val visitTime = requiredLong(item, "visitTime")
            if (visitTime <= 0 || visitTime > MAX_VISIT_TIME_MILLIS) {
                throw SettingsBackupException("history[$index].visitTime is out of range")
            }
            // Absent or null means "a single visit"; only the store's own bound is enforced.
            val visitCount = if (item.has("visitCount") && !item.isNull("visitCount")) {
                int(item, "visitCount")
            } else 1
            if (visitCount !in 1..ImportedHistory.MAX_VISIT_COUNT) {
                throw SettingsBackupException("history[$index].visitCount is out of range")
            }
            BackupHistoryEntry(title = title, url = url, visitTime = visitTime, visitCount = visitCount)
        }
    }

    /** Library rows land in stores that accept HTTP(S) only; reject them here, not mid-write. */
    private fun libraryUrl(item: JSONObject, label: String): String {
        val url = requiredString(item, "url")
        if (url.length > MAX_LIBRARY_URL_CHARS || !com.mybrowser.core.UrlUtils.isHttpUrl(url)) {
            throw SettingsBackupException("$label.url is not an HTTP(S) address")
        }
        return url
    }

    private fun validateRate(group: JSONObject, field: String) {
        if (!group.has(field.substringAfterLast('.'))) return
        val value = optionalDouble(group, field.substringAfterLast('.')) ?: return
        if (PlaybackSpeed.normalizeSelection(value.toFloat()) == null) {
            throw SettingsBackupException("Playback rate out of range: $value")
        }
    }

    // --- Strict, null-preserving accessors: absent = keep current, wrong type = reject. ---

    private fun boundedMetadata(root: JSONObject, key: String, limit: Int): String {
        val value = optionalString(root, key).orEmpty()
        if (value.length > limit) throw SettingsBackupException("$key is too long")
        return value
    }

    private fun optionalObject(group: JSONObject, key: String): JSONObject? =
        if (!group.has(key) || group.isNull(key)) null else group.optJSONObject(key)
            ?: throw SettingsBackupException("$key must be an object")

    private fun optionalArray(group: JSONObject, key: String): JSONArray? =
        if (!group.has(key) || group.isNull(key)) null else group.optJSONArray(key)
            ?: throw SettingsBackupException("$key must be an array")

    private fun optionalString(group: JSONObject, key: String): String? =
        if (!group.has(key) || group.isNull(key)) null else string(group, key)

    private fun optionalBoolean(group: JSONObject, key: String): Boolean? =
        if (!group.has(key) || group.isNull(key)) null else requiredBoolean(group, key)

    private fun optionalInt(group: JSONObject, key: String): Int? =
        if (!group.has(key) || group.isNull(key)) null else int(group, key)

    private fun optionalDouble(group: JSONObject, key: String): Double? =
        if (!group.has(key) || group.isNull(key)) null else double(group, key)

    private fun string(group: JSONObject, key: String): String {
        val value = group.opt(key)
        return value as? String ?: throw SettingsBackupException("$key must be a string")
    }

    private fun requiredString(group: JSONObject, key: String): String {
        if (!group.has(key) || group.isNull(key)) throw SettingsBackupException("$key is missing")
        return string(group, key)
    }

    private fun requiredBoolean(group: JSONObject, key: String): Boolean {
        val value = group.opt(key)
        return value as? Boolean ?: throw SettingsBackupException("$key must be a boolean")
    }

    private fun int(group: JSONObject, key: String): Int {
        val value = group.opt(key)
        return (value as? Number)?.toInt()?.takeIf { (value as Number).toDouble() == it.toDouble() }
            ?: throw SettingsBackupException("$key must be an integer")
    }

    private fun requiredLong(group: JSONObject, key: String): Long {
        if (!group.has(key) || group.isNull(key)) throw SettingsBackupException("$key is missing")
        val value = group.opt(key)
        return (value as? Number)?.toLong()?.takeIf { (value as Number).toDouble() == it.toDouble() }
            ?: throw SettingsBackupException("$key must be an integer")
    }

    private fun double(group: JSONObject, key: String): Double {
        val value = group.opt(key)
        return (value as? Number)?.toDouble() ?: throw SettingsBackupException("$key must be a number")
    }
}
