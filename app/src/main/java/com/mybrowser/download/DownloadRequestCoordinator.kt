package com.mybrowser.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-document download confirmation and abuse limits. Only the selected tab publishes
 * UI state; switching back restores that document's budget, rejections and blocked list.
 * Main-frame navigation or closing a tab drops its requests and credentials. Nothing
 * here is persisted; the session ViewModel only retains it across Activity recreation.
 * Call on the UI thread, like WebView's download/navigation callbacks.
 */
class DownloadRequestCoordinator(private val handler: DownloadHandler) {
    data class Request(
        val url: String,
        val filename: String,
        val mimeType: String?,
        val userAgent: String?,
        val contentDisposition: String?,
        val referer: String?,
        val isPrivate: Boolean,
        val cookieHeader: String?,
        val sourceOrigin: String?,
        val contentLength: Long? = null,
    ) {
        val identity: String get() = url.substringBefore('#')
    }

    enum class BlockedReason { REJECTED, LIMIT, BUDGET, EXISTING }
    data class Confirmation(val request: Request, val isNewCopy: Boolean)
    data class BlockedEntry(val request: Request, val reason: BlockedReason) {
        val identity: String get() = request.identity
    }
    sealed class SubmitResult {
        data class Confirm(val request: Request) : SubmitResult()
        data class Existing(val id: Long, val status: DownloadStatus, val firstForPage: Boolean) : SubmitResult()
        /** A direct user action gets local feedback, never automatic sheet navigation. */
        data class ExistingNotice(val status: DownloadStatus) : SubmitResult()
        data object ConfirmationBusy : SubmitResult()
        data class Suppressed(val blockedCount: Int) : SubmitResult()
        data class Intercepted(val blockedCount: Int, val firstNotice: Boolean) : SubmitResult()
    }

    private class Document {
        var pending: Confirmation? = null
        var newCopy = false
        var fromUser = false
        var blocked = emptyList<BlockedEntry>()
        val rejected = mutableSetOf<String>()
        val reported = mutableSetOf<String>()
        var budget = MAX_IMPLICIT_DIALOGS
        var noticed = false
        var overflowNoticed = false
        var overflow = 0
    }
    private val documents = mutableMapOf<String, Document>()
    private var tabId = "initial"
    private val document: Document get() = documents.getOrPut(tabId) { Document() }
    private val _pending = MutableStateFlow<Confirmation?>(null)
    val pending: StateFlow<Confirmation?> = _pending.asStateFlow()
    private val _blocked = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val blocked: StateFlow<List<BlockedEntry>> = _blocked.asStateFlow()
    private val _overflow = MutableStateFlow(0)
    /** Number of discarded events, not distinct URLs. Saturates without retaining their credentials. */
    val overflow = _overflow.asStateFlow()

    fun selectTab(id: String) { tabId = id; publish() }
    fun startDocument(id: String) {
        documents.remove(id)
        if (id == tabId) publish()
    }
    fun retainTabs(ids: Set<String>) {
        documents.keys.retainAll(ids)
        if (tabId in ids) publish() else { _pending.value = null; _blocked.value = emptyList(); _overflow.value = 0 }
    }
    fun resetTransientState() { documents.clear(); publish() }
    private fun publish() {
        _pending.value = document.pending
        _blocked.value = document.blocked
        _overflow.value = document.overflow
    }

    fun submit(request: Request): SubmitResult = submit(request, explicit = false)

    /** Long-press actions bypass page suppression/budgets, but still require confirmation. */
    fun submitFromUser(request: Request): SubmitResult = submit(request, explicit = true, fromUser = true)

