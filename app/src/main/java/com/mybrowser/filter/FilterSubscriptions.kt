package com.mybrowser.filter

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
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
 * Process-scoped subscriptions. Immutable content files + an atomic manifest ensure an
 * interrupted or invalid update cannot discard the last working list.
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
    private val manifest = AtomicFile(File(directory, "subscriptions.json"))
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
            manifest.openRead().use { JSONArray(TextDownloader.readText(it, 256 * 1024)) }
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

    /** Updates enabled subscriptions by default; a row's explicit update may target a disabled one. */
    suspend fun update(id: String? = null): Boolean = mutate {
        var allSucceeded = true
        val targets = _subscriptions.value.filter { if (id == null) it.enabled else it.id == id }
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

    private fun persist(lists: List<Subscription>) {
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().put("id", list.id).put("name", list.name).put("url", list.url)
                .put("enabled", list.enabled).put("file", list.file).put("updatedAt", list.updatedAt)
                .put("checkedAt", list.checkedAt).put("etag", list.etag).put("modified", list.modified))
        }
        manifest.writeUtf8(array.toString())
    }

    private fun cleanup() {
        val keep = _subscriptions.value.mapNotNull { it.file }.toSet()
        directory.listFiles()?.filter { SNAPSHOT.matches(it.name) && it.name !in keep }?.forEach { it.delete() }
    }

    companion object {
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
