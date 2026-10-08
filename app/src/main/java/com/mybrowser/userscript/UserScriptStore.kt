package com.mybrowser.userscript

import android.content.Context
import android.util.AtomicFile
import com.mybrowser.core.TextDownloader
import com.mybrowser.core.writeUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * GM values written during a private session, held here and nowhere else.
     *
     * A private session wipes its WebView profile and its cookies on exit; a script's values are
     * the one thing in that session this app would otherwise write to disk under a name that
     * outlives it. They are keyed by script id in memory, so two private tabs of the same script
     * see the same values while the session lasts, and nothing survives it.
     */
    private val privateValues = mutableMapOf<String, String>()

    /**
     * A nonce that advances whenever a script's injected program would differ.
     *
     * A runtime watches this alongside the script list: private GM values are not part of any
     * [InstalledUserScript], so a private write changes what a script is injected with while every
     * script stays equal, and a list-keyed observer would never be woken at all. The runtime then
     * compares the values themselves per script, so the wake-up costs one script's rebuild rather
     * than every installed program.
     */
    private val programRevision = MutableStateFlow(0L)
    val programs: StateFlow<Long> = programRevision.asStateFlow()

    /** Raises [programs]; callers that mutate values reach the runtime through this. */
    fun notifyChanged() {
        programRevision.value = programRevision.value + 1
    }

    /**
     * A private session's values were just discarded; the next one must not inherit them.
     *
     * [notifyChanged] is the same signal a saved-script edit raises, so a runtime that is watching
     * the store rebuilds what it injects. Without it a private write would be invisible to the
     * runtime, which caches the program per script and would keep handing the next document the
     * values from before the write.
     */
    fun beginPrivateSession() {
        synchronized(privateValues) { privateValues.clear() }
        notifyChanged()
    }

    fun endPrivateSession() {
        synchronized(privateValues) { privateValues.clear() }
        notifyChanged()
    }

    /**
     * The values to hand a script's next document, from the place that owns them for this session:
     * the private map while browsing privately, the saved script otherwise.
     */
    fun valuesFor(id: String, private: Boolean): String =
        if (private) synchronized(privateValues) { privateValues[id] } ?: "{}"
        else _scripts.value.find { it.metadata.id == id }?.values ?: "{}"

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
                    val resources = ScriptResource.readMap(entry.optJSONObject("resources") ?: JSONObject())
                    require(metadata.resources.keys.containsAll(resources.keys))
                    val values = runCatching {
                        valuesFile(metadata.id).openRead().use { TextDownloader.readText(it, MAX_VALUES_BYTES) }
                            .also { JSONObject(it) }
                    }.getOrDefault("{}")
                    loaded += InstalledUserScript(metadata, source,
                        entry.optString("url").takeIf { TextDownloader.isHttpUrl(it) }, code,
                        entry.optBoolean("enabled") && metadata.supported && code.size == metadata.requires.size &&
                            resources.keys == metadata.resources.keys, values, resources)
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
        val resources = if (metadata.supported) metadata.resources.mapValues { ScriptResource.fetch(it.value) } else emptyMap()
        mutex.withLock {
            initializeLocked()
            check(!loadFailed) { "Saved scripts could not be read" }
            val old = _scripts.value.find { it.metadata.id == metadata.id }
            require(old != null || _scripts.value.size < MAX_SCRIPTS) { "Script limit reached" }
            val script = InstalledUserScript(metadata, source,
                sourceUrl?.takeIf(TextDownloader::isHttpUrl), dependencies,
                enabled = metadata.supported && (old?.enabled ?: true), values = old?.values ?: "{}", resources = resources)
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
                    it.requiredCode.size == it.metadata.requires.size && it.resources.keys == it.metadata.resources.keys) else it
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
            // A private session’s values live in memory only, so deleting the file does not
            // reach them. Leaving them behind means removing and reinstalling the same script in
            // one session brings the previous state back.
            if (synchronized(privateValues) { privateValues.remove(id) } != null) notifyChanged()
        }
    }

    /**
     * Native side checks the current grant again, including after a script is disabled.
     *
     * [private] routes the write to the session map instead of the file. The validation is the same
     * either way — a private session grants a script no more storage than a normal one, it only
     * decides where the result may live.
     */
    suspend fun changeValue(
        id: String,
        operation: String,
        key: String,
        value: Any?,
        private: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val script = _scripts.value.find { it.metadata.id == id && it.enabled } ?: return@withLock false
            val grant = when (operation) { "set" -> "GM_setValue"; "delete" -> "GM_deleteValue"; else -> return@withLock false }
            if (!script.metadata.grants(grant) || key.length > 256 || key.contains('\u0000')) return@withLock false
            /*
             * A private write is one read-modify-write *under the same monitor a session boundary
             * takes*. The store's own mutex does not cover [beginPrivateSession] — that runs on the
             * main thread when the mode is entered — so reading the map here and writing it a few
             * statements later would let a session that ended in between have its values written
             * back, and the next session would inherit them. Doing all of it inside the monitor is
             * what makes that impossible rather than merely unlikely.
             */
            if (private) {
                val text = synchronized(privateValues) {
                    val values = JSONObject(privateValues[id] ?: "{}")
                    if (operation == "delete") values.remove(key) else values.put(key, value ?: JSONObject.NULL)
                    if (values.length() > 256) return@synchronized null
                    val encoded = values.toString()
                    if (encoded.toByteArray().size > MAX_VALUES_BYTES) return@synchronized null
                    privateValues[id] = encoded
                    encoded
                } ?: return@withLock false
                notifyChanged()
                return@withLock true
            }
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
                .put("resources", JSONObject().also { obj -> script.resources.forEach { (name, resource) -> obj.put(name, resource.json()) } })
                .put("requires", JSONArray(script.requiredCode)).put("enabled", script.enabled))
        }
        manifest.writeUtf8(json.toString())
    }

    private fun checkSize(scripts: List<InstalledUserScript>) {
        val size = scripts.sumOf { script ->
            require(script.requiredCode.all { it.toByteArray().size <= MAX_REQUIRE_BYTES })
            script.source.toByteArray().size.toLong() + script.requiredCode.sumOf { it.toByteArray().size.toLong() } +
                script.resources.values.sumOf { it.bytes().size.toLong() }
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