    private fun submit(request: Request, explicit: Boolean, fromUser: Boolean = false): SubmitResult {
        val state = document
        val identity = request.identity
        if (state.pending?.request?.let { it.identity == identity && it.isPrivate == request.isPrivate } == true) {
            return SubmitResult.Suppressed(state.blocked.size)
        }
        if (!explicit && identity in state.rejected) return SubmitResult.Suppressed(state.blocked.size)
        val existing = handler.existingTaskFor(identity, request.isPrivate)
        existing?.let { (id, status) ->
            if (!explicit || status != DownloadStatus.COMPLETED) {
                if (fromUser) return SubmitResult.ExistingNotice(status)
                // Keep a visible action even after the one-time "view task" routing.
                // Retrying uses this request's current credentials/headers, not the old task's.
                recordBlocked(request, BlockedReason.EXISTING)
                val first = identity !in state.reported && state.reported.size < MAX_REMEMBERED_IDENTITIES
                if (first) state.reported.add(identity)
                return if (first || explicit) SubmitResult.Existing(id, status, true)
                    else SubmitResult.Suppressed(state.blocked.size)
            }
        }
        if (!explicit && state.blocked.any { it.identity == identity }) return SubmitResult.Suppressed(state.blocked.size)
        if (state.pending != null) {
            if (fromUser) return SubmitResult.ConfirmationBusy
            recordBlocked(request, BlockedReason.LIMIT)
            return interceptNotice()
        }
        if (!explicit && state.budget <= 0) {
            recordBlocked(request, BlockedReason.BUDGET)
            return interceptNotice()
        }
        if (!explicit) state.budget--
        state.pending = Confirmation(request, existing?.second == DownloadStatus.COMPLETED)
        state.newCopy = explicit
        state.fromUser = fromUser
        if (fromUser) state.blocked = state.blocked.filterNot { it.identity == identity }
        publish()
        return SubmitResult.Confirm(request)
    }

    fun confirmPending(): DownloadHandler.EnqueueOutcome {
        val state = document
        val request = state.pending?.request ?: return DownloadHandler.EnqueueOutcome.Rejected
        val newCopy = state.newCopy
        state.pending = null
        state.newCopy = false
        state.fromUser = false
        publish()
        return handler.enqueueOrGetExisting(
            request.url, request.userAgent, request.contentDisposition, request.mimeType,
            referer = request.referer, isPrivate = request.isPrivate, cookieHeader = request.cookieHeader,
            newCopy = newCopy,
        )
    }

    fun rejectPending() {
        val state = document
        val request = state.pending?.request ?: return
        val fromUser = state.fromUser
        state.pending = null
        state.newCopy = false
        state.fromUser = false
        // Stop remembering new identities at the cap; automatic requests still pass
        // through the document budget. No unbounded set of private URLs is retained.
        if (state.rejected.size < MAX_REMEMBERED_IDENTITIES) state.rejected.add(request.identity)
        if (!fromUser) recordBlocked(request, BlockedReason.REJECTED)
        publish()
    }

    /** Explicit authority exists only during this call; no token can leak to a later page event. */
    fun requestBlocked(identity: String, cookies: (String) -> String?): SubmitResult? {
        val entry = document.blocked.firstOrNull { it.identity == identity } ?: return null
        if (document.pending != null) return SubmitResult.Suppressed(document.blocked.size)
        document.blocked = document.blocked.filterNot { it.identity == identity }
        document.rejected.remove(identity)
        publish()
        return submit(entry.request.copy(cookieHeader = cookies(entry.request.url)), explicit = true)
    }

    fun removeBlocked(identity: String) {
        document.blocked = document.blocked.filterNot { it.identity == identity }
        publish()
    }

    private fun interceptNotice(): SubmitResult.Intercepted {
        val state = document
        val first = !state.noticed || (state.overflow > 0 && !state.overflowNoticed)
        state.noticed = true
        if (state.overflow > 0) state.overflowNoticed = true
        return SubmitResult.Intercepted(state.blocked.size, first)
    }

    private fun recordBlocked(request: Request, reason: BlockedReason) {
        val state = document
        if (state.blocked.any { it.identity == request.identity }) return
        if (state.blocked.size >= MAX_BLOCKED_ENTRIES) {
            state.overflow = (state.overflow.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else state.blocked = state.blocked + BlockedEntry(request, reason)
        publish()
    }

    companion object {
        const val MAX_BLOCKED_ENTRIES = 5
        private const val MAX_IMPLICIT_DIALOGS = 3
        private const val MAX_REMEMBERED_IDENTITIES = 32
    }
}
