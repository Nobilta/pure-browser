package com.mybrowser.download

import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable

/** FIFO within each host, allowing unrelated hosts to progress when one host is full. */
internal class ConcurrencyBudget(private var total: Int, private var perHost: Int) {
    private val lock = Any()
    private var used = 0
    private val hosts = mutableMapOf<String, Int>()
    private class Ticket(val host: String) {
        val ready = CompletableDeferred<Lease>()
        var lease: Lease? = null
    }
    class Lease internal constructor(private val release: () -> Unit) : Closeable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    private val queue = ArrayDeque<Ticket>()
    init { require(total > 0 && perHost in 1..total) }

    /** Existing leases finish normally when the limit shrinks; queued work uses the new limit. */
    fun updateLimits(total: Int, perHost: Int) {
        require(total > 0 && perHost in 1..total)
        synchronized(lock) {
            this.total = total
            this.perHost = perHost
            dispatch()
        }
    }

    suspend fun acquire(host: String): Lease {
        val ticket = Ticket(host.lowercase(java.util.Locale.ROOT).trimEnd('.'))
        synchronized(lock) { queue.addLast(ticket); dispatch() }
        return try { ticket.ready.await() } catch (error: Throwable) {
            synchronized(lock) {
                queue.remove(ticket)
                ticket.lease?.close()
                dispatch()
            }
            throw error
        }
    }

    private fun dispatch() {
        while (used < total) {
            val next = queue.firstOrNull { hosts.getOrDefault(it.host, 0) < perHost } ?: break
            queue.remove(next)
            used++
            hosts[next.host] = hosts.getOrDefault(next.host, 0) + 1
            val lease = Lease { synchronized(lock) {
                used--
                val remaining = hosts.getValue(next.host) - 1
                if (remaining == 0) hosts.remove(next.host) else hosts[next.host] = remaining
                dispatch()
            } }
            next.lease = lease
            next.ready.complete(lease)
        }
    }
}
