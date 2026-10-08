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
        /**
         * Bumped every time the device reports a position, even when the report is identical to
         * the previous one. A widget that has to react to "the device has spoken" cannot key off
         * the value itself: a paused renderer, or one that refused a seek, reports the same
         * position forever, and a value-keyed effect would never run again.
         */
        val playbackRevision: Long = 0,
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

    /**
     * Sends a stream to a renderer.
     *
     * [startAtSeconds] is where the phone was when the stream was picked up, or null to let the
     * renderer start at the beginning. It is applied after the renderer accepts the URI: it has to
     * be playing before it can be told to jump, and a renderer that never gets there is told apart
     * from one that refuses, by the message the caller gets.
     */
    fun cast(
        candidate: MediaSniffer.Candidate,
        device: DlnaDevice,
        startAtSeconds: Long? = null,
        onResult: (String) -> Unit,
    ) {
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
                    // Only worth doing once the renderer has taken the stream.
                    if (result.isSuccess) handOffStartPosition(generation, device, startAtSeconds, onResult)
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
                        publish(_state.value.copy(
                            playback = result.getOrNull(),
                            statusUnavailable = result.isFailure,
                            playbackRevision = _state.value.playbackRevision + 1,
                        ))
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
    /**
     * Seeks, or says why it cannot.
     *
     * Returning quietly here would read as a dead control: the panel only offers the position
     * slider when the device reports a duration, so "no duration" is the one case where the answer
     * has to come from the message rather than from the panel being absent.
     */
    fun seek(seconds: Long, onResult: (String) -> Unit = {}) {
        val duration = _state.value.playback?.durationSeconds?.takeIf { it > 0 }
        if (duration == null) {
            onResult(appContext.getString(R.string.cast_seek_unknown))
            return
        }
        transport(onResult, failureMessage = R.string.cast_seek_failed) {
            AvTransport.seek(it, seconds.coerceIn(0, duration))
        }
    }

    /** Jumps [bySeconds] from where the device says it is; skipped forward, it stops at the end. */
    fun skip(bySeconds: Long, onResult: (String) -> Unit = {}) {
        val position = _state.value.playback?.positionSeconds
        val duration = _state.value.playback?.durationSeconds?.takeIf { it > 0 }
        if (position == null || duration == null) {
            onResult(appContext.getString(R.string.cast_seek_unknown))
            return
        }
        seek(position + bySeconds, onResult)
    }

    /**
     * Where a cast should start so the renderer continues what the phone was playing.
     *
     * Null when there is nothing worth handing over: no measurable position, a stream the page
     * reports no duration for (a live stream, where the renderer's own edge is the right place to
     * be), or so little played that starting over is the same thing. It lives here as a plain
     * function so the rule can be tested without a renderer on the other end.
     */
    fun startPositionFor(positionSeconds: Double, durationSeconds: Double): Long? {
        if (!positionSeconds.isFinite() || !durationSeconds.isFinite()) return null
        if (durationSeconds <= 0.0) return null
        val position = positionSeconds.coerceIn(0.0, durationSeconds)
        if (position < MIN_HANDOFF_SECONDS) return null
        return position.toLong()
    }

    /**
     * Tells the renderer to jump to where the phone was, once it is actually playing.
     *
     * A renderer refuses Seek while its transport is still settling, so the wait is for a state
     * that is past the transition rather than for a fixed pause: the common case is already playing
     * on the first check and costs one round trip. Paused counts — seeking a paused renderer is
     * legal and lands where it is asked to — and someone who pauses right after casting would
     * otherwise be told their device cannot skip. A device that never leaves the transition, or
     * refuses the seek, hears about it once: starting from the beginning is a fine outcome as long
     * as the user is told why.
     *
     * [generation] is the cast this position belongs to. The wait can outlive the send: sending a
     * second video to the same device clears `isCasting`, so a device-only check would let the old
     * wait see its own device connected and seek the *new* video to the old video's position — or
     * undo a jump the user has made in the meantime. The generation is re-read after the status
     * round trip and again immediately before the seek, so neither can slip through.
     */
    private suspend fun handOffStartPosition(
        generation: Long,
        device: DlnaDevice,
        startAtSeconds: Long?,
        onResult: (String) -> Unit,
    ) {
        val target = startAtSeconds?.takeIf { it > 0 } ?: return
        fun current() = synchronized(lock) { !closed && castGeneration == generation && _state.value.connected == device }
        repeat(START_POSITION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(START_POSITION_RETRY_MILLIS)
            // A cast that was replaced or closed while waiting must not be told to jump.
            if (!current()) return
            if (readStatus(device).getOrNull()?.transportState in SEEKABLE_STATES) {
                if (!current()) return
                if (AvTransport.seek(device, target).isFailure) onResult(appContext.getString(R.string.cast_seek_failed))
                return
            }
        }
        onResult(appContext.getString(R.string.cast_seek_failed))
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
        failureMessage: Int = R.string.cast_control_failed,
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
                            lastError = if (result.isFailure) appContext.getString(failureMessage) else null))
                    }
                    if (result.isFailure) {
                        // The user gets a sentence; whoever has to diagnose it later needs the
                        // device's own words, which for a refused seek are a SOAP fault such as
                        // "710 Seek mode not supported".
                        Log.w(TAG, "Control command failed: ${result.exceptionOrNull()?.message}")
                        onResult(appContext.getString(failureMessage))
                    } else if (disconnect) onResult(appContext.getString(R.string.ui_casting_stopped))
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

        /**
         * Below this there is nothing to continue: a cast that picks up three seconds in is
         * indistinguishable from one that starts at the beginning, and asking for it anyway turns
         * a device that cannot seek into an error message for no gain.
         */
        const val MIN_HANDOFF_SECONDS = 10.0

        /** Transport states a seek is legal in: past the transition, playing or paused. */
        val SEEKABLE_STATES = setOf("PLAYING", "PAUSED_PLAYBACK")

        /** How long to give a renderer to leave its transition before giving up on the start position. */
        const val START_POSITION_ATTEMPTS = 8
        const val START_POSITION_RETRY_MILLIS = 750L
    }
}
