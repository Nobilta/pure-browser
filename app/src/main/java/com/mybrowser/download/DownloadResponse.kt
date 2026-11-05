package com.mybrowser.download

import java.io.Closeable
import java.io.InputStream
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An already opened response: confirmation transfers ownership without another HTTP request. */
class DownloadResponse(val body: InputStream, val length: Long, val network: Network? = null) : Closeable {
    enum class Unavailable { NETWORK, EXPIRED }
    private val _unavailable = MutableStateFlow<Unavailable?>(null)
    val unavailable = _unavailable.asStateFlow()
    private var timeout: Job? = null
    private var held = false
    private var started = false
    private var closed = false

    @Synchronized internal fun hold(scope: CoroutineScope, timeoutMillis: Long = 30_000) {
        if (held || closed || started) return
        held = true
        timeout = scope.launch { delay(timeoutMillis); discard(Unavailable.EXPIRED) }
    }

    /** Starting and expiry compete under one lock; expiry must never close an active transfer. */
    @Synchronized internal fun start(): Boolean {
        if (closed || started) return false
        started = true
        timeout?.cancel()
        return true
    }

    internal fun discard(reason: Unavailable) {
        synchronized(this) {
            if (closed || started) return
            _unavailable.value = reason
            close()
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        timeout?.cancel()
        runCatching { body.close() }
    }
}
