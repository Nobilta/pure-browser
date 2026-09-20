package com.mybrowser.filter

import android.content.Context
import android.webkit.WebResourceRequest
import androidx.core.content.edit
import com.mybrowser.core.ResourceType
import com.mybrowser.core.classifyResourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Owns the process-wide native ad-blocking engine.
 *
 * Rule lists are parsed off the UI thread. Rebuilding uses a read/write lock so a WebView
 * worker never races a native handle being freed, while normal request checks only take a
 * short read lock around the JNI call. Custom lists are part of the same engine as the
 * bundled list; the previous implementation's static placeholder silently ignored them.
 */
class FilterController(private val appContext: Context) {

    private val blocked = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val rebuildGeneration = AtomicLong(0)
    private val lock = ReentrantReadWriteLock()
    private val rebuildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("filter_settings", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    private val _ruleCount = MutableStateFlow(0)
    private val _cosmeticCount = MutableStateFlow(0)
    private val _unsupportedCount = MutableStateFlow(0)
    private val firstBuild = CompletableDeferred<Unit>()
    val isReady: Boolean get() = firstBuild.isCompleted
    suspend fun awaitReady() = firstBuild.await()

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()
    val cosmeticCount: StateFlow<Int> = _cosmeticCount.asStateFlow()
    val unsupportedCount: StateFlow<Int> = _unsupportedCount.asStateFlow()
    val blockedCount: Int get() = blocked.get()

    @Volatile private var payloads: List<String> = emptyList()
    @Volatile private var sourceNames: List<String> = emptyList()
    private var activeSources: List<Pair<String, String>> = emptyList()
    private var filter: NativeFilter? = null

    fun resetPageCount() = blocked.set(0)

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean("enabled", value) }
        _enabled.value = value
    }

    /** Publish the switch only after a grouped settings import has committed it. */
    internal fun reloadEnabledPreference() {
        _enabled.value = prefs.getBoolean("enabled", true)
    }

    /** Every enabled subscription participates in the same atomic engine snapshot. */
    fun replaceLists(rules: List<String>, names: List<String> = emptyList()): Job? {
        if (closed.get()) return null
        val sizes = rules.map { utf8Bytes(it) }
        if (rules.size > 35 || sizes.any { it > FilterListFormat.MAX_BYTES } ||
            sizes.sum() > FilterListFormat.MAX_TOTAL_BYTES) return null
        payloads = rules.toList()
        sourceNames = names.toList()
        return scheduleRebuild()
    }

    data class Explanation(val blocking: Pair<String, String>?, val exception: Pair<String, String>?)

    /** Re-evaluate with the current loaded lists. Called only when a user asks for details. */
    fun explain(url: String, document: String, type: ResourceType): Explanation {
        val sources = lock.read { activeSources }
        val engine = checkNotNull(NativeFilter.createOrNull()) { "Filter engine unavailable" }
        var blocking: Pair<String, String>? = null
        var exception: Pair<String, String>? = null
        try {
            for ((name, text) in sources) {
                val (hit, allow) = engine.explainList(text, url, document, type)
                if (blocking == null && hit != null) blocking = name to hit
                if (exception == null && allow != null) exception = name to allow
            }
        } finally { engine.close() }
        return Explanation(blocking, exception)
    }

    fun reload() = scheduleRebuild()

    fun cosmeticCss(url: String, siteEnabled: Boolean = true): String =
        if (_enabled.value && siteEnabled) lock.read { filter?.cosmeticCss(url).orEmpty() } else ""

    fun shouldBlock(request: WebResourceRequest, documentUrl: String, siteEnabled: Boolean = true,
        type: ResourceType = classifyResourceType(request), countForPage: Boolean = true): Boolean {
        if (!_enabled.value || !siteEnabled || request.isForMainFrame) return false
        val requestUrl = request.url.toString()
        if (requestUrl.length > MAX_URL_LENGTH || documentUrl.length > MAX_URL_LENGTH) return false
        val hit = lock.read {
            filter?.shouldBlock(requestUrl, documentUrl, type) ?: false
        }
        if (hit && countForPage) blocked.incrementAndGet()
        return hit
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        rebuildGeneration.incrementAndGet()
        rebuildScope.cancel()
        val old = lock.write {
            val previous = filter
            filter = null
            _ruleCount.value = 0
            _cosmeticCount.value = 0
            _unsupportedCount.value = 0
            activeSources = emptyList()
            previous
        }
        old?.close()
    }

    /** Schedules a snapshot rebuild; only the newest snapshot may replace the live engine. */
    private fun scheduleRebuild(): Job? {
        if (closed.get()) return null
        val generation = rebuildGeneration.incrementAndGet()
        return rebuildScope.launch {
            delay(80) // Coalesce rapid subscription changes before parsing large lists.
            if (generation != rebuildGeneration.get()) return@launch
            val snapshot = payloads
            val names = sourceNames
            val next = buildEngine(snapshot, names)

            if (closed.get() || generation != rebuildGeneration.get()) {
                next?.first?.close()
                return@launch
            }

            val discarded = lock.write {
                if (closed.get() || generation != rebuildGeneration.get()) {
                    return@write next?.first
                }
                val old = filter
                filter = next?.first
                _ruleCount.value = next?.second ?: 0
                _cosmeticCount.value = next?.first?.cosmeticRuleCount ?: 0
                _unsupportedCount.value = next?.first?.unsupportedRuleCount ?: 0
                activeSources = snapshot.mapIndexed { index, text -> (names.getOrNull(index) ?: "List ${index + 1}") to text }
                firstBuild.complete(Unit)
                old
            }
            // Acquiring the write lock waited for every old reader. No future reader can
            // see the detached handle, so destruction need not stall new requests.
            discarded?.close()
        }
    }

    private fun buildEngine(
        lists: List<String>,
        names: List<String>,
    ): Pair<NativeFilter, Int>? {
        if (!NativeFilter.isAvailable) {
            return null
        }
        val next = NativeFilter.createOrNull() ?: return null
        var count = 0
        lists.forEachIndexed { index, rules ->
            if (rules.isNotBlank()) {
                val added = next.addList(rules)
                // The native side refuses oversize or unparseable lists with -1; swallowing
                // that would leave the engine silently short with no trace in a bug report.
                if (added < 0) android.util.Log.w(
                    "FilterController",
                    "Refused filter list ${names.getOrNull(index) ?: "List ${index + 1}"}",
                )
                count = added.coerceAtLeast(count)
            }
        }

        return next to count
    }

    companion object {
        const val MAX_URL_LENGTH = 8_192

        /** UTF-8 size without copying the text; the list caps are byte budgets, not character counts. */
        fun utf8Bytes(text: String): Long {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                bytes += when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                index += Character.charCount(codePoint)
            }
            return bytes
        }
    }
}
