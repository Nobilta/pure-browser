package com.mybrowser.translate

import android.content.Context
import android.os.SystemClock
import android.view.ActionMode
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mybrowser.R
import com.mybrowser.core.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns translation jobs, tab snapshots and UI state for one browser window. */
internal class TranslationController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
    private val translate: suspend (TranslationSettings, List<String>) -> List<String> = { settings, texts ->
        TranslationClient(settings).translateAll(texts, settings.targetLanguage)
    },
) {
    interface Host {
        val view: WebView?
        val url: String
        val documentEpoch: Long
        val ownerTabId: String?
        val selectedTabId: String?
        val panelVisible: Boolean
        fun message(text: String)
    }

    private val settingsRepository = TranslationSettingsRepository(context)
    var settings by mutableStateOf(settingsRepository.load())
        private set
    private val pageTranslator = PageTranslator(context)
    var busy by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var pageTranslated by mutableStateOf(false)
        private set
    var sessionActive by mutableStateOf(false)
        private set
    var complete by mutableStateOf(false)
        private set
    var closing by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var selectionOriginal by mutableStateOf<String?>(null)
        private set
    var selectionTranslated by mutableStateOf<String?>(null)
        private set
    var selectionBusy by mutableStateOf(false)
        private set
    var selectionError by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var scrollJob: Job? = null
    private var pollJob: Job? = null
    private var generationId = 0
    private var appliedMode: TranslationPageMode? = null
    private var pending = linkedMapOf<Int, TranslationPageItem>()
    var hasPending by mutableStateOf(false)
        private set
    private var totalChars = 0
    private var doneChars = 0
    private var scrollPending = false
    private var retryAt = 0L
    private var errorReported = false
    private data class TranslationTabSnapshot(
        val url: String,
        val mode: TranslationPageMode,
        val pageTranslated: Boolean,
        val sessionActive: Boolean,
        val complete: Boolean,
        val pending: LinkedHashMap<Int, TranslationPageItem>,
        val totalChars: Int,
        val doneChars: Int,
        val progress: Int,
    )
    private val tabSnapshots = mutableMapOf<String, TranslationTabSnapshot>()
    private var selectionJob: Job? = null
    private var selectionCaptureJob: Job? = null
    private var stateJob: Job? = null

    private fun saveForTab(tabId: String) {
        val mode = appliedMode ?: return
        if (!sessionActive && !pageTranslated && pending.isEmpty()) return
        tabSnapshots[tabId] = TranslationTabSnapshot(
            url = host.url,
            mode = mode,
            pageTranslated = pageTranslated,
            sessionActive = sessionActive,
            complete = complete,
            pending = LinkedHashMap(pending),
            totalChars = totalChars,
            doneChars = doneChars,
            progress = progress,
        )
    }

    private fun startComparisonResume(view: WebView, tabId: String) {
        if (host.ownerTabId != tabId || host.selectedTabId != tabId) return
        job?.cancel()
        scrollJob?.cancel()
        generationId++
        val generation = generationId
        val pageUrl = host.url
        val pageGeneration = host.documentEpoch
        sessionActive = true
        complete = false
        busy = true
        hasPending = true
        closing = false
        appliedMode = TranslationPageMode.COMPARE
        job = scope.launch {
            try {
                runComparisonTranslationLoop(view, pageUrl, pageGeneration, generation)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                busy = false
                sessionActive = false
                complete = false
                hasPending = true
                reportPageTranslationError(error)
            } finally {
                if (generationId == generation) busy = false
            }
        }
    }

    private fun restoreTranslationSnapshot(view: WebView, tabId: String, snapshot: TranslationTabSnapshot) {
        pending.clear()
        pending.putAll(snapshot.pending)
        totalChars = snapshot.totalChars
        doneChars = snapshot.doneChars
        progress = snapshot.progress
        pageTranslated = snapshot.pageTranslated
        sessionActive = snapshot.sessionActive
        complete = snapshot.complete
        hasPending = snapshot.pending.isNotEmpty() || !snapshot.complete
        appliedMode = snapshot.mode
        closing = false
        errorMessage = null
        busy = false
        when {
            snapshot.mode == TranslationPageMode.COMPARE && snapshot.sessionActive ->
                startComparisonResume(view, tabId)
            snapshot.mode == TranslationPageMode.REPLACE && snapshot.sessionActive -> {
                sessionActive = true
                startReplacePolling(view, host.url, host.documentEpoch, generationId)
                if (pending.isNotEmpty()) startVisibleTranslationJob()
            }
        }
    }

    fun refresh() {
        val view = host.view ?: run {
            pageTranslated = false
            sessionActive = false
            complete = false
            return
        }
        val tabId = host.selectedTabId ?: run {
            pageTranslated = false
            sessionActive = false
            complete = false
            return
        }
        stateJob?.cancel()
        stateJob = scope.launch {
            if (host.view !== view || host.ownerTabId != tabId || host.selectedTabId != tabId ||
                sessionActive
            ) return@launch
            val pageUrl = host.url
            val epoch = host.documentEpoch
            val mode = runCatching { pageTranslator.translatedMode(view) }.getOrNull()
            currentCoroutineContext().ensureActive()
            if (host.view !== view || host.ownerTabId != tabId || host.selectedTabId != tabId ||
                host.url != pageUrl || host.documentEpoch != epoch || sessionActive
            ) return@launch
            val snapshot = tabSnapshots.remove(tabId)
            // Reloaded WebViews no longer own the JS observers saved by the old document.
            if (snapshot != null && snapshot.url == pageUrl && snapshot.mode == mode) {
                restoreTranslationSnapshot(view, tabId, snapshot)
                return@launch
            }
            pageTranslated = mode != null
            appliedMode = mode
            complete = mode != null
        }
    }

    /**
     * WebView owns the selection while ActionMode is alive. Read it before finishing the mode;
     * finishing first clears the DOM selection and the async read then returns an empty string.
     */
    fun translateSelection(mode: ActionMode) {
        val view = host.view ?: run {
            runCatching { mode.finish() }
            return
        }
        selectionCaptureJob?.cancel()
        selectionCaptureJob = scope.launch {
            val text = runCatching { pageTranslator.selection(view) }.getOrDefault("")
            runCatching { mode.finish() }
            if (host.view !== view) return@launch
            startSelection(text)
        }
    }

    /** Starts the result panel from text captured by the native selection action. */
    private fun startSelection(text: String) {
        if (text.isBlank()) {
            host.message(context.getString(R.string.translate_selection_empty))
            return
        }
        selectionJob?.cancel()
        selectionOriginal = text
        selectionTranslated = null
        selectionError = null
        selectionBusy = true
        selectionJob = scope.launch {
            try {
                selectionTranslated = translate(settings, listOf(text)).single()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                selectionError = error.message ?: context.getString(R.string.translate_error, "")
            } finally {
                selectionBusy = false
            }
        }
    }

    private fun detach() {
        job?.cancel()
        scrollJob?.cancel()
        pollJob?.cancel()
        stateJob?.cancel()
        generationId++
        pending.clear()
        hasPending = false
        scrollPending = false
        busy = false
        progress = 0
        totalChars = 0
        doneChars = 0
        pageTranslated = false
        sessionActive = false
        complete = false
        closing = false
        appliedMode = null
        errorMessage = null
        retryAt = 0L
        errorReported = false
    }

    fun translatePage(): Boolean {
        if (busy || pageTranslated || sessionActive) return false
        val view = host.view ?: return false
        if (!UrlUtils.isHttpUrl(host.url)) {
            errorMessage = context.getString(R.string.translate_no_text)
            return false
        }
        val settings = this.settings
        if (!settings.configured) {
            errorMessage = context.getString(R.string.translate_not_configured)
            return false
        }
        detach()
        busy = true
        sessionActive = true
        val mode = settings.pageMode
        appliedMode = mode
        val pageUrl = host.url
        val pageGeneration = host.documentEpoch
        val generation = generationId
        job = scope.launch {
            try {
                if (mode == TranslationPageMode.COMPARE) {
                    val session = pageTranslator.startComparison(view)
                    if (host.view !== view || host.url != pageUrl || host.documentEpoch != pageGeneration ||
                        generationId != generation
                    ) return@launch
                    if (session.totalGroups == 0) {
                        errorMessage = context.getString(R.string.translate_no_text)
                        sessionActive = false
                        return@launch
                    }
                    totalChars = session.totalCharacters.coerceAtLeast(1)
                    hasPending = true
                    if (session.truncated) host.message(context.getString(R.string.translate_truncated))
                    runComparisonTranslationLoop(view, pageUrl, pageGeneration, generation)
                } else {
                    val page = pageTranslator.collect(view)
                    if (host.view !== view || host.url != pageUrl || host.documentEpoch != pageGeneration ||
                        generationId != generation
                    ) return@launch
                    if (page.items.isEmpty()) {
                        errorMessage = context.getString(R.string.translate_no_text)
                        sessionActive = false
                        return@launch
                    }
                    totalChars = page.totalCharacters.coerceAtLeast(1)
                    page.items.forEach { pending[it.id] = it }
                    hasPending = pending.isNotEmpty()
                    if (page.truncated) host.message(context.getString(R.string.translate_truncated))
                    startReplacePolling(view, pageUrl, pageGeneration, generation)
                    translateVisiblePageItems(view, pageUrl, pageGeneration, generation, allowFallback = true)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                busy = false
                complete = false
                if (mode == TranslationPageMode.REPLACE && pageTranslated) {
                    // The initial viewport pass can contain several batches. A failure after
                    // the first one used to cancel the poll and stop the session, unlike the
                    // same failure from a scroll-triggered batch. Keep the watcher alive and
                    // use the same 2-second backoff.
                    hasPending = pending.isNotEmpty()
                    retryAt = SystemClock.uptimeMillis() + 2_000
                } else {
                    pollJob?.cancel()
                    sessionActive = false
                    if (!pageTranslated) {
                        pending.clear()
                        hasPending = false
                        appliedMode = null
                        closing = true
                        runCatching {
                            if (mode == TranslationPageMode.COMPARE) pageTranslator.restoreComparison(view)
                            else pageTranslator.restore(view)
                        }
                    } else {
                        hasPending = true
                    }
                }
                reportPageTranslationError(error)
            } finally {
                if (generationId == generation) {
                    busy = false
                    if (mode == TranslationPageMode.REPLACE) {
                        updateReplaceSessionComplete()
                        if (!pageTranslated && !hasPending) {
                            sessionActive = false
                            complete = false
                            appliedMode = null
                        }
                        queueVisibleTranslationAfterBatch()
                    } else if (!pageTranslated && !hasPending) {
                        sessionActive = false
                        complete = false
                        appliedMode = null
                    }
                }
            }
        }
        return true
    }

    /**
     * Hcfy-style comparison loop. JS owns the IntersectionObserver/MutationObserver and only
     * marks groups ready when they enter the viewport. Native takes those groups serially,
     * applies each batch back into its paragraph, and keeps polling at a low rate once the
     * current page is complete so dynamically inserted content can resume the session.
     */
    private suspend fun runComparisonTranslationLoop(
        view: WebView,
        pageUrl: String,
        pageGeneration: Long,
        generation: Int,
    ) {
        val settings = this.settings
        while (currentCoroutineContext().isActive) {
            if (!sessionActive ||
                host.view !== view || host.url != pageUrl ||
                host.documentEpoch != pageGeneration || generationId != generation
            ) break
            val batch = pageTranslator.takeReady(view)
            updateComparisonMetrics(batch)
            if (batch.items.isEmpty()) {
                busy = false
                hasPending = !batch.complete
                complete = batch.complete
                if (batch.complete) delay(800) else delay(180)
                continue
            }
            val ids = batch.items.map { it.id }
            complete = false
            hasPending = true
            busy = true
            try {
                val translated = translate(settings, batch.items.map { it.text })
                if (host.view !== view || host.url != pageUrl ||
                    host.documentEpoch != pageGeneration || generationId != generation
                ) break
                val appliedCount = pageTranslator.applyComparison(
                    view,
                    batch.items.zip(translated) { item, text -> item.id to text },
                    settings.targetLanguage,
                )
                pageTranslated = pageTranslated || appliedCount > 0
                updateComparisonMetrics(pageTranslator.comparisonStatus(view))
                busy = false
                errorMessage = null
                errorReported = false
                delay(60)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // The batch has already left JS's ready queue. Put it back before unwinding so a
                // tab switch cannot strand these paragraphs in pending=true forever.
                withContext(NonCancellable) {
                    runCatching { pageTranslator.requeueComparison(view, ids) }
                }
                throw cancelled
            } catch (error: Exception) {
                busy = false
                complete = false
                if (pageTranslated) {
                    runCatching { pageTranslator.requeueComparison(view, ids) }
                    hasPending = true
                    reportPageTranslationError(error)
                    // Keep the session alive: the requeued groups are retried after a pause
                    // instead of leaving a page that is half translated, with both the sheet
                    // and the scroll path refusing to continue.
                    delay(2_000)
                    continue
                }
                runCatching { pageTranslator.restoreComparison(view) }
                sessionActive = false
                hasPending = false
                appliedMode = null
                closing = true
                reportPageTranslationError(error)
                return
            }
        }
    }

    private fun updateComparisonMetrics(batch: com.mybrowser.translate.TranslationComparisonBatch) {
        totalChars = batch.totalCharacters.coerceAtLeast(1)
        doneChars = batch.doneCharacters
        progress = if (batch.totalCharacters > 0) {
            (batch.doneCharacters * 100 / batch.totalCharacters).coerceIn(0, 100)
        } else 0
        hasPending = !batch.complete
        complete = batch.complete
    }

    private fun updateReplaceSessionComplete() {
        if (appliedMode == TranslationPageMode.REPLACE &&
            pageTranslated && pending.isEmpty()
        ) {
            complete = true
            hasPending = false
            progress = 100
        }
    }

    /**
     * Replacement mode keeps a lightweight rescan alive while a session is active. This covers
     * nested scroll containers and text/style mutations that the old top-level scroll listener
     * could not see; the JS side returns only new or changed text nodes.
     */
    private fun startReplacePolling(
        view: WebView,
        pageUrl: String,
        pageGeneration: Long,
        generation: Int,
    ) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(if (complete) 800 else 350)
                if (!sessionActive ||
                    appliedMode != TranslationPageMode.REPLACE ||
                    generationId != generation ||
                    host.view !== view || host.url != pageUrl ||
                    host.documentEpoch != pageGeneration
                ) break
                val updated = runCatching { pageTranslator.collectNewReplace(view) }.getOrNull()
                if (updated != null) {
                    updated.updatePending(pending)
                    if (updated.items.isNotEmpty()) {
                        hasPending = true
                        complete = false
                    }
                    totalChars = updated.totalCharacters.coerceAtLeast(1)
                    hasPending = pending.isNotEmpty()
                }
                val now = SystemClock.uptimeMillis()
                val visible = if (job?.isActive != true && !busy &&
                    pending.isNotEmpty() && now >= retryAt
                ) {
                    runCatching { pageTranslator.visibleIds(view, TranslationPageMode.REPLACE) }
                        .getOrDefault(emptySet())
                } else {
                    emptySet()
                }
                val hasVisiblePending = visible.any { pending.containsKey(it) }
                if (hasVisiblePending) {
                    startVisibleTranslationJob()
                }
                if (pending.isEmpty() && !busy) {
                    updateReplaceSessionComplete()
                    // Keep watching after the last batch: user-opened panels and infinite
                    // scroll can add text later. Only Stop/navigation ends this loop.
                }
            }
        }
    }

    private fun reportPageTranslationError(error: Exception) {
        val message = error.message ?: ""
        errorMessage = message
        // The sheet is normally dismissed once a page translation starts, so an error must
        // also be visible from the page-level floating control. Retries keep the error text
        // but do not repeat the toast every retry.
        if (!errorReported && !host.panelVisible) {
            host.message(context.getString(R.string.translate_error, message))
        }
        errorReported = true
    }

    /**
     * Translates only the text blocks near the viewport, then returns. Scrolling asks for the
     * next viewport; the whole page is never translated in one blocking pass.
     */
    private suspend fun translateVisiblePageItems(
        view: WebView,
        pageUrl: String,
        pageGeneration: Long,
        generation: Int,
        modeOverride: TranslationPageMode? = null,
        allowFallback: Boolean = false,
    ): Int {
        val mode = modeOverride ?: appliedMode ?: return 0
        val settings = this.settings
        var applied = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (host.view !== view || host.url != pageUrl || host.documentEpoch != pageGeneration ||
                generationId != generation
            ) break
            val visible = runCatching { pageTranslator.visibleIds(view, mode) }.getOrDefault(emptySet())
            // A page can start with a full-screen hero and no text inside the prefetch window.
            // Translate a small first batch anyway so the page control appears and a later
            // scroll can continue, instead of leaving the session with no visible work.
            val candidates = if (allowFallback && visible.isEmpty() && applied == 0) {
                pending.values.take(24)
            } else {
                pending.values.filter { it.id in visible }
            }
            val batch = ArrayList<TranslationPageItem>()
            var batchChars = 0
            for (item in candidates) {
                if (batch.isNotEmpty() && (batch.size >= 24 || batchChars + item.text.length > 6000)) break
                batch += item
                batchChars += item.text.length
                if (batch.size >= 24) break
            }
            if (batch.isEmpty()) break
            batch.forEach { pending.remove(it.id) }
            hasPending = pending.isNotEmpty()
            try {
                val translated = translate(settings, batch.map { it.text })
                if (host.view !== view || host.url != pageUrl || host.documentEpoch != pageGeneration ||
                    generationId != generation
                ) break
                val pairs = batch.zip(translated) { item, text -> item.id to text }
                val appliedCount = if (mode == TranslationPageMode.COMPARE) {
                    pageTranslator.applyComparison(view, pairs, settings.targetLanguage)
                } else {
                    pageTranslator.apply(
                        view,
                        batch.zip(translated) { item, text -> Triple(item.id, item.version, text) },
                    )
                }
                if (mode == TranslationPageMode.REPLACE) {
                    // Rejected results may be the first batch. Collect their replacements
                    // before completion logic can mistake the temporarily empty native queue
                    // for an ended session and stop the watcher.
                    val fresh = pageTranslator.collectNewReplace(view)
                    fresh.updatePending(pending)
                    hasPending = pending.isNotEmpty()
                }
                pageTranslated = pageTranslated || appliedCount > 0
                if (appliedCount > 0) doneChars += batchChars
                progress = if (totalChars > 0) {
                    (doneChars * 100 / totalChars).coerceIn(0, 100)
                } else 0
                retryAt = 0L
                errorReported = false
                errorMessage = null
                applied += appliedCount
            } catch (error: Exception) {
                // Let JS requeue the current revision, including on tab-switch cancellation.
                // The original batch may now refer to an excluded node or an older revision.
                withContext(NonCancellable) {
                    runCatching { pageTranslator.requeueReplace(view, batch) }
                    if (host.view === view && host.url == pageUrl &&
                        generationId == generation
                    ) {
                        val fresh = runCatching { pageTranslator.collectNewReplace(view) }.getOrNull()
                        if (fresh != null && host.view === view && host.url == pageUrl &&
                            generationId == generation && host.documentEpoch == pageGeneration
                        ) {
                            fresh.updatePending(pending)
                            hasPending = pending.isNotEmpty()
                        }
                    }
                }
                throw error
            }
            delay(120)
        }
        return applied
    }

    fun onScroll() {
        if (!sessionActive || appliedMode == null || pending.isEmpty()) return
        if (job?.isActive == true) {
            scrollPending = true
            return
        }
        scrollJob?.cancel()
        scrollJob = scope.launch {
            delay(180)
            if (job?.isActive == true) {
                scrollPending = true
                return@launch
            }
            startVisibleTranslationJob()
        }
    }

    private fun queueVisibleTranslationAfterBatch() {
        if (!sessionActive || !scrollPending || pending.isEmpty()) return
        scrollPending = false
        scrollJob?.cancel()
        scrollJob = scope.launch {
            delay(40)
            startVisibleTranslationJob()
        }
    }

    private fun startVisibleTranslationJob() {
        if (!sessionActive) return
        if (SystemClock.uptimeMillis() < retryAt) return
        val view = host.view ?: return
        val mode = appliedMode ?: return
        if (pending.isEmpty() || busy) return
        val pageUrl = host.url
        val pageGeneration = host.documentEpoch
        val generation = generationId
        busy = true
        job = scope.launch {
            try {
                translateVisiblePageItems(view, pageUrl, pageGeneration, generation, modeOverride = mode)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                complete = false
                hasPending = pending.isNotEmpty()
                retryAt = SystemClock.uptimeMillis() + 2_000
                reportPageTranslationError(error)
            } finally {
                if (generationId == generation) {
                    busy = false
                    updateReplaceSessionComplete()
                    queueVisibleTranslationAfterBatch()
                }
            }
        }
    }

    fun restore() {
        host.ownerTabId?.let { tabSnapshots.remove(it) }
        val view = host.view
        val mode = appliedMode
        detach()
        closing = view != null
        if (view != null) scope.launch {
            runCatching {
                if (mode == TranslationPageMode.COMPARE) pageTranslator.restoreComparison(view)
                else pageTranslator.restore(view)
            }
        }
    }

    fun updateSettings(value: TranslationSettings) {
        settings = value
        settingsRepository.save(value)
    }

    fun clearError() { errorMessage = null }

    fun dismissSelection() {
        selectionJob?.cancel()
        selectionOriginal = null
        selectionTranslated = null
        selectionError = null
        selectionBusy = false
    }

    fun onPageStarted() {
        detach()
        selectionCaptureJob?.cancel()
        dismissSelection()
    }

    fun onTabChanging(nextTabId: String, liveTabIds: Set<String>) {
        host.ownerTabId?.takeIf { it != nextTabId }?.let(::saveForTab)
        detach()
        tabSnapshots.keys.retainAll(liveTabIds)
    }
}
