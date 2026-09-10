package com.mybrowser.filter

import android.content.Context
import android.webkit.WebResourceRequest
import androidx.core.content.edit
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
    private val firstBuild = CompletableDeferred<Unit>()
    val isReady: Boolean get() = firstBuild.isCompleted
    suspend fun awaitReady() = firstBuild.await()

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()
    val cosmeticCount: StateFlow<Int> = _cosmeticCount.asStateFlow()
    val blockedCount: Int get() = blocked.get()

    @Volatile private var payloads: List<String> = emptyList()
    private var filter: NativeFilter? = null

    fun resetPageCount() = blocked.set(0)

    fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean("enabled", value) }
        _enabled.value = value
    }

    /** Every enabled subscription participates in the same atomic engine snapshot. */
    fun replaceLists(rules: List<String>): Job? {
        if (closed.get()) return null
        if (rules.size > 35 || rules.any { it.length > FilterListFormat.MAX_BYTES } ||
            rules.sumOf { it.length.toLong() } > FilterListFormat.MAX_TOTAL_BYTES) return null
        payloads = rules.toList()
        return scheduleRebuild()
    }

    fun reload() = scheduleRebuild()

    fun cosmeticCss(url: String, siteEnabled: Boolean = true): String =
        if (_enabled.value && siteEnabled) lock.read { filter?.cosmeticCss(url).orEmpty() } else ""

    fun shouldBlock(request: WebResourceRequest, documentUrl: String, siteEnabled: Boolean = true): Boolean {
        if (!_enabled.value || !siteEnabled || request.isForMainFrame) return false
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
            _cosmeticCount.value = 0
        }
    }

    /** Schedules a snapshot rebuild; only the newest snapshot may replace the live engine. */
    private fun scheduleRebuild(): Job? {
        if (closed.get()) return null
        val generation = rebuildGeneration.incrementAndGet()
        return rebuildScope.launch {
            delay(80) // Coalesce rapid subscription changes before parsing large lists.
            if (generation != rebuildGeneration.get()) return@launch
            val snapshot = payloads
            val next = buildEngine(snapshot)

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
                _cosmeticCount.value = next?.first?.cosmeticRuleCount ?: 0
                old?.close()
                firstBuild.complete(Unit)
            }
        }
    }

    private fun buildEngine(
        lists: List<String>,
    ): Pair<NativeFilter, Int>? {
        if (!NativeFilter.isAvailable) {
            return null
        }
        val next = NativeFilter.createOrNull() ?: return null
        var count = 0
        lists.forEach { rules ->
            if (rules.isNotBlank()) count = next.addList(rules).coerceAtLeast(count)
        }

        return next to count
    }

    private companion object {
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
