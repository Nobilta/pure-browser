package com.mybrowser.filter

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Persists user-supplied filter lists and feeds them into the live FilterController. */
class CustomFilterController(
    context: Context,
    private val filterController: FilterController? = null,
) {

    data class CustomList(
        val id: String,
        val name: String,
        val url: String,
        val ruleCount: Int,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheDir = File(appContext.cacheDir, "filter_lists").apply { mkdirs() }
    private val _customLists = MutableStateFlow<List<CustomList>>(emptyList())
    private val _ruleCount = MutableStateFlow(0)
    private val _lastError = MutableStateFlow<String?>(null)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()
    private val closed = AtomicBoolean(false)

    val customLists: StateFlow<List<CustomList>> = _customLists.asStateFlow()
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        // Cache files and the initial native rebuild are disk/CPU work. Do not perform
        // either on Activity.onCreate, where StrictMode and first-frame latency expose it.
        ioScope.launch { loadSavedLists() }
    }

    suspend fun addCustomList(name: String, url: String): Boolean = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext false
        mutationMutex.withLock {
            _lastError.value = null
            val cleanName = name.trim()
            val cleanUrl = url.trim()
            if (cleanName.isEmpty()) return@withLock fail("名称不能为空")
            if (cleanName.length > MAX_NAME_LENGTH) return@withLock fail("名称过长")
            if (cleanUrl.length > MAX_URL_LENGTH) return@withLock fail("规则列表地址过长")
            if (!isValidListUrl(cleanUrl)) {
                return@withLock fail("规则列表必须使用 HTTP 或 HTTPS")
            }

            val id = sha256(cleanUrl).take(16)
            if (_customLists.value.none { it.id == id } &&
                _customLists.value.size >= MAX_CUSTOM_LISTS
            ) {
                return@withLock fail("自定义规则列表已达到上限")
            }

            val rules = downloadRules(cleanUrl) ?: return@withLock fail("无法下载规则列表")
            runCatching { File(cacheDir, "$id.txt").writeText(rules) }
                .getOrElse { return@withLock fail("无法保存规则列表") }

            val entry = CustomList(id, cleanName, cleanUrl, countRules(rules))
            _customLists.value = (_customLists.value.filterNot { it.id == id } + entry)
                .sortedBy { it.name.lowercase() }
            persistMetadata()
            rebuildEngine()
            true
        }
    }

    fun removeCustomList(id: String) {
        if (closed.get()) return
        ioScope.launch {
            mutationMutex.withLock {
                val removed = _customLists.value.any { it.id == id }
                if (!removed) return@withLock
                File(cacheDir, "$id.txt").delete()
                _customLists.value = _customLists.value.filterNot { it.id == id }
                persistMetadata()
                rebuildEngine()
            }
        }
    }

    fun reload() {
        if (closed.get()) return
        ioScope.launch {
            mutationMutex.withLock { rebuildEngine() }
        }
    }

    private suspend fun loadSavedLists() {
        if (closed.get()) return
        mutationMutex.withLock {
            val raw = prefs.getString(KEY_LISTS, null) ?: return@withLock
            // SharedPreferences is normally private, but a corrupted/partially migrated
            // value must not make JSONArray parse an unbounded blob on the IO thread.  Keep
            // the persisted envelope bounded just like the downloaded rule payloads.
            if (raw.length > MAX_PERSISTED_JSON_LENGTH) {
                prefs.edit { remove(KEY_LISTS) }
                _customLists.value = emptyList()
                rebuildEngine()
                return@withLock
            }
            val loaded = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until minOf(array.length(), MAX_CUSTOM_LISTS)) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val name = item.optString("name").trim().take(MAX_NAME_LENGTH)
                        val url = item.optString("url").trim()
                        val file = File(cacheDir, "$id.txt")
                        if (ID_PATTERN.matches(id) && name.isNotBlank() &&
                            url.length <= MAX_URL_LENGTH && isValidListUrl(url) && file.isFile
                        ) {
                            val rules = readBounded(file) ?: continue
                            add(CustomList(id, name, url, countRules(rules)))
                        }
                    }
                }
            }.getOrDefault(emptyList())
            _customLists.value = loaded
            rebuildEngine()
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            // The process-wide filter must not keep applying a list after its owner Activity
            // is gone. The next Activity will load the persisted lists again.
            filterController?.setCustomRules(emptyList())
            ioScope.cancel()
        }
    }

    private fun persistMetadata() {
        val array = JSONArray()
        _customLists.value.take(MAX_CUSTOM_LISTS).forEach { list ->
            array.put(
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("url", list.url),
            )
            // Keep the JSON valid while enforcing the same bound on writes.  Truncating the
            // final string would create malformed preferences that fail on every launch.
            if (array.toString().length > MAX_PERSISTED_JSON_LENGTH) {
                array.remove(array.length() - 1)
                return@forEach
            }
        }
        prefs.edit { putString(KEY_LISTS, array.toString()) }
    }

    private fun rebuildEngine() {
        val payloads = _customLists.value.mapNotNull { list ->
            runCatching { File(cacheDir, "${list.id}.txt").takeIf(File::isFile)?.let(::readBounded) }
                .getOrNull()
        }
        _ruleCount.value = payloads.sumOf(::countRules)
        filterController?.setCustomRules(payloads)
    }

    private fun downloadRules(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PureBrowser/1.0")
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RULE_BYTES) return@runCatching null
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun countRules(text: String): Int = text.lineSequence().count { line ->
        val value = line.trim()
        value.isNotEmpty() && !value.startsWith('!') && !value.startsWith('[')
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }

    private fun isValidListUrl(raw: String): Boolean = runCatching {
        val parsed = URL(raw)
        (parsed.protocol.equals("http", ignoreCase = true) ||
            parsed.protocol.equals("https", ignoreCase = true)) &&
            parsed.host.isNotBlank() && raw.none { it.isWhitespace() || it.isISOControl() }
    }.getOrDefault(false)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "custom_filters"
        const val KEY_LISTS = "lists"
        const val MAX_RULE_BYTES = 8 * 1024 * 1024
        const val MAX_NAME_LENGTH = 128
        const val MAX_URL_LENGTH = 2_048
        const val MAX_CUSTOM_LISTS = 32
        // Enough for 32 maximally sized entries plus JSON escaping overhead, while still
        // small enough to reject a corrupt preference before parsing.
        const val MAX_PERSISTED_JSON_LENGTH = 256 * 1024
        val ID_PATTERN = Regex("[a-f0-9]{16}")

        /** Reads a cached list with the same bound used for network downloads. */
        fun readBounded(file: File): String? = runCatching {
            file.inputStream().use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RULE_BYTES) return@runCatching null
                    out.write(buffer, 0, read)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }
}
