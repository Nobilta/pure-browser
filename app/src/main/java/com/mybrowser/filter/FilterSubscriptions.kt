package com.mybrowser.filter

import android.content.Context
import android.util.AtomicFile
import android.util.Log
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

    /**
     * What happened to a rule the user wrote; the caller says which, so a tap that blocked nothing
     * is never reported as a block.
     */
    enum class RuleChange { ADDED, ALREADY_PRESENT, INVALID, FULL, TOO_LARGE, SAVE_FAILED }

    /**
     * Rules the user added by hand, oldest first.
     *
     * Kept in the same preference entry group as the subscription manifest rather than as a
     * [Subscription]: a subscription is something the app downloads, updates and can fail to fetch,
     * and a hand-written rule is none of those. It is loaded as one more payload in the same engine
     * snapshot, so it participates in blocking exactly like a list rule does.
     */
    private val _userRules = MutableStateFlow<List<String>>(emptyList())
    val userRules = _userRules.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock { initializeLocked() }
    }

    /**
     * Adds one hand-written rule.
     *
     * The in-memory list is only advanced after the preference write is confirmed, so a rule that
     * did not reach disk is not reported as applied.
     */
    suspend fun addUserRule(rule: String): RuleChange = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            val line = rule.trim()
            if (!BlockRules.isValid(line)) return@withLock RuleChange.INVALID
            val current = _userRules.value
            if (current.any { it == line }) return@withLock RuleChange.ALREADY_PRESENT
            if (current.size >= MAX_USER_RULES) return@withLock RuleChange.FULL
            val next = current + line
            // Hand-written rules share one budget with the subscriptions in the engine snapshot,
            // so a rule that would push the combined payload past the limit has to be refused here.
            // Accepting it would persist a rule the engine then rejects wholesale, leaving the user
            // with a rule the settings show and the filter never applies.
            if (!fitsPayloadLimit(next)) return@withLock RuleChange.TOO_LARGE
            if (!persistUserRules(next)) return@withLock RuleChange.SAVE_FAILED
            _userRules.value = next
            rebuild()
            RuleChange.ADDED
        }
    }

    /** Removes one rule. False when it was not there, so the caller does not claim a removal. */
    suspend fun removeUserRule(rule: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            initializeLocked()
            val line = rule.trim()
            val current = _userRules.value
            if (current.none { it == line }) return@withLock false
            val next = current.filterNot { it == line }
            if (!persistUserRules(next)) return@withLock false
            _userRules.value = next
            rebuild()
            true
        }
    }

    /** True when the write was confirmed; the caller must not publish the change otherwise. */
    private fun persistUserRules(rules: List<String>): Boolean = runCatching {
        val text = rules.joinToString("\n")
        require(text.length <= MAX_USER_RULES_BYTES)
        prefs.commitConfirmed(mapOf(USER_RULES_KEY to text))
    }.isSuccess

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
        _userRules.value = readUserRules()
        initialized = true
        rebuild()
    }

    /**
     * Reads the hand-written rules, dropping any that are no longer rules.
     *
     * A rule this app wrote always passes [BlockRules.isValid], so a line that fails here came from
     * an older version or an edited preference file. It is dropped rather than loaded, because an
     * unparseable line makes the whole payload uncountable and the engine would then keep its
     * previous snapshot. The cleaned list is written back on the next change, not here: reading
     * must not write.
     */
    private fun readUserRules(): List<String> {
        val stored = prefs.getString(USER_RULES_KEY, null) ?: return emptyList()
        if (stored.length > MAX_USER_RULES_BYTES) {
            // Refusing the whole blob discards every rule the user wrote, which they cannot tell
            // apart from the rules never having been saved. Nothing else here writes a blob this
            // large, so it means the file was edited or truncated; the log is the only trace.
            Log.w(TAG, "Hand-written rules exceeded $MAX_USER_RULES_BYTES bytes and were not loaded")
            return emptyList()
        }
        return stored.lineSequence()
            .map(String::trim)
            .filter(BlockRules::isValid)
            // `addUserRule` refuses a duplicate, so a repeated line can only come from an edited
            // preference file; it is still dropped here, because the settings list keys its rows by
            // the rule text and two identical keys throw when the list is composed.
            .distinct()
            .take(MAX_USER_RULES)
            .toList()
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
            // Built aside from [loaded], which belongs to the caller: a refused migration must
            // leave the in-memory list exactly as it was, not merely skip the write.
            val candidates = mutableListOf<Subscription>()
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
                candidates += entry
            }
            // The same three ceilings every other write path checks. A migration that saved an
            // over-budget set would leave every list shown as enabled with a rule count while the
            // engine quietly kept its previous snapshot — the exact state checkSize exists to
            // prevent, and the one path that used to skip it.
            checkSize(loaded + candidates)
            persist(loaded + candidates)
            loaded += candidates
        }.onFailure { error ->
            // Nothing was persisted and nothing was added: the legacy store is left for a later
            // build, and the user is no worse off than before this ran.
            Log.w(TAG, "Legacy filter lists were not migrated: ${error.message}")
        }
    }

    fun setAutoUpdate(value: Boolean) {
        prefs.edit { putBoolean("auto_update", value) }
        _autoUpdate.value = value
        FilterUpdateJob.schedule(appContext, value)
    }

    suspend fun setEnabled(id: String, value: Boolean): Boolean = mutate {
        val next = _subscriptions.value.map { if (it.id == id) it.copy(enabled = value) else it }
        // Enabling a list adds its rules to the engine snapshot, so it has to pass the same budget
        // a new rule does. Disabling only shrinks the snapshot and passes on its own.
        checkSize(next)
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
     * them wholesale, so "absent" and "cleared" stay distinguishable. Hand-written rules
     * follow the same rule, for the same reason: an older file that never carried them
     * must not silently wipe the ones a user wrote on this device.
     */
    suspend fun importConfiguration(
        builtInStates: Map<String, Boolean>,
        customLists: List<Triple<String, String, Boolean>>?,
        enabled: Boolean? = null,
        autoUpdate: Boolean? = null,
        userRules: List<String>? = null,
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
                val nextRules = userRules?.let { rules ->
                    // Refused here rather than written and dropped: the engine ignores a rule it
                    // cannot read, so a file carrying one has to fail the group instead of
                    // importing as though it had been applied.
                    val cleaned = rules.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    require(cleaned.size <= MAX_USER_RULES) { "User rule limit reached" }
                    require(cleaned.all(BlockRules::isValid)) { "Unsupported user rule" }
                    cleaned
                } ?: _userRules.value
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
                checkSize(next, nextRules)
                // Only an import that carries rules writes that key: a file that said nothing about
                // them leaves the stored text exactly as it was, including a version of it written
                // by a newer build than the one doing the importing.
                persist(next, enabled, autoUpdate, userRules?.let { nextRules })
                _subscriptions.value = next
                _userRules.value = nextRules
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

    /**
     * Refuses a configuration the engine would reject as a whole.
     *
     * Every path that changes what is enabled runs this, because the engine takes one snapshot of
     * subscription payloads plus the hand-written rules and refuses the whole set when any of the
     * three ceilings is passed. Checking only the subscription files leaves a configuration that
     * saves, shows as enabled, and then silently keeps the previous snapshot in the engine.
     *
     * A payload that cannot be read is counted as absent rather than as zero: the rebuild would
     * skip it too, and refusing a change over a list the engine will never see would be wrong.
     *
     * [rules] defaults to the stored hand-written rules, but every caller that is about to commit
     * a *different* set has to pass it: an import writes its rules after this check, so judging it
     * against the old ones would let a file through that the engine then refuses.
     */
    internal fun checkSize(lists: List<Subscription>, rules: List<String> = _userRules.value) {
        if (lists.sumOf { it.bytes.toLong() } > FilterListFormat.MAX_TOTAL_BYTES) {
            throw IOException("Filter storage limit reached")
        }
        val sizes = lists.filter { it.enabled }.mapNotNull { readPayload(it)?.toByteArray(Charsets.UTF_8)?.size }
        val rulesBytes = if (rules.isEmpty()) 0 else rules.joinToString("\n").toByteArray(Charsets.UTF_8).size
        if (!fitsPayloadLimit(sizes, rulesBytes)) throw IOException("Filter storage limit reached")
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

    /**
     * Whether [rules] leave room for them inside the engine’s aggregate payload limit.
     *
     * The same three ceilings [FilterController.replaceLists] enforces are checked here, before the
     * rule is written: per-payload, total payload, and count. Checking only the file size would still
     * let the combined snapshot be refused, which is the failure this exists to prevent.
     */
    private fun fitsPayloadLimit(rules: List<String>): Boolean {
        val enabled = _subscriptions.value.filter { it.enabled }
        val sizes = enabled.mapNotNull { readPayload(it)?.let { text -> text.toByteArray(Charsets.UTF_8).size } }
        return fitsPayloadLimit(sizes, rules.joinToString("\n").toByteArray(Charsets.UTF_8).size)
    }

    /**
     * The arithmetic behind [fitsPayloadLimit], over sizes the caller already has.
     *
     * Split out so the three ceilings the engine enforces can be pinned directly: seeding payloads
     * near the 32 MiB limit just to watch one comparison fail would cost the test more than the
     * check it covers.
     */
    internal fun fitsPayloadLimit(existingPayloadBytes: List<Int>, rulesBytes: Int): Boolean {
        if (rulesBytes > FilterListFormat.MAX_BYTES) return false
        if (existingPayloadBytes.size + 1 > FilterListFormat.MAX_LISTS) return false
        return existingPayloadBytes.sumOf { it.toLong() } + rulesBytes <= FilterListFormat.MAX_TOTAL_BYTES
    }

    private suspend fun rebuild() {
        val enabled = _subscriptions.value.filter { it.enabled }.mapNotNull { list -> readPayload(list)?.let { list.name to it } }
        val rules = _userRules.value
        // One payload, named in the settings and in the "why was this blocked" report like any
        // other source. An empty list is left out rather than sent as an empty payload, which
        // FilterListFormat would reject for having no rules.
        val payloads = if (rules.isEmpty()) enabled
            else enabled + (appContext.getString(R.string.filter_user_rules) to rules.joinToString("\n"))
        // Null means the engine refused the whole set — over one of the three ceilings — and is
        // still holding its previous snapshot. Nothing else can reach this point once the write
        // paths pre-check, so a null here means a check and the payloads have drifted apart; it is
        // logged rather than assumed impossible.
        val job = filter?.replaceLists(payloads.map { it.second }, payloads.map { it.first })
        if (filter != null && job == null) Log.w(TAG, "Filter engine refused the ${payloads.size}-list snapshot")
        job?.join()
    }

    private fun persist(
        lists: List<Subscription>,
        enabled: Boolean? = null,
        autoUpdate: Boolean? = null,
        userRules: List<String>? = null,
    ) {
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().put("id", list.id).put("name", list.name).put("url", list.url)
                .put("enabled", list.enabled).put("file", list.file).put("updatedAt", list.updatedAt)
                .put("checkedAt", list.checkedAt).put("etag", list.etag).put("modified", list.modified))
        }
        val text = array.toString()
        require(text.length <= 256 * 1024) { "Subscription manifest too large" }
        // Hand-written rules ride in the same confirmed write: an import either lands whole or
        // leaves the previous configuration in place, never half of each.
        val rules = userRules?.joinToString("\n")?.also { require(it.length <= MAX_USER_RULES_BYTES) }
        prefs.commitConfirmed(buildMap {
            put(MANIFEST_KEY, text)
            enabled?.let { put("enabled", it) }
            autoUpdate?.let { put("auto_update", it) }
            rules?.let { put(USER_RULES_KEY, it) }
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

        private const val TAG = "FilterSubscriptions"
        private const val MANIFEST_KEY = "subscriptions_manifest"

        /** Hand-written rules. In the same preference file so a settings import commits them too. */
        private const val USER_RULES_KEY = "user_rules"

        /**
         * Ceiling on hand-written rules. Small on purpose: every rule is matched against every
         * request, and a list this size is already far past what someone types by hand.
         */
        const val MAX_USER_RULES = 500
        private const val MAX_USER_RULES_BYTES = 64 * 1024
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
