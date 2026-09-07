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

/**
 * Owns discovery state and the currently-selected renderer.
 *
 * SSDP and SOAP work runs off the main thread. Publishing one immutable state object through
 * StateFlow keeps the DLNA layer independent from Compose and avoids writing Snapshot state
 * from an IO callback. The small compatibility accessors are useful to non-UI callers.
 */
class CastController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext

    data class State(
        val devices: List<DlnaDevice> = emptyList(),
        val isSearching: Boolean = false,
        val connected: DlnaDevice? = null,
        val lastError: String? = null,
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

    /** Restarting a search keeps devices already found: they rarely disappear mid-session. */
    fun search() {
        val generation: Long
        synchronized(lock) {
            if (_state.value.isSearching) return
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
                                    _state.value.devices.any { it.usn == device.usn }
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
        scope.launch {
            synchronized(lock) { publish(_state.value.copy(connected = device, lastError = null)) }
            val result = AvTransport.playMedia(
                device = device,
                url = candidate.url,
                title = candidate.displayLabel(appContext.resources),
                isStream = candidate.isStream,
            )
            result.fold(
                onSuccess = { onResult(appContext.getString(R.string.ui_casting_to, device.displayName(appContext.resources))) },
                onFailure = { error ->
                    Log.w(TAG, "cast failed", error)
                    val message = appContext.getString(R.string.cast_network_error)
                    synchronized(lock) {
                        publish(_state.value.copy(connected = null, lastError = message))
                    }
                    onResult(message)
                },
            )
        }
    }

    fun pause(onResult: (String) -> Unit) = transport(onResult) { AvTransport.pause(it) }

    fun resume(onResult: (String) -> Unit) = transport(onResult) { AvTransport.play(it) }

    /** Also drops the connection: after Stop the renderer has no transport URI left. */
    fun stop(onResult: (String) -> Unit) {
        val device = connected ?: return
        scope.launch {
            AvTransport.stop(device)
            synchronized(lock) { publish(_state.value.copy(connected = null)) }
            onResult(appContext.getString(R.string.ui_casting_stopped))
        }
    }

    fun setVolume(volume: Int) {
        val device = connected ?: return
        scope.launch { AvTransport.setVolume(device, volume.coerceIn(0, 100)) }
    }

    private fun transport(
        onResult: (String) -> Unit,
        action: suspend (DlnaDevice) -> Result<Unit>,
    ) {
        val device = connected ?: return
        scope.launch {
            action(device).onFailure {
                Log.w(TAG, "transport action failed", it)
                onResult(appContext.getString(R.string.cast_network_error))
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            ++searchGeneration
            searchJob?.cancel()
            searchJob = null
            publish(_state.value.copy(isSearching = false))
        }
    }

    private fun publish(next: State) {
        _state.value = next.copy(devices = next.devices.toList())
    }

    private companion object {
        const val TAG = "CastController"
    }
}
