package com.mybrowser.devtools

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Network details and bodies for one tab.
 *
 * The list itself lives in [com.mybrowser.core.NetworkLogStore]; this store only holds the
 * expensive parts. Bodies are charged against [DevToolsBudgets.BODY_CACHE_BYTES_PER_TAB] and
 * older bodies are replaced by an "evicted" state while their overview and headers stay
 * readable. Removing a record frees its body at once.
 */
internal class NetworkDetailStore {
    private val lock = Any()
    private val details = LinkedHashMap<Long, NetworkRequestDetail>()
    private val chargedBytes = HashMap<Long, Long>()
    private var totalBodyBytes = 0L
    private var visible = false
    private var scheduled = false
    private val handler = Handler(Looper.getMainLooper())
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private val publish = Runnable {
        synchronized(lock) {
            scheduled = false
            if (visible) mutableRevision.value++
        }
    }

    fun setVisible(value: Boolean) = synchronized(lock) {
        visible = value
        handler.removeCallbacks(publish)
        scheduled = false
        if (visible) mutableRevision.value++
    }

    fun clear() = synchronized(lock) {
        details.clear()
        chargedBytes.clear()
        totalBodyBytes = 0L
        handler.removeCallbacks(publish)
        scheduled = false
        mutableRevision.value++
    }

    fun put(detail: NetworkRequestDetail) = synchronized(lock) {
        details[detail.id] = detail
        reweigh(detail.id)
        trimBodies()
        schedule()
    }

    fun update(id: Long, transform: (NetworkRequestDetail) -> NetworkRequestDetail) = synchronized(lock) {
        val existing = details[id] ?: return@synchronized
        details[id] = transform(existing)
        reweigh(id)
        trimBodies()
        schedule()
    }

    fun updateAll(transform: (NetworkRequestDetail) -> NetworkRequestDetail) = synchronized(lock) {
        var changed = false
        for ((id, detail) in details) {
            val updated = transform(detail)
            if (updated !== detail) {
                details[id] = updated
                reweigh(id)
                changed = true
            }
        }
        if (changed) {
            trimBodies()
            schedule()
        }
    }

    /** Stop leaves in-flight reads without a final body event; they must not stay "collecting". */
    fun abortCollecting() = synchronized(lock) {
        var changed = false
        for ((id, detail) in details) {
            val request = if (detail.requestBody.state == BodyState.COLLECTING)
                detail.requestBody.copy(state = BodyState.TRUNCATED, reason = BodyReason.ABORTED) else detail.requestBody
            val response = if (detail.responseBody.state == BodyState.COLLECTING)
                detail.responseBody.copy(state = BodyState.TRUNCATED, reason = BodyReason.ABORTED) else detail.responseBody
            if (request != detail.requestBody || response != detail.responseBody) {
                details[id] = detail.copy(requestBody = request, responseBody = response)
                reweigh(id)
                changed = true
            }
        }
        if (changed) {
            trimBodies()
            schedule()
        }
    }

    fun remove(id: Long) = synchronized(lock) {
        details.remove(id)
        totalBodyBytes -= chargedBytes.remove(id) ?: 0L
        if (totalBodyBytes < 0L) totalBodyBytes = 0L
        schedule()
    }

    fun get(id: Long): NetworkRequestDetail? = synchronized(lock) { details[id] }

    fun snapshot(): List<NetworkRequestDetail> = synchronized(lock) { details.values.toList() }

    private fun schedule() {
        if (visible && !scheduled) {
            scheduled = true
            handler.postDelayed(publish, 150)
        }
    }

    private fun reweigh(id: Long) {
        val detail = details[id] ?: return
        val previous = chargedBytes[id] ?: 0L
        val next = charged(detail.requestBody) + charged(detail.responseBody)
        chargedBytes[id] = next
        totalBodyBytes += next - previous
        if (totalBodyBytes < 0L) totalBodyBytes = 0L
    }

    private fun charged(body: BodyPreview): Long = when (body.state) {
        // These states keep no preview text; a binary size or an unsupported reason is
        // diagnostic metadata, not something the cache has to account for.
        BodyState.EVICTED, BodyState.UNAVAILABLE, BodyState.NOT_COLLECTED,
        BodyState.FAILED, BodyState.EMPTY -> 0L
        else -> maxOf(body.bytes.coerceAtLeast(0L), body.text.length.toLong())
    }

    private fun trimBodies() {
        if (totalBodyBytes <= DevToolsBudgets.BODY_CACHE_BYTES_PER_TAB) return
        for (id in details.keys.toList()) {
            if (totalBodyBytes <= DevToolsBudgets.BODY_CACHE_BYTES_PER_TAB) return
            val candidate = details[id] ?: continue
            if (candidate.requestBody.state == BodyState.EVICTED &&
                candidate.responseBody.state == BodyState.EVICTED) continue
            details[id] = candidate.copy(
                requestBody = if (candidate.requestBody.state == BodyState.EVICTED) candidate.requestBody
                    else evictedPreview(candidate.requestBody),
                responseBody = if (candidate.responseBody.state == BodyState.EVICTED) candidate.responseBody
                    else evictedPreview(candidate.responseBody),
            )
            reweigh(id)
        }
    }

    private fun evictedPreview(body: BodyPreview): BodyPreview = body.copy(
        state = BodyState.EVICTED,
        text = "",
        bytes = 0,
        chars = 0,
        reason = BodyReason.EVICTED,
    )
}
