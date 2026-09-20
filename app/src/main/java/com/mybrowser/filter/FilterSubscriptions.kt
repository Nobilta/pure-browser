package com.mybrowser.filter

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import com.mybrowser.data.commitConfirmed
import com.mybrowser.R
import com.mybrowser.core.TextDownloader
import com.mybrowser.core.writeUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Process-scoped subscriptions. Immutable content files plus a confirmed preference-file
 * manifest preserve the last working list. The manifest shares storage with the global
 * switches so settings imports can commit the entire filtering group in one edit.
 */
class FilterSubscriptions(
    context: Context,
    private val filter: FilterController? = null,
    private val builtIns: List<Source> = BUILT_INS,
    private val fetch: suspend (String, String?, String?) -> TextDownloader.Response = { url, etag, modified ->
        TextDownloader().get(url, FilterListFormat.MAX_BYTES, etag, modified)
    },
) {
    data class Source(val id: String, val name: String, val url: String, val asset: String)
    data class Subscription(
        val id: String,
        val name: String,
        val url: String,
        val builtIn: Boolean = false,
        val enabled: Boolean = true,
        val ruleCount: Int = 0,
        val bytes: Int = 0,
        val file: String? = null,
        val updatedAt: Long = 0,
        val checkedAt: Long = 0,
        val etag: String? = null,
        val modified: String? = null,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "filter_subscriptions")
    private val legacyManifest = AtomicFile(File(directory, "subscriptions.json"))
    private val prefs = appContext.getSharedPreferences("filter_settings", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var initialized = false
    private val _subscriptions = MutableStateFlow(builtIns.map { Subscription(it.id, it.name, it.url, builtIn = true) })
    private val _busy = MutableStateFlow(false)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _autoUpdate = MutableStateFlow(prefs.getBoolean("auto_update", true))
    val subscriptions = _subscriptions.asStateFlow()
    val busy = _busy.asStateFlow()
    val lastError = _lastError.asStateFlow()
    val autoUpdate = _autoUpdate.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked() }
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        directory.mkdirs()
        val saved = runCatching {
            val text = prefs.getString(MANIFEST_KEY, null)
                ?: legacyManifest.openRead().use { TextDownloader.readText(it, 256 * 1024) }
            require(text.length <= 256 * 1024)
            JSONArray(text)
        }.getOrNull()
        val records = mutableMapOf<String, JSONObject>()
        if (saved != null) for (i in 0 until minOf(saved.length(), MAX_CUSTOM_LISTS + builtIns.size)) {
            saved.optJSONObject(i)?.let { records[it.optString("id")] = it }
        }
        val loaded = builtIns.map { source ->
            decode(records.remove(source.id), Subscription(source.id, source.name, source.url, builtIn = true))
        }.toMutableList()
        records.values.take(MAX_CUSTOM_LISTS).forEach { value ->
            val id = value.optString("id")
            val url = value.optString("url")
            val name = value.optString("name")
            if (ID.matches(id) && TextDownloader.isHttpUrl(url) && name.isNotBlank() && name.length <= 128) {
                loaded += decode(value, Subscription(id, name, url))
            }
        }
        if (saved == null) migrateLegacy(loaded)
        _subscriptions.value = loaded.map { savedList ->
            val list = if (savedList.file != null && readSavedPayload(savedList) == null) {
                savedList.copy(file = null, etag = null, modified = null, updatedAt = 0, checkedAt = 0)
            } else savedList
            val payload = readPayload(list)
            if (payload == null) list.copy(file = null, ruleCount = 0, bytes = 0, etag = null, modified = null,
                error = appContext.getString(R.string.filter_missing_snapshot))
            else list.copy(ruleCount = FilterListFormat.validate(payload), bytes = payload.toByteArray().size)
        }
        initialized = true
        rebuild()
    }

    private fun decode(value: JSONObject?, defaults: Subscription): Subscription {
        if (value == null) return defaults
        val filename = value.optString("file").takeIf { SNAPSHOT.matches(it) && File(directory, it).isFile }
        return defaults.copy(enabled = value.optBoolean("enabled", true), file = filename,
            updatedAt = value.optLong("updatedAt"), checkedAt = value.optLong("checkedAt"),
            etag = value.optString("etag").takeIf { filename != null && it.isNotBlank() },
            modified = value.optString("modified").takeIf { filename != null && it.isNotBlank() })
    }

    private fun migrateLegacy(loaded: MutableList<Subscription>) {
        val raw = appContext.getSharedPreferences("custom_filters", Context.MODE_PRIVATE).getString("lists", null) ?: return
        if (raw.length > 256 * 1024) return
        runCatching {
            val values = JSONArray(raw)
            for (i in 0 until minOf(values.length(), MAX_CUSTOM_LISTS)) {
                val item = values.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name").take(128)
                val url = item.optString("url")
                if (!ID.matches(id) || name.isBlank() || !TextDownloader.isHttpUrl(url)) continue
                var entry = Subscription(id, name, url)
                val old = File(appContext.cacheDir, "filter_lists/" + id + ".txt")
                runCatching {
                    val text = old.inputStream().use { TextDownloader.readText(it, FilterListFormat.MAX_BYTES) }
                    entry = stage(entry, text)
                }
                loaded += entry
            }
            persist(loaded)
        }
    }

    fun setAutoUpdate(value: Boolean) {
        prefs.edit { putBoolean("auto_update", value) }
        _autoUpdate.value = value
        FilterUpdateJob.schedule(appContext, value)
    }

    suspend fun setEnabled(id: String, value: Boolean): Boolean = mutate {
        val next = _subscriptions.value.map { if (it.id == id) it.copy(enabled = value) else it }
        persist(next)
        _subscriptions.value = next
        true
    }

    suspend fun remove(id: String): Boolean = mutate {
        val next = _subscriptions.value.filterNot { it.id == id && !it.builtIn }
        persist(next)
        _subscriptions.value = next
        cleanup()
        true
    }

    suspend fun add(name: String, url: String): Boolean = mutate {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 128 && TextDownloader.isHttpUrl(cleanUrl))
        require(_subscriptions.value.none { it.url == cleanUrl }) { "Already subscribed" }
        require(_subscriptions.value.count { !it.builtIn } < MAX_CUSTOM_LISTS) { "Subscription limit reached" }
        val response = fetch(cleanUrl, null, null)
        val list = stage(Subscription(TextDownloader.sha256(cleanUrl).take(16), cleanName, cleanUrl),
            response.text ?: throw IOException("Empty response")).copy(etag = response.etag, modified = response.lastModified)
        val next = _subscriptions.value + list
        checkSize(next)
        persist(next)
        _subscriptions.value = next
        cleanup()
        true
    }

    /** Result of a config-only import: success plus how many lists await their first download. */
    data class ImportOutcome(val ok: Boolean, val pendingUpdates: Int)

    /**
     * Applies subscription configuration from a settings import. This is config-only:
     * no network requests. Custom lists with an existing local snapshot (same URL) keep
     * their cached rules; new ones start without a payload and show up as pending an
     * update. Unknown built-in ids must be filtered out by the caller.
     *
     * A null [customLists] means the file said nothing about custom subscriptions and
     * the current ones are kept untouched; an explicit (possibly empty) list replaces
     * them wholesale, so "absent" and "cleared" stay distinguishable.
     */
    suspend fun importConfiguration(
        builtInStates: Map<String, Boolean>,
        customLists: List<Triple<String, String, Boolean>>?,
        enabled: Boolean? = null,
        autoUpdate: Boolean? = null,
    ): ImportOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            _busy.value = true
            _lastError.value = null
            try {
                customLists?.let { validateCustomLists(it, builtIns.map { source -> source.url }.toSet()) }
                initializeLocked()
                val current = _subscriptions.value
                var next = current.map { subscription ->
                    if (subscription.builtIn && subscription.id in builtInStates) {
                        subscription.copy(enabled = builtInStates.getValue(subscription.id))
                    } else {
                        subscription
                    }
                }
                if (customLists != null) {
                    next = next.filter { it.builtIn } // custom lists are replaced wholesale
                    require(customLists.size <= MAX_CUSTOM_LISTS) { "Subscription limit reached" }
                    for ((name, url, enabled) in customLists) {
                        val cleanName = name.trim()
                        val cleanUrl = url.trim()
                        require(cleanName.isNotEmpty() && cleanName.length <= 128 && TextDownloader.isHttpUrl(cleanUrl))
                        // Reuse a cached payload when the same URL was already subscribed here.
                        val cached = current.firstOrNull { !it.builtIn && it.url == cleanUrl }
                        next += (cached?.copy(name = cleanName, enabled = enabled)
                            ?: Subscription(TextDownloader.sha256(cleanUrl).take(16), cleanName, cleanUrl, enabled = enabled))
                    }
                }
                checkSize(next)
                persist(next, enabled, autoUpdate)
                _subscriptions.value = next
                filter?.reloadEnabledPreference()
                _autoUpdate.value = prefs.getBoolean("auto_update", true)
                // Scheduling is derived from the durable preference and retried at app
                // startup. A scheduler failure cannot undo a successful configuration commit.
                runCatching { FilterUpdateJob.schedule(appContext, _autoUpdate.value) }
                cleanup()
                ImportOutcome(ok = true, pendingUpdates = next.count { !it.builtIn && it.enabled && it.file == null })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _lastError.value = appContext.getString(R.string.filter_operation_failed)
                ImportOutcome(ok = false, pendingUpdates = 0)
            } finally {
                try {
                    // A committed snapshot must reach the engine even when its UI closes.
                    withContext(NonCancellable) { if (initialized) rebuild() }
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /** Updates enabled subscriptions by default; a row's explicit update may target a disabled one. */
    suspend fun update(id: String? = null): Boolean = updateMatching { if (id == null) it.enabled else it.id == id }

    /** First payloads for imported enabled custom lists; no unrelated refreshes. */
    suspend fun updateMissing(): Boolean = updateMatching { !it.builtIn && it.enabled && it.file == null }

    private suspend fun updateMatching(matches: (Subscription) -> Boolean): Boolean = mutate {
        var allSucceeded = true
        val targets = _subscriptions.value.filter(matches)
        for (target in targets) {
            try {
                val hasSnapshot = target.file != null && readSavedPayload(target) != null
                val response = fetch(target.url, if (hasSnapshot) target.etag else null, if (hasSnapshot) target.modified else null)
                if (!hasSnapshot && response.text == null) throw IOException("No snapshot for HTTP 304")
                val nextList = (if (response.text == null) target.copy(checkedAt = System.currentTimeMillis())
                    else stage(target, response.text)).copy(
                    etag = response.etag ?: target.etag, modified = response.lastModified ?: target.modified, error = null)
                val next = _subscriptions.value.map { if (it.id == target.id) nextList else it }
                checkSize(next)
                persist(next)
                _subscriptions.value = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                allSucceeded = false
                _subscriptions.value = _subscriptions.value.map {
                    if (it.id == target.id) it.copy(error = appContext.getString(R.string.filter_update_failed)) else it
                }
            }
        }
        cleanup()
        allSucceeded
    }

    private suspend fun mutate(block: suspend () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            _busy.value = true
            _lastError.value = null
            try {
                initializeLocked()
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _lastError.value = appContext.getString(R.string.filter_operation_failed)
                false
            } finally {
                try {
                    // A committed snapshot must reach the engine even when its UI closes.
                    // Keep the progress state until the native/CSS snapshot is active.
                    withContext(NonCancellable) { if (initialized) rebuild() }
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    private fun checkSize(lists: List<Subscription>) {
        if (lists.sumOf { it.bytes.toLong() } > FilterListFormat.MAX_TOTAL_BYTES) throw IOException("Filter storage limit reached")
    }

    private fun stage(list: Subscription, text: String): Subscription {
        val count = FilterListFormat.validate(text)
        val filename = list.id + "-" + TextDownloader.sha256(text) + ".txt"
        val atomic = AtomicFile(File(directory, filename))
        if (!atomic.baseFile.isFile) atomic.writeUtf8(text)
        val now = System.currentTimeMillis()
        return list.copy(file = filename, bytes = text.toByteArray().size, ruleCount = count,
            updatedAt = if (list.file == filename && list.updatedAt != 0L) list.updatedAt else now, checkedAt = now, error = null)
    }

    private fun readPayload(list: Subscription): String? {
        readSavedPayload(list)?.let { return it }
        val source = builtIns.find { it.id == list.id } ?: return null
        return runCatching {
            appContext.assets.open(source.asset).use { TextDownloader.readText(it, FilterListFormat.MAX_BYTES) }
                .also { FilterListFormat.validate(it) }
        }.getOrNull()
    }

    private fun readSavedPayload(list: Subscription): String? =
        list.file?.takeIf(SNAPSHOT::matches)?.let { name ->
            runCatching {
                File(directory, name).inputStream().use { TextDownloader.readText(it, FilterListFormat.MAX_BYTES) }
                    .also { FilterListFormat.validate(it) }
                    .takeIf { name == list.id + "-" + TextDownloader.sha256(it) + ".txt" }
            }.getOrNull()
        }

    private suspend fun rebuild() {
        val enabled = _subscriptions.value.filter { it.enabled }.mapNotNull { list -> readPayload(list)?.let { list.name to it } }
        filter?.replaceLists(enabled.map { it.second }, enabled.map { it.first })?.join()
    }

    private fun persist(lists: List<Subscription>, enabled: Boolean? = null, autoUpdate: Boolean? = null) {
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().put("id", list.id).put("name", list.name).put("url", list.url)
                .put("enabled", list.enabled).put("file", list.file).put("updatedAt", list.updatedAt)
                .put("checkedAt", list.checkedAt).put("etag", list.etag).put("modified", list.modified))
        }
        val text = array.toString()
        require(text.length <= 256 * 1024) { "Subscription manifest too large" }
        prefs.commitConfirmed(buildMap {
            put(MANIFEST_KEY, text)
            enabled?.let { put("enabled", it) }
            autoUpdate?.let { put("auto_update", it) }
        })
        // The old AtomicFile is read only until the first successful migration/write.
        // Its payload filenames and HTTP validators are preserved verbatim in the JSON.
        legacyManifest.delete()
    }

    private fun cleanup() {
        val keep = _subscriptions.value.mapNotNull { it.file }.toSet()
        directory.listFiles()?.filter { SNAPSHOT.matches(it.name) && it.name !in keep }?.forEach { it.delete() }
    }

    companion object {
        /** Shared by decode, whole-file preflight and the repository write boundary. */
        fun validateCustomLists(lists: List<Triple<String, String, Boolean>>, builtInUrls: Set<String> = BUILT_INS.map { it.url }.toSet()) {
            require(lists.size <= MAX_CUSTOM_LISTS) { "Subscription limit reached" }
            val urls = lists.map { it.second.trim() }
            require(urls.toSet().size == urls.size) { "Duplicate subscription URL" }
            require(urls.none { it in builtInUrls }) { "Custom subscription duplicates a built-in list" }
            lists.forEach { (name, url, _) ->
                require(name.trim().length in 1..128) { "Subscription name out of range" }
                require(TextDownloader.isHttpUrl(url.trim())) { "Invalid subscription URL" }
            }
        }

        private const val MANIFEST_KEY = "subscriptions_manifest"
        const val MAX_CUSTOM_LISTS = 32
        private val ID = Regex("[a-f0-9]{16}")
        private val SNAPSHOT = Regex("[a-z0-9-]+-[a-f0-9]{64}\\.txt")
        val BUILT_INS = listOf(
            Source("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt", "filters/easylist.txt"),
            Source("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "filters/easyprivacy.txt"),
            Source("easylist-china", "EasyList China", "https://easylist-downloads.adblockplus.org/easylistchina.txt", "filters/easylist-china.txt"),
        )
    }
}
