package com.mybrowser.media

import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds what has been sniffed on the current page.
 *
 * WebView invokes [add] from a Chromium worker thread while Compose renders the cast
 * affordance on the main thread. Compose Snapshot state is not an event bus for those
 * threads: a worker write can race a composition and fail while applying a snapshot. A
 * single immutable [Snapshot] published through StateFlow gives readers a coherent view
 * and makes every update atomic without exposing a mutable list.
 */
class MediaCandidateStore {

    data class Snapshot(
        val candidates: List<MediaSniffer.Candidate> = emptyList(),
        val playingVideoUrl: String? = null,
        /** The one candidate that best matches the active media element. */
        val playingCandidateUrl: String? = null,
        val playingVideoHints: List<String> = emptyList(),
    ) {
        val count: Int get() = candidates.size

        val preferredCandidate: MediaSniffer.Candidate?
            get() = playingCandidateUrl
                ?.let { url -> candidates.firstOrNull { it.url == url } }
                ?: candidates.firstOrNull()

        val playingCandidateUrls: Set<String>
            get() = playingCandidateUrl?.let(::setOf).orEmpty()
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()
    @Volatile
    var pageGeneration: Long = 0
        private set

    // Compatibility accessors keep non-Compose callers and existing integrations small;
    // they are read-only StateFlow snapshots, never independently mutable state.
    val candidates: List<MediaSniffer.Candidate> get() = _state.value.candidates
    val playingVideoUrl: String? get() = _state.value.playingVideoUrl
    val preferredCandidate: MediaSniffer.Candidate? get() = _state.value.preferredCandidate
    val playingCandidateUrls: Set<String> get() = _state.value.playingCandidateUrls
    val count: Int get() = _state.value.count

    /**
     * @return true if this was a new candidate. Adding is idempotent by URL — a player
     * re-requesting its manifest is normal and must not produce duplicates.
     */
    @Synchronized
    fun add(candidate: MediaSniffer.Candidate): Boolean {
        val clean = sanitizeCandidate(candidate) ?: return false
        val current = _state.value
        if (current.candidates.any { it.url == clean.url }) return false

        // Keep progressive URLs even when a page also exposes HLS/DASH. A media element may
        // legitimately be playing the progressive source, and dropping it would make the
        // active-stream marker impossible to resolve. The hard cap below still prevents an
        // ad-heavy page from growing the picker without bound.
        val expanded = current.candidates + clean
        // A player signal can arrive before its network request. Re-evaluate the single
        // active match whenever a late candidate is added, then sort with that match first.
        val playing = findPlayingCandidate(expanded, current.playingVideoHints)?.url
        val bounded = boundCandidates(expanded, playing)
        publish(
            current.copy(
                candidates = bounded.first,
                // Do not retain a pointer to a candidate that was evicted by the cap. In
                // particular, a long ad-heavy page may discover the active stream late.
                playingCandidateUrl = bounded.second,
            ),
        )
        return true
    }

    /** A request that began on an earlier page must not publish after navigation. */
    @Synchronized
    fun add(candidate: MediaSniffer.Candidate, expectedGeneration: Long): Boolean {
        if (expectedGeneration != pageGeneration) return false
        return add(candidate)
    }

    /** Called on every main-frame navigation: candidates belong to one page only. */
    @Synchronized
    fun clear() {
        pageGeneration++
        if (_state.value != Snapshot()) _state.value = Snapshot()
    }

    /** A loaded currentSrc can recover a candidate missed by the network interceptor. */
    @Synchronized
    fun updatePlayback(signal: MediaPlaybackTracker.Signal) {
        if (signal.hasVideo) {
            signal.sourceUrl?.let { source -> MediaSniffer.inspect(source, signal.frameUrl)?.let(::add) }
        }
        setPlayingVideosLocked(if (signal.isPlaying) signal.urls else emptyList())
    }

    /** Records one URL hint for callers that only have currentSrc. */
    @Synchronized
    fun setPlayingVideo(url: String?) {
        setPlayingVideosLocked(url?.let(::listOf).orEmpty())
    }

    /**
     * Records ordered URL hints for the active media element. Exact URL matches beat
     * signed-query path matches, and earlier hints break ties. Only one candidate is marked;
     * all other streams remain available for manual selection.
     */
    @Synchronized
    fun setPlayingVideos(urls: List<String>) {
        setPlayingVideosLocked(urls)
    }

    /** Whether [candidate] is the stream reported by the active media element. */
    fun isPlaying(candidate: MediaSniffer.Candidate): Boolean =
        candidate.url == _state.value.playingCandidateUrl

    private fun setPlayingVideosLocked(urls: List<String>) {
        val hints = urls
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.length <= MAX_URL_LENGTH }
            .distinct()
            .take(MAX_PLAYING_HINTS)
            .toList()
        val current = _state.value
        if (hints == current.playingVideoHints) return
        val playing = findPlayingCandidate(current.candidates, hints)?.url
        publish(
            current.copy(
                playingVideoUrl = hints.firstOrNull(),
                playingVideoHints = hints,
                playingCandidateUrl = playing,
                candidates = sortCandidates(current.candidates, playing),
            ),
        )
    }

