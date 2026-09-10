package com.mybrowser.download

import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable

/** FIFO within each host, allowing unrelated hosts to progress when one host is full. */
internal class ConcurrencyBudget(private val total: Int, private val perHost: Int) {
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
