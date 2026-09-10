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
        val sourceUrl: String? = null,
    ) {
        val canSeek: Boolean get() = hasVideo && duration > 0 && seekEnd > seekStart
        val identity: String get() = "$frameId/$videoId"
        val canUseEnhancedControls: Boolean get() = hasVideo && isFullscreen && nativeControlsAvailable
    }

    var isInstalled = false
        private set
    var current = Signal()
        private set
    private var closed = false
    private var listenerInstalled = false
    private var scriptHandler: ScriptHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private val frames = linkedMapOf<String, Frame>()
    private val pending = linkedMapOf<Int, Pending>()
    private var nextCommand = 0
    private var fullscreenTarget: Target? = null
    private var boostTarget: Target? = null
    private val source: String get() = probeSource(webView.context)

    private data class Frame(val signal: Signal, val time: Long, val proxy: JavaScriptReplyProxy?)
    private data class Target(val frameId: String, val videoId: String, val proxy: JavaScriptReplyProxy?)
    private data class Pending(val frameId: String, val callback: (Boolean) -> Unit)

    fun install(): Boolean {
        if (closed || isInstalled) return isInstalled
        if (!runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            }.getOrDefault(false)
        ) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(view: WebView, message: WebMessageCompat,
                        sourceOrigin: android.net.Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
                        if (closed || view !== webView || sourceOrigin.scheme !in listOf("http", "https")) return
                        val json = decode(message.data) as? JSONObject ?: return
                        if (json.optString("type") == "ack") {
                            val id = json.optInt("id", -1)
                            val request = pending[id] ?: return
                            if (request.frameId == json.optString("frameId")) {
                                pending.remove(id)
                                request.callback(json.optBoolean("ok"))
                            }
                        } else {
                            decodeSignal(json)?.let { accept(it, replyProxy) }
                        }
                    }
                })
            listenerInstalled = true
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, "$source(window);", setOf("*"))
            isInstalled = true
            true
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
        send(target(), "setPlaybackRate", JSONObject().put("rate", safe.toDouble()), onResult)
    }

    fun togglePlayback(onResult: (Boolean) -> Unit = {}) = send(target(), "togglePlayback", onResult = onResult)

    fun seekTo(position: Double, onResult: (Boolean) -> Unit = {}) {
        if (!position.isFinite() || !current.canSeek) { onResult(false); return }
        send(target(), "seek", JSONObject().put("position", position.coerceIn(current.seekStart, current.seekEnd)), onResult)
    }

    fun beginBoost(rate: Float, onResult: (Boolean) -> Unit = {}) {
        if (rate != 2f && rate != 3f) { onResult(false); return }
        boostTarget = target()
        send(boostTarget, "beginBoost", JSONObject().put("rate", rate.toDouble()), onResult)
    }

    fun endBoost() {
        val target = boostTarget ?: return
        boostTarget = null
        send(target, "endBoost")
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

    private fun activeTarget(): Target? = frames[current.frameId]?.takeIf { it.signal.hasVideo }?.let {
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

    private fun accept(signal: Signal, proxy: JavaScriptReplyProxy?) {
        if (closed) return
        val now = SystemClock.uptimeMillis()
        frames[signal.frameId] = Frame(signal, now, proxy ?: frames[signal.frameId]?.proxy)
        frames.entries.removeAll { now - it.value.time > 4_000L }
        while (frames.size > 32) frames.remove(frames.keys.first())
        val pinned = fullscreenTarget
        val best = frames.values.filter { it.signal.hasVideo }.maxWithOrNull(
            compareBy<Frame> { it.signal.isFullscreen }
                .thenBy { it.signal.frameId == pinned?.frameId && it.signal.videoId == pinned?.videoId }
                .thenBy { it.signal.isPlaying }.thenBy { it.signal.score },
        )
        current = best?.signal ?: Signal()
        onSignal(current)
    }

    fun reset() {
        endBoost()
        fullscreenTarget = null
        frames.clear()
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
                isPlaying = hasVideo && json.optBoolean("playing"), urls = urls,
                frameUrl = json.optString("frameUrl").take(8192),
                score = json.optInt("score").coerceIn(0, 20_000),
                playbackRate = PlaybackSpeed.sanitizeObserved(json.optDouble("playbackRate", Double.NaN).toFloat()),
                hasVideo = hasVideo, frameId = frame, videoId = video,
                position = seconds("position"), duration = seconds("duration"),
                seekStart = seconds("seekStart"), seekEnd = seconds("seekEnd"),
                width = json.optInt("width").coerceIn(0, 16384), height = json.optInt("height").coerceIn(0, 16384),
                isFullscreen = hasVideo && json.optBoolean("fullscreen"),
                isBoosting = hasVideo && json.optBoolean("boosting"),
                nativeControlsAvailable = hasVideo && json.optBoolean("nativeControlsAvailable"),
                sourceUrl = json.optString("sourceUrl").takeIf {
                    hasVideo && it.length <= 8192 && it.toUri().scheme in listOf("http", "https", "blob")
                },
            )
        }
    }
}
