package com.mybrowser.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

/** One payload budget shared by every HTTP task and segment in the process. */
internal class DownloadRateLimiter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private var bytesPerSecond = 0L
    private var tokens = 0.0
    private var updatedAt = nanoTime()

    fun setLimit(bytesPerSecond: Long) {
        require(bytesPerSecond >= 0)
        synchronized(lock) {
            if (this.bytesPerSecond == bytesPerSecond) return
            this.bytesPerSecond = bytesPerSecond
            tokens = 0.0
            updatedAt = nanoTime()
        }
    }

    /** Cancellation never leaves a reservation behind for the next task to repay. */
    suspend fun acquire(bytes: Int) {
        require(bytes >= 0)
        var remaining = bytes
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val chunk = minOf(remaining, SLICE_BYTES)
            val wait = synchronized(lock) {
                if (bytesPerSecond == 0L) return
                val now = nanoTime()
                val elapsed = (now - updatedAt).coerceAtLeast(0L)
                updatedAt = now
                // Allow at most 100 ms of idle credit, or one small slice at low rates.
                val capacity = maxOf(SLICE_BYTES.toDouble(), bytesPerSecond / 10.0)
                tokens = minOf(capacity, tokens + elapsed / 1_000_000_000.0 * bytesPerSecond)
                if (tokens >= chunk) {
                    tokens -= chunk
                    0L
                } else {
                    ceil((chunk - tokens) * 1000.0 / bytesPerSecond).toLong().coerceIn(1L, 100L)
                }
            }
            if (wait == 0L) remaining -= chunk else waitMillis(wait)
        }
    }

    private companion object { const val SLICE_BYTES = 16 * 1024 }
}
