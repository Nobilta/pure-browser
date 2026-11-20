package com.mybrowser.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil

/** One payload budget shared by every HTTP task and segment in the process. */
internal class DownloadRateLimiter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private val lock = Any()
    private val turn = Mutex()
    @Volatile private var bytesPerSecond = 0L
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

    fun readSize(bufferSize: Int): Int {
        val limit = bytesPerSecond
        return if (limit == 0L) bufferSize else minOf(bufferSize.toLong(), (limit / 10).coerceAtLeast(1)).toInt()
    }

    /** Cancellation never leaves a reservation behind for the next task to repay. */
    suspend fun acquire(bytes: Int) {
        require(bytes >= 0)
        if (bytesPerSecond == 0L) return
        var remaining = bytes
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            turn.withLock {
                while (true) {
                    val wait = synchronized(lock) {
                        if (bytesPerSecond == 0L) return
                        val chunk = minOf(remaining, readSize(SLICE_BYTES))
                        val now = nanoTime()
                        val elapsed = (now - updatedAt).coerceAtLeast(0L)
                        updatedAt = now
                        // Allow at most 100 ms of idle credit, or one small slice at low rates.
                        val capacity = maxOf(SLICE_BYTES.toDouble(), bytesPerSecond / 10.0)
                        tokens = minOf(capacity, tokens + elapsed / 1_000_000_000.0 * bytesPerSecond)
                        if (tokens >= chunk) {
                            tokens -= chunk
                            remaining -= chunk
                            0L
                        } else {
                            ceil((chunk - tokens) * 1000.0 / bytesPerSecond).toLong().coerceIn(1L, 100L)
                        }
                    }
                    if (wait == 0L) break
                    waitMillis(wait)
                }
            }
        }
    }

    private companion object { const val SLICE_BYTES = 16 * 1024 }
}
