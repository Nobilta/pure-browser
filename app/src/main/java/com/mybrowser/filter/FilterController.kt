package com.mybrowser.filter

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
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
    private val loading = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val rebuildGeneration = AtomicLong(0)
    private val lock = ReentrantReadWriteLock()
    private val rebuildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _enabled = MutableStateFlow(true)
    private val _ruleCount = MutableStateFlow(0)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()
    val blockedCount: Int get() = blocked.get()

    @Volatile private var builtInRules: String = ""
    @Volatile private var customRules: List<String> = emptyList()
    private var filter: NativeFilter? = null

    fun resetPageCount() = blocked.set(0)

    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }

    /** Loads the bundled list once per process. */
    fun load(scope: CoroutineScope) {
        if (!loading.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                appContext.assets.open(ASSET_LIST).bufferedReader().use { it.readText() }
            }.getOrElse {
                Log.w(TAG, "could not read $ASSET_LIST", it)
                ""
            }
            builtInRules = text
            scheduleRebuild()
        }
    }

    /** Replaces the custom list payloads and rebuilds the native matcher. */
    fun setCustomRules(rules: List<String>) {
        if (closed.get()) return
        // A downloaded list is user-controlled input. Bound both an individual payload and
        // the aggregate snapshot before it reaches the native parser, otherwise a large or
        // repeated list can retain tens of megabytes until the next rebuild completes.
        var total = 0
        customRules = rules.asSequence()
            .map { it.take(MAX_RULE_BYTES) }
            .take(MAX_CUSTOM_LISTS)
            .mapNotNull { value ->
                if (total + value.length > MAX_TOTAL_RULE_BYTES) return@mapNotNull null
                total += value.length
                value
            }
            .toList()
        scheduleRebuild()
    }

    fun reload() = scheduleRebuild()

    fun shouldBlock(request: WebResourceRequest, documentUrl: String): Boolean {
        if (!_enabled.value || request.isForMainFrame) return false
        val requestUrl = request.url.toString()
        if (requestUrl.length > MAX_URL_LENGTH || documentUrl.length > MAX_URL_LENGTH) return false
        val hit = lock.read {
            filter?.shouldBlock(requestUrl, documentUrl, classify(request)) ?: false
        }
        if (hit) blocked.incrementAndGet()
        return hit
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        rebuildGeneration.incrementAndGet()
        rebuildScope.cancel()
        lock.write {
            filter?.close()
            filter = null
            _ruleCount.value = 0
        }
    }

    /** Schedules a snapshot rebuild; only the newest snapshot may replace the live engine. */
    private fun scheduleRebuild() {
        if (closed.get()) return
        val generation = rebuildGeneration.incrementAndGet()
        rebuildScope.launch {
            val builtIn = builtInRules
            val custom = customRules
            val next = buildEngine(builtIn, custom)

            if (closed.get() || generation != rebuildGeneration.get()) {
                next?.first?.close()
                return@launch
            }

            lock.write {
                if (closed.get() || generation != rebuildGeneration.get()) {
                    next?.first?.close()
                    return@write
                }
                val old = filter
                filter = next?.first
                _ruleCount.value = next?.second ?: 0
                old?.close()
            }
        }
    }

    private fun buildEngine(
        builtIn: String,
        custom: List<String>,
    ): Pair<NativeFilter, Int>? {
        if (!NativeFilter.isAvailable) {
            return null
        }
        val next = NativeFilter.createOrNull() ?: return null
        var count = 0
        if (builtIn.isNotBlank()) count = next.addList(builtIn).coerceAtLeast(0)
        custom.forEach { rules ->
            if (rules.isNotBlank()) count = next.addList(rules).coerceAtLeast(count)
        }

        return next to count
    }

    private companion object {
        const val TAG = "FilterController"
        const val ASSET_LIST = "filters/easylist-min.txt"
        const val MAX_RULE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_RULE_BYTES = 16 * 1024 * 1024
        const val MAX_CUSTOM_LISTS = 32
        const val MAX_URL_LENGTH = 8_192

        fun classify(request: WebResourceRequest): NativeFilter.ResourceType {
            val accept = request.requestHeaders.entries
                .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                ?.value.orEmpty()
                .lowercase()
            when {
                accept.startsWith("text/css") -> return NativeFilter.ResourceType.STYLESHEET
                accept.startsWith("image/") -> return NativeFilter.ResourceType.IMAGE
                accept.startsWith("video/") || accept.startsWith("audio/") ->
                    return NativeFilter.ResourceType.MEDIA
                accept.startsWith("font/") || accept.contains("font/woff") ->
                    return NativeFilter.ResourceType.FONT
                accept.contains("text/html") -> return NativeFilter.ResourceType.SUBDOCUMENT
            }

            val ext = request.url.path.orEmpty().substringAfterLast('.', "").lowercase()
            return when (ext) {
                "js", "mjs" -> NativeFilter.ResourceType.SCRIPT
                "css" -> NativeFilter.ResourceType.STYLESHEET
                "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif" ->
                    NativeFilter.ResourceType.IMAGE
                "woff", "woff2", "ttf", "otf", "eot" -> NativeFilter.ResourceType.FONT
                "mp4", "webm", "m4v", "mov", "m3u8", "mpd", "mp3", "m4a", "aac", "ogg" ->
                    NativeFilter.ResourceType.MEDIA
                "json" -> NativeFilter.ResourceType.XML_HTTP_REQUEST
                "html", "htm" -> NativeFilter.ResourceType.SUBDOCUMENT
                else -> NativeFilter.ResourceType.OTHER
            }
        }
    }
}