    private fun publish(next: Snapshot) {
        // Lists are created with immutable operations above. Copy the two externally
        // supplied collections once more so a caller cannot mutate a value after it has
        // been published to StateFlow.
        _state.value = next.copy(
            candidates = next.candidates.toList(),
            playingVideoHints = next.playingVideoHints.toList(),
        )
    }

    private fun sanitizeCandidate(candidate: MediaSniffer.Candidate): MediaSniffer.Candidate? {
        val url = candidate.url.trim()
        if (url.isEmpty() || url.length > MAX_URL_LENGTH) return null
        return candidate.copy(
            url = url,
            label = candidate.label.trim().take(MAX_LABEL_LENGTH).ifBlank { url },
            pageUrl = candidate.pageUrl?.trim()?.take(MAX_URL_LENGTH),
        )
    }

    private fun sortCandidates(
        values: List<MediaSniffer.Candidate>,
        playingUrl: String?,
    ): List<MediaSniffer.Candidate> = values.sortedWith(
        compareByDescending<MediaSniffer.Candidate> { it.url == playingUrl }
            .thenByDescending { it.kind.rank }
            .thenBy { it.label },
    )

    /**
     * Applies the picker cap while guaranteeing that a successfully matched active stream is
     * kept. Without this, a URL sorted below the first 24 entries could make the UI silently
     * lose the “正在播放” marker even though matching succeeded.
     */
    private fun boundCandidates(
        values: List<MediaSniffer.Candidate>,
        playingUrl: String?,
    ): Pair<List<MediaSniffer.Candidate>, String?> {
        val sorted = sortCandidates(values, playingUrl)
        if (sorted.size <= MAX_CANDIDATES) return sorted to playingUrl

        val bounded = sorted.take(MAX_CANDIDATES).toMutableList()
        if (playingUrl != null && bounded.none { it.url == playingUrl }) {
            sorted.firstOrNull { it.url == playingUrl }?.let { active ->
                bounded[bounded.lastIndex] = active
            }
        }
        val retainedPlaying = playingUrl?.takeIf { url -> bounded.any { it.url == url } }
        return bounded to retainedPlaying
    }

    /** Resolves hints in O(candidates × hints), parsing each URL once per update. */
    private fun findPlayingCandidate(
        pool: List<MediaSniffer.Candidate>,
        hints: List<String>,
    ): MediaSniffer.Candidate? {
        if (hints.isEmpty() || pool.isEmpty()) return null
        val endpoints = pool.map { endpointOf(it.url) }
        val peers = endpoints.filterNotNull().groupingBy { it.location }.eachCount()
        var best: MediaSniffer.Candidate? = null
        var bestQuality = 0
        hints.forEach { hint ->
            val left = endpointOf(hint)
            pool.forEachIndexed { index, candidate ->
                val right = endpoints[index]
                val quality = when {
                    hint == candidate.url || (left != null && left == right) -> 2
                    left != null && right != null && left.location == right.location &&
                        peers[right.location] == 1 -> 1
                    else -> 0
                }
                // Earlier hints and candidate order break ties. An exact match is final.
                if (quality > bestQuality) {
                    best = candidate
                    bestQuality = quality
                    if (quality == 2) return best
                }
            }
        }
        return best
    }

    private data class Location(val scheme: String, val host: String, val port: Int, val path: String)
    private data class Endpoint(val location: Location, val query: String?)

    private fun endpointOf(raw: String): Endpoint? = runCatching {
        val uri = raw.trim().toUri()
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return@runCatching null
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.takeIf(String::isNotBlank) ?: return@runCatching null
        val port = uri.port.takeIf { it >= 0 } ?: when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
        // Reserved escapes must not turn into path separators or query delimiters.
        Endpoint(Location(scheme, host, port, uri.encodedPath.orEmpty().ifEmpty { "/" }), uri.encodedQuery)
    }.getOrNull()

    private companion object {
        /** Stop an ad rotator from growing the picker without bound. */
        const val MAX_CANDIDATES = 24
        const val MAX_PLAYING_HINTS = 48
        const val MAX_URL_LENGTH = 8_192
        const val MAX_LABEL_LENGTH = 160
    }
}
