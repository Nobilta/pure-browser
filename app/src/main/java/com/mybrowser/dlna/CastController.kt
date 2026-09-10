package com.mybrowser.dlna

import android.content.Context
import com.mybrowser.R
import android.util.Log
import com.mybrowser.media.MediaSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Owns discovery state and the currently-selected renderer.
 *
 * SSDP and SOAP work runs off the main thread. Publishing one immutable state object through
 * StateFlow keeps the DLNA layer independent from Compose and avoids writing Snapshot state
 * from an IO callback. The small compatibility accessors are useful to non-UI callers.
 */
class CastController(
    context: Context,
    private val scope: CoroutineScope,
    private val readStatus: suspend (DlnaDevice) -> Result<AvTransport.PlaybackStatus> = AvTransport::status,
    private val sendMedia: suspend (MediaSniffer.Candidate, DlnaDevice) -> Result<Unit> = { candidate, device ->
        AvTransport.playMedia(device, candidate.url, candidate.displayLabel(context.resources), candidate.kind)
    },
) {
    private val appContext = context.applicationContext

    data class State(
        val devices: List<DlnaDevice> = emptyList(),
        val isSearching: Boolean = false,
        val connected: DlnaDevice? = null,
        val pendingDevice: DlnaDevice? = null,
        val isCasting: Boolean = false,
        val lastError: String? = null,
        val playback: AvTransport.PlaybackStatus? = null,
        val statusUnavailable: Boolean = false,
        val isControlling: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val devices: List<DlnaDevice> get() = _state.value.devices
    val isSearching: Boolean get() = _state.value.isSearching
    val connected: DlnaDevice? get() = _state.value.connected
    val lastError: String? get() = _state.value.lastError

    private val lock = Any()
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var castJob: Job? = null
    private var castGeneration = 0L
    private var controlJob: Job? = null
    private var pollJob: Job? = null
    private var pollGeneration = 0L
    private var visible = false
    private var closed = false

    /** Restarting a search keeps devices already found: they rarely disappear mid-session. */
    fun search() {
        val generation: Long
        synchronized(lock) {
            if (closed || _state.value.isSearching) return
            searchJob?.cancel()
            generation = ++searchGeneration
            publish(_state.value.copy(isSearching = true, lastError = null))
            // Keep creation and publication under the same lock. Without this, cancel()
            // could run in the tiny window after launch() but before searchJob was stored,
            // leaving a discovery coroutine alive after the picker was dismissed.
            searchJob = scope.launch {
                try {
                    SsdpDiscovery.search()
                        .catch { error ->
                            Log.w(TAG, "discovery failed", error)
                            synchronized(lock) {
                                if (generation == searchGeneration) {
                                    publish(_state.value.copy(lastError = appContext.getString(R.string.cast_network_error)))
                                }
                            }
                        }
                        .collect { reply ->
                            // A handful of devices is not worth fan-out concurrency; serial
                            // description fetches keep the picker stable while it is open.
                            val device = DeviceDescription.fetch(reply) ?: return@collect
                            synchronized(lock) {
                                if (generation != searchGeneration ||
                                    _state.value.devices.any { it.usn == device.usn || it.controlUrl == device.controlUrl }
                                ) {
                                    return@synchronized
                                }
                                publish(_state.value.copy(devices = _state.value.devices + device))
                            }
                        }
                } finally {
                    synchronized(lock) {
                        if (generation == searchGeneration) {
                            publish(_state.value.copy(isSearching = false))
                            searchJob = null
                        }
                    }
                }
            }
        }
    }

    fun cast(candidate: MediaSniffer.Candidate, device: DlnaDevice, onResult: (String) -> Unit) {
        val job: Job
        synchronized(lock) {
            if (closed || _state.value.isCasting || _state.value.isControlling) return
            stopPolling()
            val generation = ++castGeneration
            publish(_state.value.copy(isCasting = true, pendingDevice = device, lastError = null))
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = try { sendMedia(candidate, device) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    val message = if (result.isSuccess) appContext.getString(R.string.ui_casting_to, device.displayName(appContext.resources))
                        else appContext.getString(R.string.cast_network_error)
                    synchronized(lock) {
                        if (generation != castGeneration) return@launch
                        publish(_state.value.copy(isCasting = false, pendingDevice = null,
                            connected = if (result.isSuccess) device else _state.value.connected,
                            playback = if (result.isSuccess) null else _state.value.playback,
                            statusUnavailable = false,
                            lastError = if (result.isFailure) message else null))
                    }
                    onResult(message)
                    refreshStatus()
                } finally {
                    synchronized(lock) {
                        if (generation == castGeneration) {
                            castJob = null
                            publish(_state.value.copy(isCasting = false, pendingDevice = null))
                        }
                    }
                }
            }
            castJob = job
        }
        job.start()
    }

    fun setVisible(value: Boolean) {
        synchronized(lock) { visible = value }
        if (value) refreshStatus() else synchronized(lock) { stopPolling() }
    }

    fun refreshStatus() {
        val job: Job
        synchronized(lock) {
            stopPolling()
            if (closed || !visible || connected == null || _state.value.isCasting || _state.value.isControlling) return
            val epoch = pollGeneration
            job = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    val device = synchronized(lock) { _state.value.connected } ?: break
                    val result = try { readStatus(device) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    synchronized(lock) {
                        if (epoch != pollGeneration || _state.value.connected != device) return@launch
                        publish(_state.value.copy(playback = result.getOrNull(), statusUnavailable = result.isFailure))
                    }
                    delay(3_000)
                }
            }
            pollJob = job
        }
        job.start()
    }

    private fun stopPolling() {
        pollGeneration++
        pollJob?.cancel()
        pollJob = null
    }

    fun pause(onResult: (String) -> Unit) = transport(onResult) { AvTransport.pause(it) }
    fun resume(onResult: (String) -> Unit) = transport(onResult) { AvTransport.play(it) }
    fun stop(onResult: (String) -> Unit) = transport(onResult, disconnect = true) { AvTransport.stop(it) }
    fun setVolume(volume: Int, onResult: (String) -> Unit = {}) = transport(onResult) { AvTransport.setVolume(it, volume.coerceIn(0, 100)) }
    fun seek(seconds: Long, onResult: (String) -> Unit = {}) {
        val duration = _state.value.playback?.durationSeconds?.takeIf { it > 0 } ?: return
        transport(onResult) { AvTransport.seek(it, seconds.coerceIn(0, duration)) }
    }

    /** Forgetting control leaves receiver playback alone; Stop is a separate action. */
    fun disconnect() {
        synchronized(lock) {
            castGeneration++
            castJob?.cancel(); castJob = null
            controlJob?.cancel(); controlJob = null
            stopPolling()
            publish(_state.value.copy(connected = null, playback = null, isControlling = false,
                isCasting = false, pendingDevice = null, statusUnavailable = false, lastError = null))
        }
    }

    private fun transport(onResult: (String) -> Unit, disconnect: Boolean = false,
        action: suspend (DlnaDevice) -> Result<Unit>) {
        val job: Job
        synchronized(lock) {
            val device = connected ?: return
            if (closed || _state.value.isCasting || _state.value.isControlling) return
            val generation = ++castGeneration
            stopPolling()
            publish(_state.value.copy(isControlling = true, lastError = null))
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = try { action(device) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    synchronized(lock) {
                        if (generation != castGeneration || _state.value.connected != device) return@launch
                        publish(_state.value.copy(isControlling = false,
                            connected = if (result.isSuccess && disconnect) null else device,
                            playback = if (result.isSuccess) null else _state.value.playback,
                            lastError = if (result.isFailure) appContext.getString(R.string.cast_control_failed) else null))
                    }
                    if (result.isFailure) onResult(appContext.getString(R.string.cast_control_failed))
                    else if (disconnect) onResult(appContext.getString(R.string.ui_casting_stopped))
                } finally {
                    synchronized(lock) {
                        if (generation == castGeneration) {
                            controlJob = null
                            publish(_state.value.copy(isControlling = false))
                        }
                    }
                    refreshStatus()
                }
            }
            controlJob = job
        }
        job.start()
    }

    fun cancel() {
        synchronized(lock) {
            ++searchGeneration
            searchJob?.cancel()
            searchJob = null
            publish(_state.value.copy(isSearching = false))
        }
    }

    fun close() {
        synchronized(lock) { closed = true; visible = false }
        cancel()
        disconnect()
    }

    private fun publish(next: State) {
        _state.value = next.copy(devices = next.devices.toList())
    }

    private companion object {
        const val TAG = "CastController"
    }
}
