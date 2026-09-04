package com.mybrowser.media

import android.annotation.SuppressLint
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
        val playbackRate: Float? = null,
    )

    @Volatile
    var isInstalled: Boolean = false
        private set

    private var scriptHandler: ScriptHandler? = null
    private var messageListenerInstalled = false
    @Volatile private var closed = false
    private val signalLock = Any()
    private val frameSignals = linkedMapOf<String, TimedSignal>()
    private var activeReplyProxy: JavaScriptReplyProxy? = null

    private data class TimedSignal(
        val signal: Signal,
        val at: Long,
        val replyProxy: JavaScriptReplyProxy?,
    )

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
                        parseSignal(message.data)?.let { signal ->
                            acceptSignal(signal, replyProxy)
                        }
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

    /**
     * Changes the speed of the strongest currently playing video.
     *
     * The document-start bridge keeps one reply channel per frame, so a player inside a
     * cross-origin iframe can be controlled without exposing a JavaScript interface or
     * evaluating code supplied by the page. Older providers fall back to the top document
     * and any same-origin child frames WebView allows it to inspect.
     */
    fun setPlaybackRate(requestedRate: Float, onResult: (Boolean) -> Unit = {}) {
        val rate = PlaybackSpeed.normalizeSelection(requestedRate)
        if (closed || rate == null) {
            onResult(false)
            return
        }

        val command = JSONObject()
            .put("type", "setPlaybackRate")
            .put("rate", rate.toDouble())
            .toString()
        val replyProxy = synchronized(signalLock) { activeReplyProxy }
        if (replyProxy != null && runCatching { replyProxy.postMessage(command) }.isSuccess) {
            onResult(true)
            return
        }

        val started = runCatching {
            webView.evaluateJavascript(playbackRateFallbackScript(rate)) { raw ->
                if (!closed) onResult(raw?.trim() == "true")
            }
        }.isSuccess
        if (!started) onResult(false)
    }

    private fun evaluateFallbackProbe() {
        if (closed) return
        runCatching {
            webView.evaluateJavascript(FALLBACK_PROBE_SCRIPT) { raw ->
                if (closed) return@evaluateJavascript
                parseSignal(raw)?.let { signal -> acceptSignal(signal, null) }
            }
        }
    }

    /** Drops signals belonging to the document that just navigated. */
    fun reset() {
        synchronized(signalLock) {
            frameSignals.clear()
            activeReplyProxy = null
        }
        if (!closed) {
            onSignal(Signal(isPlaying = false, urls = emptyList(), frameUrl = null))
        }
    }

    fun close() {
        synchronized(signalLock) {
            if (closed) return
            closed = true
            frameSignals.clear()
            activeReplyProxy = null
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
        val playbackRate = PlaybackSpeed.sanitizeObserved(
            json.optDouble("playbackRate", Double.NaN).toFloat(),
        )

        return Signal(
            isPlaying = json.optBoolean("playing", false),
            urls = urls,
            frameUrl = json.optString("frameUrl")
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }
                ?.take(MAX_URL_LENGTH),
            score = json.optInt("score", 0).coerceIn(0, MAX_SCORE),
            playbackRate = playbackRate,
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
    private fun acceptSignal(signal: Signal, replyProxy: JavaScriptReplyProxy?) {
        val active = synchronized(signalLock) {
            if (closed) return
            val now = SystemClock.uptimeMillis()
            val key = signal.frameUrl ?: "<unknown-frame>"
            frameSignals[key] = TimedSignal(signal, now, replyProxy)
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
            val best = frameSignals.values
                .filter { it.signal.isPlaying }
                .maxWithOrNull(
                    compareBy<TimedSignal> { it.signal.score }
                        .thenBy { it.signal.urls.size },
                )
            activeReplyProxy = best?.replyProxy
            best?.signal
        }
        if (active != null) {
            onSignal(active)
        } else {
            onSignal(Signal(isPlaying = false, urls = emptyList(), frameUrl = signal.frameUrl))
        }
    }

    private fun playbackRateFallbackScript(rate: Float): String = """
        (function() {
          var requestedRate = ${rate.toDouble()};
          var supported = [0.5, 0.75, 1, 1.25, 1.5, 2, 3];
          if (!Number.isFinite(requestedRate) || !supported.some(function(value) {
                return Math.abs(value - requestedRate) < 0.001;
              })) return false;

          function isVideo(element) {
            return element && String(element.tagName || '').toLowerCase() === 'video';
          }

          function apply(video, targetRate) {
            if (!isVideo(video) || !Number.isFinite(targetRate)) return false;
            try {
              video.defaultPlaybackRate = targetRate;
              video.playbackRate = targetRate;
              return Math.abs(Number(video.playbackRate) - targetRate) < 0.001;
            } catch (_) { return false; }
          }

          function visibleArea(video) {
            try {
              var style = video.ownerDocument.defaultView.getComputedStyle(video);
              var rect = video.getBoundingClientRect();
              if (style.display === 'none' || style.visibility === 'hidden') return 0;
              return Math.max(0, rect.width * rect.height);
            } catch (_) { return 0; }
          }

          var candidates = [];
          function install(win, depth) {
            if (!win || depth > 5) return;
            var doc = null;
            try { doc = win.document; } catch (_) { return; }
            if (!doc) return;

            try { win.__pureBrowserPlaybackRate = requestedRate; } catch (_) {}
            try {
              if (!win.__pureBrowserPlaybackRateHooks) {
                win.__pureBrowserPlaybackRateHooks = true;
                ['play', 'playing', 'loadedmetadata', 'canplay', 'ratechange'].forEach(function(name) {
                  doc.addEventListener(name, function(event) {
                    var desired = Number(win.__pureBrowserPlaybackRate);
                    if (isVideo(event.target) && Number.isFinite(desired) &&
                        Math.abs(Number(event.target.playbackRate) - desired) >= 0.001) {
                      apply(event.target, desired);
                    }
                  }, true);
                });
              }
            } catch (_) {}

            try {
              Array.prototype.slice.call(doc.querySelectorAll('video')).forEach(function(video) {
                var active = false;
                try { active = !video.paused && !video.ended; } catch (_) {}
                if (active) candidates.push({ video: video, area: visibleArea(video) });
              });
            } catch (_) {}
            try {
              Array.prototype.slice.call(doc.querySelectorAll('iframe,frame')).forEach(function(frame) {
                var child = null;
                try { child = frame.contentWindow; } catch (_) {}
                if (child) install(child, depth + 1);
              });
            } catch (_) {}
          }

          install(window, 0);
          candidates.sort(function(first, second) { return second.area - first.area; });
          return candidates.length > 0 && apply(candidates[0].video, requestedRate);
        })();
    """.trimIndent()

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
              var desiredPlaybackRate = null;

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

              function validPlaybackRate(value) {
                var rate = Number(value);
                if (!Number.isFinite(rate)) return null;
                var supported = [0.5, 0.75, 1, 1.25, 1.5, 2, 3];
                for (var i = 0; i < supported.length; i++) {
                  if (Math.abs(supported[i] - rate) < 0.001) return supported[i];
                }
                return null;
              }

              function applyPlaybackRate(video) {
                if (!video || desiredPlaybackRate === null) return false;
                try {
                  video.defaultPlaybackRate = desiredPlaybackRate;
                  video.playbackRate = desiredPlaybackRate;
                  return Math.abs(Number(video.playbackRate) - desiredPlaybackRate) < 0.001;
                } catch (_) { return false; }
              }

              function attach(video) {
                if (video.__mybrowserMediaProbeAttached) return;
                video.__mybrowserMediaProbeAttached = true;
                ['play', 'playing', 'timeupdate', 'loadedmetadata', 'canplay',
                 'pause', 'ended', 'emptied', 'durationchange', 'ratechange'].forEach(function(name) {
                  try {
                    video.addEventListener(name, function() {
                      if (desiredPlaybackRate !== null) applyPlaybackRate(video);
                      schedule();
                    });
                  } catch (_) {}
                });
                if (desiredPlaybackRate !== null) applyPlaybackRate(video);
              }

              function applyToPlayingVideo() {
                var videos = [];
                try { videos = Array.prototype.slice.call(document.querySelectorAll('video')); }
                catch (_) {}
                videos.forEach(attach);
                videos = videos.filter(function(video) {
                  try { return !video.paused && !video.ended; }
                  catch (_) { return false; }
                });
                videos.sort(function(first, second) {
                  function area(video) {
                    try {
                      if (!visible(video)) return 0;
                      var rect = video.getBoundingClientRect();
                      return rect.width * rect.height;
                    } catch (_) { return 0; }
                  }
                  return area(second) - area(first);
                });
                return videos.length > 0 && applyPlaybackRate(videos[0]);
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
                  var playbackRate = null;
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
                    playbackRate = Number(video.playbackRate);
                    if (!Number.isFinite(playbackRate)) playbackRate = null;
                  } catch (_) {}
                  if (visible(video)) score += 150;
                  try {
                    var rect = video.getBoundingClientRect();
                    score += Math.min(150, Math.max(0, rect.width * rect.height / 10000));
                  } catch (_) {}
                  output.push({
                    urls: urls,
                    score: score,
                    active: active,
                    frameUrl: frameUrl,
                    playbackRate: playbackRate
                  });
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
                  score: best ? best.score : 0,
                  playbackRate: best ? best.playbackRate : null
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

              // JavaScriptReplyProxy sends native commands back only to the frame that
              // reported the strongest playing video. The command contains one bounded
              // numeric rate and never evaluates text supplied by the page.
              try {
                var commandBridge = window[bridgeName];
                if (commandBridge) {
                  commandBridge.onmessage = function(event) {
                    var command = null;
                    try { command = JSON.parse(String(event && event.data || '')); }
                    catch (_) { return; }
                    if (!command || command.type !== 'setPlaybackRate') return;
                    var rate = validPlaybackRate(command.rate);
                    if (rate === null) return;
                    desiredPlaybackRate = rate;
                    applyToPlayingVideo();
                    post();
                  };
                }
              } catch (_) {}

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
                  var playbackRate = null;
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
                    playbackRate = Number(video.playbackRate);
                    if (!Number.isFinite(playbackRate)) playbackRate = null;
                    var rect = video.getBoundingClientRect();
                    if (visible(video)) {
                      score += 150;
                      score += Math.min(150, Math.max(0, rect.width * rect.height / 10000));
                    }
                  } catch (_) {}
                  found.push({
                    urls: urls,
                    active: active,
                    score: score,
                    frameUrl: frameUrl,
                    depth: depth,
                    playbackRate: playbackRate
                  });
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
                score: best ? best.score : 0,
                playbackRate: best ? best.playbackRate : null
              });
            })();
        """.trimIndent()

        const val SIGNAL_TTL_MS = 3_000L
    }
}
