package com.mybrowser.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mybrowser.core.PlaybackSpeed
import com.mybrowser.core.VideoFit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Observes and controls a particular HTML video, including cross-origin player frames. */
@SuppressLint("RequiresFeature")
class MediaPlaybackTracker(
    private val webView: WebView,
    private val onSignal: (Signal) -> Unit,
) {
    data class Signal(
        val isPlaying: Boolean = false,
        val urls: List<String> = emptyList(),
        val frameUrl: String? = null,
        val score: Int = 0,
        val playbackRate: Float? = null,
        val hasVideo: Boolean = false,
        val hasMedia: Boolean = hasVideo,
        val playbackAvailable: Boolean = hasMedia,
        val muted: Boolean = false,
        val frameId: String = "",
        val videoId: String = "",
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val seekStart: Double = 0.0,
        val seekEnd: Double = 0.0,
        val width: Int = 0,
        val height: Int = 0,
        val isFullscreen: Boolean = false,
        val isBoosting: Boolean = false,
        val nativeControlsAvailable: Boolean = false,
        /** The playing video is not advancing because data has not arrived. */
        val buffering: Boolean = false,
        /** Bytes this frame has fed its own player; zero when the page exposes no counter. */
        val receivedBytes: Long = 0L,
        val sourceUrl: String? = null,
    ) {
        val canSeek: Boolean get() = hasMedia && duration > 0 && seekEnd > seekStart
        val identity: String get() = "$frameId/$videoId"
        val canUseEnhancedControls: Boolean get() = hasVideo && isFullscreen && nativeControlsAvailable
    }

    /** True when every new document receives the probe before page scripts run. */
    var isInstalled = false
        private set
    var current = Signal()
        private set
    private var closed = false
    private var listenerInstalled = false
    /** Last command error the page reported, so a refused takeover is logged once, not silently. */
    private var lastCommandError: String? = null
    private var scriptHandler: ScriptHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private val frames = linkedMapOf<String, Frame>()
    private val expireFrames = Runnable { if (!closed) publishCurrent() }
    private val pending = linkedMapOf<Int, Pending>()
    private val frameOrigins = linkedMapOf<String, FrameOrigin>()
    private var nextCommand = 0
    private var fullscreenTarget: Target? = null
    private var boostTarget: Target? = null
    private var suspended = false
    private val source: String get() = probeSource(webView.context)

    private class FrameOrigin(val key: String, var time: Long)
    private data class Frame(val signal: Signal, val time: Long, val proxy: JavaScriptReplyProxy?)
    private data class Target(val frameId: String, val videoId: String, val proxy: JavaScriptReplyProxy?)
    private data class Pending(val frameId: String, val callback: (Boolean) -> Unit)

    fun install(): Boolean {
        if (closed || listenerInstalled) return isInstalled
        if (!runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            }.getOrDefault(false)
        ) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(view: WebView, message: WebMessageCompat,
                        sourceOrigin: android.net.Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                        if (closed || view !== webView || sourceOrigin.scheme !in listOf("http", "https")) return
                        val json = decode(message.data) as? JSONObject ?: return
                        val frameId = json.optString("frameId").takeIf { it.isNotBlank() } ?: return
                        if (!claimsFrame(frameId, sourceOrigin)) return
                        if (json.optString("type") == "ack") {
                            val id = json.optInt("id", -1)
                            val request = pending[id] ?: return
                            if (request.frameId == frameId) {
                                pending.remove(id)
                                request.callback(json.optBoolean("ok"))
                            }
                        } else {
                            // The probe keeps the last command error in its state message: a
                            // refused takeover is otherwise silent apart from a transient hint,
                            // and which command failed is what a bug report needs.
                            json.optString("lastCommandError").takeIf { it.isNotBlank() && it != lastCommandError }
                                ?.let {
                                    lastCommandError = it
                                    android.util.Log.w(TAG, "Playback command failed: $it")
                                }
                            decodeSignal(json)?.let { accept(it, replyProxy) }
                        }
                    }
                })
            listenerInstalled = true
            // Older providers support messages before they support document-start
            // scripts. Keep their live play/pause bridge: waiting for the 1.2 s
            // fallback poll can let window hiding pause an opted-in background video.
            // Page-load probing still installs scripts in accessible frames only.
            scriptHandler = runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(webView, "$source(window);", setOf("*"))
                } else null
            }.getOrNull()
            isInstalled = scriptHandler != null
            isInstalled
        }.getOrElse { removeHooks(); false }
    }

    /** Remove handles while they still refer to the original native contents. */
    fun prepareForPopup() {
        scriptHandler?.let { runCatching { it.remove() } }
        scriptHandler = null
    }

    /** The popup transport preserves the bridge, but older providers drop scripts. */
    fun onPopupContentsAttached() {
        if (closed || !isInstalled) return
        isInstalled = runCatching {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, "$source(window);", setOf("*"))
            true
        }.getOrDefault(false)
    }

    /** Modern providers post from each frame; old providers inspect accessible frames only. */
    fun probe() {
        if (closed) return
        if (isInstalled) {
            runCatching {
                webView.evaluateJavascript("(function(){var p=window.__pureBrowserVideoV2;if(!p)return false;p.post();return true;})()") { raw ->
                    if (!closed && raw != "true") fallbackProbe()
                }
            }.onFailure { fallbackProbe() }
        } else fallbackProbe()
    }

    private fun fallbackProbe() {
        if (closed) return
        runCatching {
            webView.evaluateJavascript(walkScript("out.push(api.snapshot());", "JSON.stringify(out)")) { raw ->
                if (closed) return@evaluateJavascript
                val values = decode(raw) as? JSONArray ?: return@evaluateJavascript
                for (index in 0 until minOf(values.length(), 32)) {
                    values.optJSONObject(index)?.let(::decodeSignal)?.let { accept(it, null) }
                }
            }
        }
    }

    fun setPlaybackRate(rate: Float, onResult: (Boolean) -> Unit = {}) {
        val safe = PlaybackSpeed.normalizeSelection(rate)
        if (safe == null) { onResult(false); return }
        send(target(), "setPlaybackRate", JSONObject().put("rate", PlaybackSpeed.wireRate(safe)), onResult)
    }

    fun togglePlayback(onResult: (Boolean) -> Unit = {}) = send(target(), "togglePlayback", onResult = onResult)
    fun setPlaying(value: Boolean) = send(target(), if (value) "play" else "pause")

    /** System Pause/noisy/ownership changes silence the whole document, across frames. */
    fun pauseAll() {
        frames.values.toList().forEach { frame ->
            send(Target(frame.signal.frameId, frame.signal.videoId, frame.proxy), "pauseAll")
        }
        runCatching { webView.evaluateJavascript(walkScript("api.pauseAll();", "true"), null) }
    }

    /** Pause every known frame and prevent autoplay while the tab is parked. */
    fun setSuspended(value: Boolean) {
        suspended = value
        frames.values.toList().forEach { frame ->
            send(Target(frame.signal.frameId, frame.signal.videoId, frame.proxy), "suspend", JSONObject().put("value", value))
        }
        runCatching { webView.evaluateJavascript(walkScript("api.suspend($value);", "true"), null) }
        if (value) {
            frames.replaceAll { _, frame -> frame.copy(signal = frame.signal.copy(isPlaying = false)) }
            publishCurrent()
        }
    }

    fun seekTo(position: Double, onResult: (Boolean) -> Unit = {}) {
        if (!position.isFinite() || !current.canSeek) { onResult(false); return }
        send(target(), "seek", JSONObject().put("position", position.coerceIn(current.seekStart, current.seekEnd)), onResult)
    }

    fun beginBoost(rate: Float, onResult: (Boolean) -> Unit = {}) {
        if (rate != 2f && rate != 3f) { onResult(false); return }
        boostTarget = target()
        send(boostTarget, "beginBoost", JSONObject().put("rate", PlaybackSpeed.wireRate(rate)), onResult)
    }

    fun endBoost() {
        val target = boostTarget ?: return
        boostTarget = null
        send(target, "endBoost")
    }

    /** Moves the rate of an ongoing hold without losing the rate to restore on release. */
    fun updateBoost(rate: Float, onResult: (Boolean) -> Unit = {}) {
        val safe = PlaybackSpeed.normalizeSelection(rate)
        val target = boostTarget
        if (safe == null || target == null) { onResult(false); return }
        send(target, "setBoostRate", JSONObject().put("rate", PlaybackSpeed.wireRate(safe)), onResult)
    }

    fun setFullscreenControls(enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        if (enabled) {
            if (!current.canUseEnhancedControls) { onResult(false); return }
            fullscreenTarget = activeTarget()
            send(fullscreenTarget, "nativeControls", onResult = onResult)
        } else {
            endBoost()
            val previous = fullscreenTarget
            fullscreenTarget = null
            send(previous, "restoreControls", onResult = onResult)
        }
    }

    /**
     * Applies the user's picture fit and mirror to the video the takeover owns.
     *
     * The command goes to the pinned fullscreen element, not to the best-scoring one: the panel
     * that offers it is only on screen while that element fills the window, and a page can be
     * playing several videos at once.
     */
    fun setVideoTransform(mirror: Boolean, fit: VideoFit, onResult: (Boolean) -> Unit = {}) {
        val pinned = fullscreenTarget
        if (pinned == null) { onResult(false); return }
        send(pinned, "setVideoTransform", JSONObject().put("mirror", mirror).put("fit", fit.name), onResult)
    }

    private fun activeTarget(): Target? = frames[current.frameId]?.takeIf { it.signal.hasMedia }?.let {
        Target(it.signal.frameId, it.signal.videoId, it.proxy)
    }

    private fun target(): Target? = fullscreenTarget ?: activeTarget()

    private fun send(target: Target?, type: String, values: JSONObject = JSONObject(), onResult: (Boolean) -> Unit = {}) {
        if (closed || target == null) { onResult(false); return }
        val id = ++nextCommand
        val command = values.put("type", type).put("id", id)
            .put("frameId", target.frameId).put("videoId", target.videoId).toString()
        if (target.proxy != null) {
            pending[id] = Pending(target.frameId, onResult)
            if (runCatching { target.proxy.postMessage(command) }.isSuccess) {
                handler.postDelayed({ pending.remove(id)?.callback?.invoke(false) }, 2_000L)
                return
            }
            pending.remove(id)
        }
        // The payload is generated JSON, never page-supplied JavaScript. A matching frame
        // and element id are mandatory, so a late command cannot hit a different video.
        runCatching {
            webView.evaluateJavascript(walkScript("if(api.command($command))out.push(true);", "out.length>0")) { raw ->
                if (!closed) { onResult(raw == "true"); fallbackProbe() }
            }
        }.onFailure { onResult(false) }
    }

    private fun walkScript(action: String, result: String): String = """
        (function(){var install=$source;var out=[];var count=0;
          function walk(w,depth){if(depth>5||++count>32)return;try{
            var doc=w.document;var api=w.__pureBrowserVideoV2||install(w);$action
            Array.prototype.slice.call(doc.querySelectorAll('iframe,frame'),0,32).forEach(function(f){try{walk(f.contentWindow,depth+1);}catch(e){}});
          }catch(e){}}walk(window,0);return $result;})()
    """.trimIndent()

    /**
     * True when [origin] is allowed to speak for [frameId].
     *
     * The bridge is visible to every frame and the probe reports from any of them, so on its
     * own a frame id is just a string a page chose: any third-party iframe could post a state
     * message claiming another frame's id and steer the cast candidates and the "now playing"
     * UI. The probe derives its id from the clock and `Math.random`, which another frame
     * cannot predict, so binding an id to the origin that first used it means a frame can only
     * ever report itself — and because the id is unguessable, a binding that ages out is safe
     * to recreate. Cross-origin embeds keep working: they report under their own id.
     */
    internal fun claimsFrame(frameId: String, origin: android.net.Uri): Boolean {
        val now = SystemClock.uptimeMillis()
        val key = if (origin.port != -1) "${origin.scheme}://${origin.host}:${origin.port}"
            else "${origin.scheme}://${origin.host}"
        val bound = frameOrigins[frameId]
        if (bound != null) {
            if (bound.key != key) return false
            bound.time = now
            return true
        }
        if (frameOrigins.size >= MAX_TRACKED_FRAMES) {
            // A rejected report never reaches the expiry pass below, so the table has to make
            // room for itself here: otherwise a page that fills it once would have every later
            // frame ignored, including its own player, for good. The binding silent longest goes,
            // not the one bound first: `time` is refreshed on every report, so insertion order
            // would evict a frame that is still talking ahead of one that has gone quiet.
            frameOrigins.entries.removeAll { now - it.value.time >= FRAME_TIMEOUT_MS }
            if (frameOrigins.size >= MAX_TRACKED_FRAMES) {
                frameOrigins.minByOrNull { it.value.time }?.key?.let { frameOrigins.remove(it) }
            }
        }
        frameOrigins[frameId] = FrameOrigin(key, now)
        return true
    }

    private fun accept(signal: Signal, proxy: JavaScriptReplyProxy?) {
        if (closed) return
        if (suspended && signal.isPlaying) {
            send(Target(signal.frameId, signal.videoId, proxy), "suspend", JSONObject().put("value", true))
        }
        val now = SystemClock.uptimeMillis()
        // A delayed play message must not recreate a session after a tab or PiP closes.
        val observed = if (suspended) signal.copy(isPlaying = false) else signal
        frames[signal.frameId] = Frame(observed, now, proxy ?: frames[signal.frameId]?.proxy)
        while (frames.size > 32) frames.remove(frames.keys.first())
        publishCurrent()
    }

    private fun publishCurrent() {
        val now = SystemClock.uptimeMillis()
        frames.entries.removeAll { now - it.value.time >= FRAME_TIMEOUT_MS }
        // Bindings age out with the frames they describe. Keeping them for the life of the
        // document would let a page that keeps creating iframes reach the cap, after which a
        // legitimate new frame's first report would be dropped.
        frameOrigins.entries.removeAll { now - it.value.time >= FRAME_TIMEOUT_MS }
        val pinned = fullscreenTarget
        val best = frames.values.filter { it.signal.hasMedia }.maxWithOrNull(
            compareBy<Frame> { it.signal.isFullscreen }
                .thenBy { it.signal.frameId == pinned?.frameId && it.signal.videoId == pinned?.videoId }
                .thenBy { it.signal.isPlaying }.thenBy { it.signal.score },
        )
        current = best?.signal ?: Signal()
        onSignal(current)
        // Removed cross-origin frames can disappear without a final JS message.
        // Expiry must run even when the remaining page contains no media and is quiet.
        handler.removeCallbacks(expireFrames)
        frames.values.filter { it.signal.hasMedia }.minOfOrNull { it.time }?.let { earliest ->
            handler.postDelayed(expireFrames, (earliest + FRAME_TIMEOUT_MS - now).coerceAtLeast(1L))
        }
    }

    fun reset() {
        endBoost()
        fullscreenTarget = null
        frames.clear()
        frameOrigins.clear()
        handler.removeCallbacks(expireFrames)
        val callbacks = pending.values.toList()
        pending.clear()
        callbacks.forEach { it.callback(false) }
        current = Signal()
        if (!closed) onSignal(current)
    }

    fun close() {
        if (closed) return
        endBoost()
        runCatching {
            webView.evaluateJavascript("""(function walk(w,d){if(d>5)return;try{
                if(w.__pureBrowserVideoV2)w.__pureBrowserVideoV2.dispose();
                Array.prototype.slice.call(w.document.querySelectorAll('iframe,frame'),0,32).forEach(function(f){walk(f.contentWindow,d+1);});
            }catch(e){}})(window,0)""", null)
        }
        closed = true
        handler.removeCallbacksAndMessages(null)
        pending.clear()
        frames.clear()
        frameOrigins.clear()
        fullscreenTarget = null
        removeHooks()
    }

    private fun removeHooks() {
        scriptHandler?.let { runCatching { it.remove() } }
        scriptHandler = null
        if (listenerInstalled) runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE) }
        listenerInstalled = false
        isInstalled = false
    }

    companion object {
        private const val TAG = "MediaPlaybackTracker"
        private const val FRAME_TIMEOUT_MS = 4_000L
        /** Backstop for the expiry above: frames reporting within one timeout window. */
        private const val MAX_TRACKED_FRAMES = 256
        private const val BRIDGE = "mybrowserMediaProbe"
        @Volatile private var cachedSource: String? = null
        private fun probeSource(context: Context): String = cachedSource ?: synchronized(this) {
            cachedSource ?: context.assets.open("playback-probe.js").bufferedReader().use { it.readText() }
                .also { cachedSource = it }
        }
        private fun decode(raw: String?): Any? {
            if (raw == null || raw.length > 131_072) return null
            val result = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
            return if (result is String) runCatching { JSONTokener(result).nextValue() }.getOrNull() else result
        }
        internal fun decodeSignal(json: JSONObject): Signal? {
            val frame = json.optString("frameId").takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
            val video = json.optString("videoId").takeIf { it != "null" && it.length <= 64 }.orEmpty()
            val hasVideo = video.isNotEmpty() && json.optBoolean("hasVideo")
            val hasMedia = video.isNotEmpty() && json.optBoolean("hasMedia", hasVideo)
            fun seconds(key: String): Double = json.optDouble(key, 0.0).takeIf { it.isFinite() }
                ?.coerceIn(0.0, 31_536_000.0) ?: 0.0
            val urls = buildList {
                val array = json.optJSONArray("urls") ?: return@buildList
                for (i in 0 until minOf(64, array.length())) {
                    val url = array.optString(i)
                    if (url.length <= 8192 && url.toUri().scheme in listOf("http", "https", "blob")) add(url)
                }
            }.distinct()
            return Signal(
                isPlaying = hasMedia && json.optBoolean("playing"), urls = urls,
                frameUrl = json.optString("frameUrl").take(8192),
                score = json.optInt("score").coerceIn(0, 20_000),
                playbackRate = PlaybackSpeed.sanitizeObserved(json.optDouble("playbackRate", Double.NaN).toFloat()),
                hasVideo = hasVideo, hasMedia = hasMedia,
                playbackAvailable = hasMedia && json.optBoolean("playbackAvailable", hasMedia),
                muted = json.optBoolean("muted"), frameId = frame, videoId = video,
                position = seconds("position"), duration = seconds("duration"),
                seekStart = seconds("seekStart"), seekEnd = seconds("seekEnd"),
                width = json.optInt("width").coerceIn(0, 16384), height = json.optInt("height").coerceIn(0, 16384),
                isFullscreen = hasVideo && json.optBoolean("fullscreen"),
                isBoosting = hasVideo && json.optBoolean("boosting"),
                nativeControlsAvailable = hasVideo && json.optBoolean("nativeControlsAvailable"),
                buffering = hasMedia && json.optBoolean("buffering"),
                receivedBytes = json.optLong("receivedBytes", 0L).coerceIn(0L, 1L shl 42),
                sourceUrl = json.optString("sourceUrl").takeIf {
                    hasMedia && it.length <= 8192 && it.toUri().scheme in listOf("http", "https", "blob")
                },
            )
        }
    }
}
