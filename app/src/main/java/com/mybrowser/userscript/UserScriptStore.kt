package com.mybrowser.userscript

import android.content.Context
import android.util.AtomicFile
import com.mybrowser.core.TextDownloader
import com.mybrowser.core.writeUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Explicitly installed scripts only; opening a web page never installs executable code. */
class UserScriptStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "userscripts")
    private val manifest = AtomicFile(File(directory, "scripts.json"))
    private val mutex = Mutex()
    private val _scripts = MutableStateFlow<List<InstalledUserScript>>(emptyList())
    val scripts = _scripts.asStateFlow()
    @Volatile var initialized = false
        private set
    var loadFailed = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked() }
    }

    private fun initializeLocked() {
        if (initialized) return
        directory.mkdirs()
        if (manifest.baseFile.exists() || File(directory, "scripts.json.bak").exists()) {
            try {
                val json = manifest.openRead().use { JSONArray(TextDownloader.readText(it, MAX_TOTAL_BYTES * 2)) }
                require(json.length() <= MAX_SCRIPTS)
                val loaded = mutableListOf<InstalledUserScript>()
                for (i in 0 until json.length()) {
                    val entry = json.getJSONObject(i)
                    val source = entry.getString("source")
                    val metadata = UserScriptMetadata.parse(source)
                    val requires = entry.optJSONArray("requires") ?: JSONArray()
                    require(requires.length() <= 8)
                    val code = (0 until requires.length()).map { requires.getString(it) }
                    val values = runCatching {
                        valuesFile(metadata.id).openRead().use { TextDownloader.readText(it, MAX_VALUES_BYTES) }
                            .also { JSONObject(it) }
                    }.getOrDefault("{}")
                    loaded += InstalledUserScript(metadata, source,
                        entry.optString("url").takeIf { TextDownloader.isHttpUrl(it) }, code,
                        entry.optBoolean("enabled") && metadata.supported && code.size == metadata.requires.size, values)
                }
                require(loaded.map { it.metadata.id }.distinct().size == loaded.size)
                checkSize(loaded)
                _scripts.value = loaded
            } catch (_: Exception) {
                // Preserve the original file for recovery; do not replace unreadable scripts.
                loadFailed = true
            }
        }
        initialized = true
    }

    suspend fun install(source: String, sourceUrl: String?): InstalledUserScript = withContext(Dispatchers.IO) {
        val metadata = UserScriptMetadata.parse(source)
        // Dependencies are fetched only after the user accepts the installation preview.
        val dependencies = if (metadata.supported) metadata.requires.map {
            TextDownloader().get(it, MAX_REQUIRE_BYTES).text ?: throw IOException("Empty dependency")
        } else emptyList()
        mutex.withLock {
            initializeLocked()
            check(!loadFailed) { "Saved scripts could not be read" }
            val old = _scripts.value.find { it.metadata.id == metadata.id }
            require(old != null || _scripts.value.size < MAX_SCRIPTS) { "Script limit reached" }
            val script = InstalledUserScript(metadata, source,
                sourceUrl?.takeIf(TextDownloader::isHttpUrl), dependencies,
                enabled = metadata.supported && (old?.enabled ?: true), values = old?.values ?: "{}")
            val next = if (old == null) _scripts.value + script else _scripts.value.map {
                if (it.metadata.id == metadata.id) script else it
            }
            persist(next)
            _scripts.value = next
            script
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            val next = _scripts.value.map {
                if (it.metadata.id == id) it.copy(enabled = enabled && it.metadata.supported &&
                    it.requiredCode.size == it.metadata.requires.size) else it
            }
            persist(next)
            _scripts.value = next
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            val next = _scripts.value.filterNot { it.metadata.id == id }
            persist(next)
            _scripts.value = next
            if (Regex("[a-f0-9]{32}").matches(id)) valuesFile(id).delete()
        }
    }

    /** Native side checks the current grant again, including after a script is disabled. */
    suspend fun changeValue(id: String, operation: String, key: String, value: Any?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val script = _scripts.value.find { it.metadata.id == id && it.enabled } ?: return@withLock false
            val grant = when (operation) { "set" -> "GM_setValue"; "delete" -> "GM_deleteValue"; else -> return@withLock false }
            if (!script.metadata.grants(grant) || key.length > 256 || key.contains('\u0000')) return@withLock false
            val values = JSONObject(script.values)
            if (operation == "delete") values.remove(key) else values.put(key, value ?: JSONObject.NULL)
            if (values.length() > 256) return@withLock false
            val text = values.toString()
            if (text.toByteArray().size > MAX_VALUES_BYTES) return@withLock false
            valuesFile(id).writeUtf8(text)
            _scripts.value = _scripts.value.map { if (it.metadata.id == id) it.copy(values = text) else it }
            true
        }
    }

    private fun valuesFile(id: String) = AtomicFile(File(directory, id + ".values.json"))

    private fun persist(scripts: List<InstalledUserScript>) {
        check(!loadFailed) { "Saved scripts could not be read" }
        checkSize(scripts)
        val json = JSONArray()
        scripts.forEach { script ->
            json.put(JSONObject().put("source", script.source).put("url", script.sourceUrl)
                .put("requires", JSONArray(script.requiredCode)).put("enabled", script.enabled))
        }
        manifest.writeUtf8(json.toString())
    }

    private fun checkSize(scripts: List<InstalledUserScript>) {
        val size = scripts.sumOf { script ->
            require(script.requiredCode.all { it.toByteArray().size <= MAX_REQUIRE_BYTES })
            script.source.toByteArray().size.toLong() + script.requiredCode.sumOf { it.toByteArray().size.toLong() }
        }
        require(size <= MAX_TOTAL_BYTES) { "Script storage limit reached" }
    }

    companion object {
        const val MAX_SCRIPTS = 24
        const val MAX_TOTAL_BYTES = 12 * 1024 * 1024
        const val MAX_REQUIRE_BYTES = 512 * 1024
        const val MAX_VALUES_BYTES = 64 * 1024
    }
}
