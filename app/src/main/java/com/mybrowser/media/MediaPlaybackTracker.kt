package com.mybrowser.media

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Observes the media element that is actually playing in a WebView.
 *
 * A video page commonly nests the player in one or more iframes, and an MSE/HLS player often
 * exposes a `blob:` currentSrc instead of the manifest URL that the network sniffer sees. A
 * document-start probe is therefore installed in every frame and reports both the active
 * element's URLs and that frame's recent media resources through WebView's message channel.
 * No JavaScript interface is exposed to the page.
 */
@SuppressLint("RequiresFeature")
class MediaPlaybackTracker(
    private val webView: WebView,
    private val onSignal: (Signal) -> Unit,
) {

    data class Signal(
        val isPlaying: Boolean,
        val urls: List<String>,
        val frameUrl: String?,
        val score: Int = 0,
    )

    @Volatile
    var isInstalled: Boolean = false
        private set

    private var scriptHandler: ScriptHandler? = null
    private var messageListenerInstalled = false
    @Volatile private var closed = false
    private val signalLock = Any()
    private val frameSignals = linkedMapOf<String, TimedSignal>()

    private data class TimedSignal(val signal: Signal, val at: Long)

    /** Installs the all-frame probe when the current WebView provider supports it. */
    fun install(): Boolean {
        if (closed || isInstalled) return isInstalled
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) return false

        return runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                ALLOWED_ORIGINS,
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: android.net.Uri,
                        isMainFrame: Boolean,
                        replyProxy: androidx.webkit.JavaScriptReplyProxy,
                    ) {
                        if (closed || view !== webView) return
                        // The listener is registered with a wildcard so cross-origin player
                        // frames can report their own media. Still reject non-web origins;
                        // file/data pages must never be able to feed arbitrary bridge data.
                        val scheme = sourceOrigin.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") return
                        parseSignal(message.data)?.let(::acceptSignal)
                    }
                },
            )
            messageListenerInstalled = true
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                DOCUMENT_START_SCRIPT,
                ALLOWED_ORIGINS,
            )
            isInstalled = true
            true
        }.getOrElse {
            removeInstalledHooks()
            false
        }
    }

    /**
     * Performs a one-shot probe in the current frame. This is also the fallback for old
     * WebView providers where document-start scripts or message listeners are unavailable.
     */
    fun probe() {
        if (closed) return

        // On providers with document-start support, ask the exact same all-frame probe that
        // is already running in the page. The older fallback walks only the top document and
        // can otherwise overwrite a correct child-frame signal with an incomplete result just
        // as the user opens the cast picker.
        if (isInstalled) {
            val requested = runCatching {
                webView.evaluateJavascript(REQUEST_PROBE_SCRIPT) { raw ->
                    if (closed) return@evaluateJavascript
                    if (raw?.trim() != "true") evaluateFallbackProbe()
                }
            }.isSuccess
            if (requested) return
        }
        evaluateFallbackProbe()
    }

    private fun evaluateFallbackProbe() {
        if (closed) return
        runCatching {
            webView.evaluateJavascript(FALLBACK_PROBE_SCRIPT) { raw ->
                if (closed) return@evaluateJavascript
                parseSignal(raw)?.let(::acceptSignal)
            }
        }
    }

    /** Drops signals belonging to the document that just navigated. */
    fun reset() {
        synchronized(signalLock) { frameSignals.clear() }
    }

    fun close() {
        synchronized(signalLock) {
            if (closed) return
            closed = true
            frameSignals.clear()
        }
        removeInstalledHooks()
    }

    private fun removeInstalledHooks() {
        scriptHandler?.let { runCatching { it.remove() } }
        scriptHandler = null
        if (messageListenerInstalled) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
            messageListenerInstalled = false
        }
        isInstalled = false
    }

    private fun parseSignal(raw: String?): Signal? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return null
        val value = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        val json = when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return null

        val urls = buildList {
            json.optString("url")
                .takeIf(::isUsableHint)
                ?.let(::add)
            json.optJSONArray("urls")?.let { array: JSONArray ->
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .takeIf(::isUsableHint)
                        ?.let(::add)
                }
            }
        }.distinct().take(MAX_URLS)

        return Signal(
            isPlaying = json.optBoolean("playing", false),
            urls = urls,
            frameUrl = json.optString("frameUrl")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.take(MAX_URL_LENGTH),
            score = json.optInt("score", 0).coerceIn(0, MAX_SCORE),
        )
    }

    private fun isUsableHint(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value == "null" || value.length > MAX_URL_LENGTH) return false
        val scheme = value.toUri().scheme?.lowercase()
        return scheme == "http" || scheme == "https" || scheme == "blob"
    }

    /**
     * Messages arrive independently from the top document and each player iframe. Keep a
     * short-lived value per frame and expose the strongest active one; a quiet top frame must
     * not clear a video that is still playing in a child frame.
     */
    private fun acceptSignal(signal: Signal) {
        val active = synchronized(signalLock) {
            if (closed) return
            val now = SystemClock.uptimeMillis()
            val key = signal.frameUrl ?: "<unknown-frame>"
            frameSignals[key] = TimedSignal(signal, now)
            val cutoff = now - SIGNAL_TTL_MS
            val iterator = frameSignals.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.at < cutoff) iterator.remove()
            }
            // A page can create unbounded ad/player iframes. Keep the newest signals only;
            // this also bounds the work of every subsequent probe.
            while (frameSignals.size > MAX_FRAME_SIGNALS) {
                frameSignals.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            frameSignals.values
                .map { it.signal }
                .filter { it.isPlaying }
                .maxWithOrNull(compareBy<Signal> { it.score }.thenBy { it.urls.size })
        }
        if (active != null) {
            onSignal(active)
        } else {
            onSignal(Signal(isPlaying = false, urls = emptyList(), frameUrl = signal.frameUrl))
        }
    }

    private companion object {
        const val BRIDGE_NAME = "mybrowserMediaProbe"
        val ALLOWED_ORIGINS = setOf("*")
        const val MAX_URLS = 64
        const val MAX_FRAME_SIGNALS = 32
        const val MAX_URL_LENGTH = 8_192
        const val MAX_SCORE = 10_000

        /**
         * Runs at document start in every frame. It deliberately uses defensive try/catch
         * around frame access: cross-origin child frames cannot be inspected by the parent,
         * but the same script is present inside those frames and reports independently.
         */
        val DOCUMENT_START_SCRIPT = """
            (function() {
              if (window.__mybrowserMediaProbeInstalled) return;
              window.__mybrowserMediaProbeInstalled = true;
              var bridgeName = '$BRIDGE_NAME';
              var scheduled = false;

              function absolute(value) {
                if (!value) return '';
                try { return new URL(String(value), document.baseURI).href; }
                catch (_) { return String(value); }
              }

              function addUnique(list, value) {
                var url = absolute(value);
                if (!url || url.length > 8192 || list.indexOf(url) >= 0) return;
                // Individual transport segments are not castable and only make the message
                // huge; manifests and progressive media remain useful hints.
                if (/\.(ts|m4s)(?:[?#]|$)/i.test(url)) return;
                list.push(url);
              }

              function visible(video) {
                try {
                  var style = window.getComputedStyle(video);
                  var rect = video.getBoundingClientRect();
                  return style.display !== 'none' && style.visibility !== 'hidden' &&
                    rect.width > 1 && rect.height > 1;
                } catch (_) { return false; }
              }

              function decode(value) {
                var text = String(value || '');
                for (var i = 0; i < 3; i++) {
                  try {
                    var next = decodeURIComponent(text);
                    if (next === text) break;
                    text = next;
                  } catch (_) { break; }
                }
                return text;
              }

              function isMediaUrl(url) {
                return /(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov|manifest|playlist)(?:[?#]|$)/i.test(url);
              }

              // Some mobile sites put the actual player in a cross-origin iframe. The
              // parent cannot inspect that frame's DOM, but its src/data attributes often
              // carry the manifest directly (or URL-encoded in a query parameter).
              function addEmbedded(list, value, base) {
                var raw = decode(value);
                var direct = absolute(raw);
                if (isMediaUrl(direct)) addUnique(list, direct);
                try {
                  var parsed = new URL(raw, base || document.baseURI);
                  parsed.searchParams.forEach(function(param) {
                    var nested = absolute(decode(param));
                    if (isMediaUrl(nested)) addUnique(list, nested);
                  });
                } catch (_) {}
                var matches = raw.match(/(?:(?:https?:)?\/\/)[^"'\s<>]+(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov|manifest|playlist)(?:[?#][^"'\s<>]*)?/ig) || [];
                matches.forEach(function(match) {
                  var nested = absolute(decode(match));
                  if (isMediaUrl(nested)) addUnique(list, nested);
                });
              }

              function attach(video) {
                if (video.__mybrowserMediaProbeAttached) return;
                video.__mybrowserMediaProbeAttached = true;
                ['play', 'playing', 'timeupdate', 'loadedmetadata', 'canplay',
                 'pause', 'ended', 'emptied', 'durationchange'].forEach(function(name) {
                  try { video.addEventListener(name, schedule); } catch (_) {}
                });
              }

              function collect(root, frameUrl, depth, output) {
                if (!root || depth > 4) return;
                var videos = [];
                try {
                  videos = root.querySelectorAll ?
                    Array.prototype.slice.call(root.querySelectorAll('video')) : [];
                } catch (_) {}
                videos.forEach(function(video) {
                  attach(video);
                  var urls = [];
                  try { addUnique(urls, video.currentSrc); } catch (_) {}
                  try { addUnique(urls, video.src); } catch (_) {}
                  try {
                    Array.prototype.slice.call(video.querySelectorAll('source')).forEach(function(source) {
                      addUnique(urls, source.currentSrc || source.src || source.getAttribute('src'));
                    });
                  } catch (_) {}
                  var score = 0;
                  var active = false;
                  try {
                    // `paused == false` is the browser's semantic playing state. A stream
                    // can be buffering (readyState < 2) while still being the video the user
                    // chose, so do not wait for enough data before identifying it.
                    active = !video.paused && !video.ended;
                    if (!video.paused && !video.ended) score += 1000;
                    if (video.readyState >= 2) score += 100;
                    if (video.currentTime > 0) score += 80;
                    if (!video.muted) score += 25;
                    if (video.videoWidth > 0 && video.videoHeight > 0) score += 40;
                  } catch (_) {}
                  if (visible(video)) score += 150;
                  try {
                    var rect = video.getBoundingClientRect();
                    score += Math.min(150, Math.max(0, rect.width * rect.height / 10000));
                  } catch (_) {}
                  output.push({ urls: urls, score: score, active: active, frameUrl: frameUrl });
                });

                var frames = [];
                try {
                  frames = root.querySelectorAll ?
                    Array.prototype.slice.call(root.querySelectorAll('iframe,frame')) : [];
                } catch (_) {}
                frames.forEach(function(frame) {
                  var child = null;
                  var childUrl = '';
                  try { child = frame.contentDocument; } catch (_) {}
                  try { childUrl = frame.contentWindow.location.href; } catch (_) {}
                  var frameSource = '';
                  try { frameSource = frame.getAttribute('src') || frame.src || ''; } catch (_) {}
                  if (!child) childUrl = absolute(frameSource);

                  var embedded = [];
                  addEmbedded(embedded, frameSource, frameUrl);
                  ['data-src', 'data-url', 'data-video', 'data-hls', 'data-m3u8'].forEach(function(name) {
                    try { addEmbedded(embedded, frame.getAttribute(name), frameUrl); } catch (_) {}
                  });
                  if (embedded.length && visible(frame)) {
                    var frameScore = 500;
                    try {
                      var frameRect = frame.getBoundingClientRect();
                      frameScore += Math.min(250, Math.max(0, frameRect.width * frameRect.height / 10000));
                    } catch (_) {}
                    output.push({
                      urls: embedded.slice(0, 16),
                      score: frameScore,
                      active: true,
                      frameUrl: childUrl || frameUrl
                    });
                  }
                  if (child) collect(child, childUrl || frameUrl, depth + 1, output);
                });
              }

              function resources(list) {
                try {
                  var entries = performance.getEntriesByType('resource') || [];
                  var start = Math.max(0, entries.length - 80);
                  // Newest requests are the best fallback for an MSE player whose `currentSrc`
                  // is only a blob URL. Keep direct video URLs first, then append resources
                  // from newest to oldest so another stream's stale manifest is less likely to
                  // win the ordered match.
                  for (var index = entries.length - 1; index >= start; index--) {
                    var entry = entries[index];
                    var name = entry && entry.name;
                    if (!name ||
                        !/(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov|manifest|playlist)(?:[?#]|$)/i.test(name)) {
                      continue;
                    }
                    addUnique(list, name);
                  }
                } catch (_) {}
              }

              function signal() {
                var found = [];
                collect(document, location.href, 0, found);
                found.sort(function(a, b) { return b.score - a.score; });
                var best = found.length ? found[0] : null;
                var urls = [];
                if (best) best.urls.forEach(function(url) { addUnique(urls, url); });
                resources(urls);
                if (urls.length > 64) urls = urls.slice(0, 64);
                return {
                  playing: !!best && !!best.active,
                  urls: urls,
                  frameUrl: best && best.frameUrl ? best.frameUrl : location.href,
                  score: best ? best.score : 0
                };
              }

              function post() {
                var message;
                try { message = JSON.stringify(signal()); } catch (_) { return; }
                try {
                  var bridge = window[bridgeName];
                  if (bridge && bridge.postMessage) bridge.postMessage(message);
                } catch (_) {}
              }

              // Keep a page-local trigger so a cast-button tap can request this same
              // cross-frame probe instead of running the less capable top-frame fallback.
              try { window.__mybrowserMediaProbeNow = post; } catch (_) {}

              function schedule() {
                if (scheduled) return;
                scheduled = true;
                setTimeout(function() { scheduled = false; post(); }, 0);
              }

              try {
                new MutationObserver(schedule).observe(document.documentElement || document,
                  { childList: true, subtree: true });
              } catch (_) {}
              try { document.addEventListener('DOMContentLoaded', post); } catch (_) {}
              try { window.addEventListener('load', post); } catch (_) {}
              try { setInterval(post, 1000); } catch (_) {}
              schedule();
            })();
        """.trimIndent()

        /** Requests the document-start probe without duplicating its traversal logic. */
        val REQUEST_PROBE_SCRIPT = """
            (function() {
              try {
                var probe = window.__mybrowserMediaProbeNow;
                if (typeof probe !== 'function') return false;
                probe();
                return true;
              } catch (_) { return false; }
            })();
        """.trimIndent()

        /** Compact one-shot fallback for providers without document-start support. */
        val FALLBACK_PROBE_SCRIPT = """
            (function() {
              function abs(value, base) {
                if (!value) return '';
                try { return new URL(String(value), base || document.baseURI).href; }
                catch (_) { return String(value); }
              }

              function decode(value) {
                var text = String(value || '');
                for (var i = 0; i < 3; i++) {
                  try {
                    var next = decodeURIComponent(text);
                    if (next === text) break;
                    text = next;
                  } catch (_) { break; }
                }
                return text;
              }

              function isMediaUrl(url) {
                return /(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov|manifest|playlist)(?:[?#]|$)/i.test(url);
              }

              function add(list, value, base) {
                var url = abs(decode(value), base);
                if (!/^https?:/i.test(url) || url.length > 8192 || !isMediaUrl(url) ||
                    /\.(ts|m4s)(?:[?#]|$)/i.test(url)) return;
                if (list.indexOf(url) < 0) list.push(url);
              }

              // Many mobile players put the real manifest in a cross-origin iframe's
              // query string. The parent cannot inspect that frame's DOM, but it can
              // safely read its src and the URL-valued query parameters.
              function addEmbedded(list, value, base) {
                var raw = decode(value);
                add(list, raw, base);
                try {
                  var parsed = new URL(raw, base || document.baseURI);
                  parsed.searchParams.forEach(function(param) { add(list, param, base); });
                } catch (_) {}
                var matches = raw.match(/(?:(?:https?:)?\/\/)[^"'\s<>]+(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov|manifest|playlist)(?:[?#][^"'\s<>]*)?/ig) || [];
                matches.forEach(function(match) { add(list, match, base); });
              }

              function visible(element) {
                try {
                  var style = window.getComputedStyle(element);
                  var rect = element.getBoundingClientRect();
                  return style.display !== 'none' && style.visibility !== 'hidden' &&
                    rect.width > 1 && rect.height > 1;
                } catch (_) { return false; }
              }

              function frameScore(frame, depth) {
                var score = depth * 5;
                try {
                  var rect = frame.getBoundingClientRect();
                  if (visible(frame)) {
                    score += 200;
                    score += Math.min(150, Math.max(0, rect.width * rect.height / 10000));
                  }
                } catch (_) {}
                return score;
              }

              var found = [];
              function collect(root, frameUrl, depth) {
                if (!root || depth > 5) return;
                var videos = [];
                try { videos = Array.prototype.slice.call(root.querySelectorAll('video')); }
                catch (_) {}
                videos.forEach(function(video) {
                  var urls = [];
                  var active = false;
                  var score = depth * 5;
                  try { add(urls, video.currentSrc, frameUrl); } catch (_) {}
                  try { add(urls, video.src, frameUrl); } catch (_) {}
                  try {
                    Array.prototype.slice.call(video.querySelectorAll('source')).forEach(function(source) {
                      add(urls, source.currentSrc || source.src || source.getAttribute('src'), frameUrl);
                    });
                  } catch (_) {}
                  try {
                    active = !video.paused && !video.ended;
                    if (active) score += 1000;
                    if (video.currentTime > 0) score += 80;
                    if (video.videoWidth > 0 && video.videoHeight > 0) score += 40;
                    var rect = video.getBoundingClientRect();
                    if (visible(video)) {
                      score += 150;
                      score += Math.min(150, Math.max(0, rect.width * rect.height / 10000));
                    }
                  } catch (_) {}
                  found.push({ urls: urls, active: active, score: score, frameUrl: frameUrl, depth: depth });
                });

                var frames = [];
                try { frames = Array.prototype.slice.call(root.querySelectorAll('iframe,frame')); }
                catch (_) {}
                frames.forEach(function(frame) {
                  var src = '';
                  try { src = frame.getAttribute('src') || frame.src || ''; } catch (_) {}
                  var childUrl = abs(src, frameUrl);
                  var hints = [];
                  addEmbedded(hints, src, frameUrl);
                  var score = frameScore(frame, depth + 1);
                  if (hints.length) score += 500;
                  // A visible media-bearing frame is the best available signal when
                  // the provider cannot inject a document-start script into child frames.
                  found.push({
                    urls: hints,
                    active: hints.length > 0 && visible(frame),
                    score: score,
                    frameUrl: childUrl || frameUrl,
                    depth: depth + 1,
                  });
                  var child = null;
                  try { child = frame.contentDocument; } catch (_) {}
                  if (child) collect(child, childUrl || frameUrl, depth + 1);
                });
              }

              collect(document, location.href, 0);
              found.sort(function(a, b) {
                return b.score - a.score || b.depth - a.depth;
              });
              var best = found.find(function(item) { return item.active && item.urls.length; }) ||
                found.find(function(item) { return item.urls.length; }) || null;
              var urls = [];
              if (best) best.urls.forEach(function(url) { add(urls, url, best.frameUrl); });

              // Keep the newest top-frame resources as a fallback for blob/MSE players.
              try {
                (performance.getEntriesByType('resource') || []).slice(-80).forEach(function(entry) {
                  if (entry && entry.name && isMediaUrl(entry.name)) {
                    add(urls, entry.name, location.href);
                  }
                });
              } catch (_) {}
              return JSON.stringify({
                playing: !!best && !!best.active,
                urls: urls.slice(0, 64),
                frameUrl: best && best.frameUrl ? best.frameUrl : location.href,
                score: best ? best.score : 0
              });
            })();
        """.trimIndent()

        const val SIGNAL_TTL_MS = 3_000L
    }
}
