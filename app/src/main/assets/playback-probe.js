(function installPureVideo(win) {
  'use strict';
  if (win.__pureBrowserVideoV2) return win.__pureBrowserVideoV2;
  var doc = win.document, bridge = win.mybrowserMediaProbe;
  var frameId = Date.now().toString(36) + Math.random().toString(36).slice(2);
  var ids = new WeakMap(), speeds = new WeakMap(), nextId = 0;
  var selected = null, boost = null, nativeControls = null, disposed = false, suspended = false;
  var scheduled = null, pulse = null, observer = null;
  // The picture fit and mirror the user chose for the video the takeover owns. The rules live in
  // the takeover's own stylesheet, so leaving fullscreen drops them; this is only what to write
  // when that stylesheet is built or rebuilt.
  var transform = null, resizeHandler = null, resizeRetry = null, resizeRetryCount = 0;
  // The last error a command hit, reported in every state message so the app can log a refused
  // takeover once instead of leaving the user with a silent fallback to the page's own controls.
  var lastCommandError = '';
  var controlsAttribute = 'data-pure-browser-controls', stageAttribute = 'data-pure-browser-stage';
  var rootAttribute = 'data-pure-browser-fullscreen', rejectedControls = null;
  var hasOwn = Function.call.bind(Object.prototype.hasOwnProperty);
  var MIN_RATE = 0.5, MAX_RATE = 5;
  var buffering = false, lastPosition = 0, lastPositionAt = 0, seekingSince = 0;
  function finite(value, fallback) { return Number.isFinite(Number(value)) ? Number(value) : fallback; }
  // One grid for the slider and the page: 0.1 steps inside the browser's range.
  function validRate(value) {
    return Number.isFinite(value) && value >= MIN_RATE && value <= MAX_RATE &&
      Math.abs(value * 10 - Math.round(value * 10)) < 1e-4;
  }
  function id(video) {
    if (!ids.has(video)) ids.set(video, 'v' + (++nextId));
    return ids.get(video);
  }
  function videos() { return Array.prototype.slice.call(doc.querySelectorAll('video,audio'), 0, 64); }
  // A WebView exposes no byte counter for a media response, so the only stream-scoped source is
  // what the page itself feeds a SourceBuffer. Counting appends is enough to tell how fast this
  // page's stream is arriving; progressive downloads fall back to the native meter.
  var appendOriginal = null, addSourceBufferOriginal = null, createObjectUrlOriginal = null;
  // A page can run several players at once (ads, preloads). Bytes are therefore counted per
  // SourceBuffer and summed only for the buffers of the MediaSource this video is showing, so a
  // second player's traffic never lands in this video's rate. The registry is small and bounded:
  // pages create a handful of MediaSources, and the oldest entry is dropped past that.
  var sourceBytes = new WeakMap();
  var sourceMedia = new WeakMap();
  var mediaRegistry = [];
  function mediaEntry(media) {
    for (var i = 0; i < mediaRegistry.length; i++) {
      if (mediaRegistry[i].media === media) return mediaRegistry[i];
    }
    var entry = { media: media, url: null, buffers: [] };
    mediaRegistry.unshift(entry);
    if (mediaRegistry.length > 8) mediaRegistry.pop();
    return entry;
  }
  function registryEntryFor(url) {
    if (!url) return null;
    for (var i = 0; i < mediaRegistry.length; i++) {
      if (mediaRegistry[i].url === url) return mediaRegistry[i];
    }
    return null;
  }
  function watchDelivery() {
    try {
      var sourcePrototype = win.SourceBuffer && win.SourceBuffer.prototype;
      if (sourcePrototype && typeof sourcePrototype.appendBuffer === 'function' &&
          !sourcePrototype.appendBuffer.__pureBrowser) {
        appendOriginal = sourcePrototype.appendBuffer;
        var append = appendOriginal;
        var counted = function(data) {
          try {
            if (data && data.byteLength > 0) {
              var buffer = this;
              sourceBytes.set(buffer, (sourceBytes.get(buffer) || 0) + data.byteLength);
            }
          } catch (_) {}
          return append.apply(this, arguments);
        };
        counted.__pureBrowser = true;
        sourcePrototype.appendBuffer = counted;
      }
      var mediaPrototype = win.MediaSource && win.MediaSource.prototype;
      if (mediaPrototype && typeof mediaPrototype.addSourceBuffer === 'function' &&
          !mediaPrototype.addSourceBuffer.__pureBrowser) {
        addSourceBufferOriginal = mediaPrototype.addSourceBuffer;
        var add = addSourceBufferOriginal;
        var tracked = function() {
          var buffer = add.apply(this, arguments);
          try {
            var entry = mediaEntry(this);
            if (entry) { entry.buffers.push(buffer); sourceMedia.set(buffer, this); }
          } catch (_) {}
          return buffer;
        };
        tracked.__pureBrowser = true;
        mediaPrototype.addSourceBuffer = tracked;
      }
      if (win.URL && typeof win.URL.createObjectURL === 'function' &&
          !win.URL.createObjectURL.__pureBrowser) {
        createObjectUrlOriginal = win.URL.createObjectURL;
        var create = createObjectUrlOriginal;
        var mapped = function(object) {
          var url = create.apply(this, arguments);
          try {
            if (win.MediaSource && object instanceof win.MediaSource) mediaEntry(object).url = url;
          } catch (_) {}
          return url;
        };
        mapped.__pureBrowser = true;
        win.URL.createObjectURL = mapped;
      }
    } catch (_) {}
  }
  function unwatchDelivery() {
    var pairs = [
      [win.SourceBuffer && win.SourceBuffer.prototype, "appendBuffer", appendOriginal],
      [win.MediaSource && win.MediaSource.prototype, "addSourceBuffer", addSourceBufferOriginal],
      [win.URL, "createObjectURL", createObjectUrlOriginal],
    ];
    for (var i = 0; i < pairs.length; i++) {
      try {
        var target = pairs[i][0], name = pairs[i][1], original = pairs[i][2];
        if (original && target && target[name] && target[name].__pureBrowser) target[name] = original;
      } catch (_) {}
    }
    appendOriginal = null; addSourceBufferOriginal = null; createObjectUrlOriginal = null;
  }
  // Bytes this video's own delivery has produced. Zero means the page exposes no counter, and the
  // native meter then falls back to its process-wide sample instead of reporting a guess.
  function deliveryBytes(video, includeTiming) {
    var total = 0;
    try {
      if (video) {
        var entry = registryEntryFor(video.currentSrc) || registryEntryFor(video.src);
        if (entry) {
          for (var i = 0; i < entry.buffers.length; i++) {
            total += sourceBytes.get(entry.buffers[i]) || 0;
          }
        }
        if (total === 0 && includeTiming) {
          // Progressive delivery has no page-side byte counter; a completed resource entry is the
          // only number available, and only when the response allows timing.
          var sources = [video.currentSrc, video.src];
          (win.performance.getEntriesByType('resource') || []).forEach(function(entry) {
            if (entry.transferSize > 0 && sources.indexOf(entry.name) >= 0) total += entry.transferSize;
          });
        }
      }
    } catch (_) {}
    return total;
  }
  // `waiting`/`stalled` are not dependable, so a silent stall is caught by watching the clock:
  // a playing video whose time does not advance cannot deliver frames.
  function updateBuffering(video) {
    var now = Date.now();
    if (!video || video.paused || video.ended || suspended) {
      buffering = false; seekingSince = 0;
      lastPosition = video ? video.currentTime : 0; lastPositionAt = now; return;
    }
    if (video.seeking) {
      // `seeking` stays true until the target position has data, so a jump into an unbuffered
      // stretch looks exactly like a stall and should be reported as one once it is not instant.
      if (seekingSince === 0) { seekingSince = now; return; }
      if (now - seekingSince > 700 && video.readyState < 3) buffering = true;
      lastPositionAt = now;
      return;
    }
    seekingSince = 0;
    if (lastPositionAt === 0) { lastPosition = video.currentTime; lastPositionAt = now; return; }
    if (video.currentTime - lastPosition > 0.2) {
      buffering = false; lastPosition = video.currentTime; lastPositionAt = now; return;
    }
    if (now - lastPositionAt > 1500 && video.readyState < 3) buffering = true;
  }
  function visible(video) {
    try {
      var rect = video.getBoundingClientRect(), style = win.getComputedStyle(video);
      return rect.width > 1 && rect.height > 1 && style.display !== 'none' && style.visibility !== 'hidden';
    } catch (_) { return false; }
  }
  function fullscreenRoot(video) {
    var root = doc.fullscreenElement || doc.webkitFullscreenElement;
    if (root && (root === video || root.contains(video))) return root;
    return video.webkitDisplayingFullscreen ? video : null;
  }
  function fullscreen(video) { return !!fullscreenRoot(video); }
  function controlPath(video, root) {
    var path = [], element = video;
    while (element && path.length < 64) {
      path.push(element);
      if (element === root) return path;
      element = element.parentElement;
    }
    return null;
  }
  function canUseNativeControls(video) {
    if (!video || String(video.tagName).toLowerCase() !== 'video') return false;
    var root = fullscreenRoot(video);
    if (!root || video.readyState < 1 || !video.videoWidth || !video.videoHeight) return false;
    if (rejectedControls && rejectedControls.video === video && rejectedControls.root === root) return false;
    // Control the loaded element, including a site's custom container and MSE player.
    // Neither a controls attribute, a hostname nor an unrelated network URL owns playback.
    return !!controlPath(video, root);
  }
  function rank(video) {
    var score = fullscreen(video) ? 10000 : 0;
    if (!video.paused && !video.ended) score += 1000;
    if (visible(video)) score += 300;
    if (video === selected) score += 60;
    if (video.readyState >= 2) score += 100;
    if (video.currentTime > 0) score += 80;
    if (!video.muted) score += 25;
    try {
      var rect = video.getBoundingClientRect();
      score += Math.min(150, Math.max(0, rect.width * rect.height / 10000));
    } catch (_) {}
    return score;
  }
  function pick() {
    var found = videos();
    if (nativeControls && found.indexOf(nativeControls.video) >= 0 &&
        fullscreenRoot(nativeControls.video) === nativeControls.root) {
      return nativeControls.video;
    }
    found.sort(function(a, b) { return rank(b) - rank(a); });
    return found[0] || null;
  }
  function addUrl(list, value) {
    if (!value) return;
    try {
      var url = new URL(String(value || ''), doc.baseURI).href;
      if (!/^(https?:|blob:)/i.test(url) || url.length > 8192 || /\.(ts|m4s)(?:[?#]|$)/i.test(url)) return;
      if (list.length < 64 && list.indexOf(url) < 0) list.push(url);
      return url;
    } catch (_) {}
  }
  function snapshot() {
    if (nativeControls && !controlsIntact(nativeControls)) {
      rejectedControls = { video: nativeControls.video, root: nativeControls.root };
      restoreBoost(); restoreControls();
    }
    var video = pick(), urls = [], sourceUrl = null, rangeStart = 0, rangeEnd = 0;
    updateBuffering(video);
    if (video) {
      selected = video;
      sourceUrl = addUrl(urls, video.currentSrc) || null;
      if (!sourceUrl) addUrl(urls, video.src);
      Array.prototype.slice.call(video.querySelectorAll('source'), 0, 16).forEach(function(source) {
        addUrl(urls, source.src || source.getAttribute('src'));
      });
      try {
        if (video.seekable.length) {
          rangeStart = video.seekable.start(0);
          rangeEnd = video.seekable.end(video.seekable.length - 1);
        }
      } catch (_) {}
    }
    try {
      (win.performance.getEntriesByType('resource') || []).slice(-80).reverse().forEach(function(entry) {
        if (/(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mov|manifest|playlist)(?:[?#]|$)/i.test(entry.name)) addUrl(urls, entry.name);
      });
    } catch (_) {}
    return {
      type: 'state', frameId: frameId, frameUrl: win.location.href,
      videoId: video ? id(video) : null, hasMedia: !!video,
      // An element can remain in the DOM after its player is closed or unloaded.
      // Keep it controllable in-page, but stop advertising a resumable system session.
      playbackAvailable: !!video && video.readyState >= 1 && !video.error && !video.ended &&
        (!video.paused || String(video.tagName).toLowerCase() === 'audio' || visible(video)),
      hasVideo: !!video && String(video.tagName).toLowerCase() === 'video', muted: !!video && video.muted,
      playing: !!video && !video.paused && !video.ended,
      fullscreen: !!video && fullscreen(video), score: video ? rank(video) : 0,
      nativeControlsAvailable: canUseNativeControls(video),
      urls: urls, sourceUrl: sourceUrl, playbackRate: video ? finite(video.playbackRate, 1) : null,
      position: video ? Math.max(0, finite(video.currentTime, 0)) : 0,
      duration: video ? Math.max(0, finite(video.duration, 0)) : 0,
      seekStart: Math.max(0, finite(rangeStart, 0)), seekEnd: Math.max(0, finite(rangeEnd, 0)),
      width: video ? video.videoWidth : 0, height: video ? video.videoHeight : 0,
      boosting: !!boost && boost.video === video,
      buffering: buffering && !!video && !video.paused && !video.ended,
      // The resource-timing scan only runs while a rate is actually being shown.
      receivedBytes: deliveryBytes(video, buffering),
      lastCommandError: lastCommandError
    };
  }
  function emit(message) {
    try { if (bridge && bridge.postMessage) bridge.postMessage(JSON.stringify(message)); } catch (_) {}
  }
  function post() {
    if (disposed) return;
    if (scheduled !== null) win.clearTimeout(scheduled);
    scheduled = null;
    var state = snapshot();
    emit(state);
    if (pulse !== null) win.clearTimeout(pulse);
    pulse = null;
    // No recurring DOM scan on pages without video or while the document is hidden.
    if (state.hasMedia && (!doc.hidden || state.playing)) pulse = win.setTimeout(post, 1000);
  }
  function schedule() {
    if (!disposed && scheduled === null) scheduled = win.setTimeout(post, 250);
  }
  function restoreBoost() {
    if (!boost) return true;
    var saved = boost;
    boost = null;
    try {
      saved.video.playbackRate = saved.rate;
      // Never write defaultPlaybackRate for a temporary gesture.
      return Math.abs(saved.video.playbackRate - saved.rate) < 0.001;
    } catch (_) { return false; }
  }
  function restoreControls() {
    if (nativeControls) {
      var saved = nativeControls;
      nativeControls = null;
      if (saved.observer) saved.observer.disconnect();
      saved.marks.forEach(function(mark) {
        try {
          if (mark.value === null) mark.element.removeAttribute(mark.name);
          else mark.element.setAttribute(mark.name, mark.value);
        } catch (_) {}
      });
      try { saved.video.controls = saved.controls; } catch (_) {}
      if (saved.suppressRotation) {
        try {
          if (saved.controlsList === null) saved.video.removeAttribute('controlslist');
          else saved.video.setAttribute('controlslist', saved.controlsList);
        } catch (_) {}
      }
      try { saved.style.remove(); } catch (_) {}
      if (saved.mirrorVideo) {
        try {
          if (saved.mirrorValue) saved.mirrorVideo.style.setProperty(saved.mirrorProperty, saved.mirrorValue, saved.mirrorPriority);
          else saved.mirrorVideo.style.removeProperty(saved.mirrorProperty);
        } catch (_) {}
      }
    }
    // The preset rules lived in that stylesheet, so nothing is left to keep sized; the choice
    // itself stays until the page or the app replaces it, so re-entering fullscreen restores it.
    refreshTransform();
  }
  function controlsIntact(saved) {
    try {
      if (saved.video.isConnected === false || saved.video.controls ||
          fullscreenRoot(saved.video) !== saved.root || saved.style.isConnected === false ||
          !saved.style.sheet || !saved.style.sheet.cssRules.length) return false;
      if (saved.suppressRotation && !/(^|\s)nofullscreen(\s|$)/.test(saved.video.getAttribute('controlslist') || '')) return false;
      if (saved.marks.some(function(mark) { return mark.element.getAttribute(mark.name) !== saved.marker; })) return false;
      var path = controlPath(saved.video, saved.root);
      if (!path || path.length !== saved.path.length || path.some(function(node, i) { return node !== saved.path[i]; })) return false;
      if (path.length === 1) return true;
      var rect = saved.video.getBoundingClientRect(), rootRect = saved.root.getBoundingClientRect();
      var style = win.getComputedStyle(saved.video);
      if (style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) === 0 ||
          rect.width < 1 || rect.height < 1 || Math.abs(rect.left - rootRect.left) > 2 ||
          Math.abs(rect.top - rootRect.top) > 2 || Math.abs(rect.width - rootRect.width) > 2 ||
          Math.abs(rect.height - rootRect.height) > 2) return false;
      // Check that site !important rules did not leave a second visible control layer.
      var checked = 0;
      for (var i = 1; i < path.length; i++) {
        var children = path[i].children;
        for (var j = 0; j < children.length; j++) {
          if (++checked > 512) return false;
          var child = children[j];
          if (child === path[i - 1]) continue;
          var css = win.getComputedStyle(child);
          if (css.display !== 'none' && css.visibility !== 'hidden' && css.visibility !== 'collapse') return false;
        }
      }
      return true;
    } catch (_) { return false; }
  }
  // The takeover pins the video to the viewport with its own !important rules, so a fit or a
  // mirror the user asked for has to be written into that same stylesheet: it must outrank the
  // `transform:none` and `object-fit:contain` the takeover declares, and it must disappear with
  // the takeover rather than linger on the page.
  //
  // The ratio presets keep the element's border box exactly where the takeover put it and fit the
  // picture into the padding box instead of shrinking the element, and a mirror is a transform
  // about the centre, which leaves the bounding box untouched. Both the takeover's own layout
  // check and the device regression read "the video element still covers the screen" as the sign
  // that custom controls own the document, so a preset that moved the element would look like a
  // broken takeover and get the page's own controls back.
  function fitRatio(fit) {
    if (fit === 'RATIO_3_4') return 3 / 4;
    if (fit === 'RATIO_16_9') return 16 / 9;
    return 0;
  }
  function validFit(fit) {
    return fit === 'NATURAL' || fit === 'FILL' || fit === 'RATIO_3_4' || fit === 'RATIO_16_9';
  }
  function transformRules(selector, root, state) {
    if (!state || (!state.mirror && state.fit === 'NATURAL')) return '';
    var text = '', ratio = fitRatio(state.fit);
    if (state.fit === 'FILL') {
      // Cover crops instead of letterboxing, which is what filling the screen means for a source
      // whose ratio differs from the viewport's.
      text += 'object-fit:cover!important;';
    } else if (ratio > 0) {
      // `fill` stretches the picture into the ratio box instead of letterboxing it inside: picking
      // a ratio asks to see the picture at that shape, the way a television's picture-size override
      // works, and a source whose own ratio differs would otherwise only look smaller.
      text += 'box-sizing:border-box!important;object-fit:fill!important;';
      // The box a preset is fitted into is the viewport, not the element's own client box. The
      // takeover pins the element to the viewport, so the two agree once the layout has settled —
      // but the element's box also *contains* the padding this feature wrote, so reading it back is
      // reading its own output. A rotation reports it mid-layout (measured on an API 37 device:
      // 606x914 against a 411x914 portrait viewport, the stale landscape padding plus a collapsed
      // content box), and the padding derived from that is what left the picture stretched into a
      // box nothing sized. The viewport was correct throughout, so it is what the ratio uses.
      var width = win.innerWidth || (root.clientWidth || 0);
      var height = win.innerHeight || (root.clientHeight || 0);
      if (width > 0 && height > 0) {
        var boxWidth = Math.min(width, height * ratio);
        text += 'padding:' + ((height - boxWidth / ratio) / 2).toFixed(2) + 'px ' +
          ((width - boxWidth) / 2).toFixed(2) + 'px!important;';
      }
    }
    // The mirror deliberately stays out of this sheet: a page stylesheet with a more specific
    // selector outranks it, which is exactly why the flip did nothing on pages that style their
    // own player. It is written on the element as an inline `!important` instead — the one
    // declaration no stylesheet can outrank — by applyMirror().
    return text ? selector + '{' + text + '}' : '';
  }
  /**
   * The declarations a mirror is written with, in the order they are tried on each element.
   *
   * `transform` is the obvious one and works on a video inside someone else's fullscreen wrapper.
   * It cannot work on the fullscreen element itself: while an element is fullscreen the UA
   * stylesheet forces `transform` (and `rotate`, and `filter`) to `none` with `!important`, and an
   * origin-important rule outranks author `!important` — inline or not. Chromium used to fullscreen
   * the video itself, which is how a page whose player goes fullscreen on its own video ends up
   * with a flip that is accepted by the style object and then dropped by the renderer. The
   * individual `scale` property is a separate one the UA reset does not cover, and it flips the
   * element about its centre exactly as `scaleX(-1)` does, so it is the one that reaches the screen
   * there.
   */
  var MIRROR_DECLARATIONS = [
    { property: 'transform', value: 'scaleX(-1)' },
    { property: 'scale', value: '-1 1' }
  ];
  /**
   * How a rotation that reports no measurable box is handled.
   *
   * A rotation can report the viewport (and the element) at 0x0 for a frame while the new layout is
   * being committed. Writing the sheet then drops the padding and stretches the picture into a box
   * nothing sized, and no further resize event is guaranteed to arrive: the picture stays wrong
   * until the user picks a preset again. Ask again shortly instead, a bounded number of times so a
   * page that never reports a size keeps no timer.
   */
  var RESIZE_RETRY_LIMIT = 3, RESIZE_RETRY_DELAY = 250;
  /** The viewport is the box a preset is fitted into; the element's own box is the fallback for a
   * page that reports no viewport size at all. */
  function usableBox(root) {
    if ((win.innerWidth || 0) > 0 && (win.innerHeight || 0) > 0) return true;
    return !!root && (root.clientWidth || 0) > 0 && (root.clientHeight || 0) > 0;
  }
  /**
   * The elements a mirror may be written on, innermost first.
   *
   * The video is the natural target, but a page can refuse the declaration on it: a selector with
   * an id or its own `!important`, a frozen style object, or a player that paints the video outside
   * the element. The wrapper the fullscreen takeover already owns is the next place the same flip
   * has the same effect on screen, and it is not the element a page's player rules are written
   * against.
   */
  function mirrorChain() {
    var saved = nativeControls, video = saved && saved.video;
    if (!video) return [];
    var chain = [video], node = video;
    while (node && node !== saved.root && chain.length < 8) {
      node = node.parentElement;
      if (node && chain.indexOf(node) < 0) chain.push(node);
    }
    if (saved.root && chain.indexOf(saved.root) < 0) chain.push(saved.root);
    return chain.filter(function(element) { return element && element.style; });
  }
  function setInlineDeclaration(element, property, value, priority) {
    try {
      if (value) element.style.setProperty(property, value, priority);
      else element.style.removeProperty(property);
      return true;
    } catch (_) { return false; }
  }
  /** What the style engine resolved, which is not what was asked for: the two differ on a
   * fullscreen element, and only this side of the call can see it. */
  function computedProperty(element, property) {
    try { return String(win.getComputedStyle(element)[property] || ''); } catch (_) { return 'unknown'; }
  }

  /**
   * Applies or clears the mirror, remembering which element and which property carry it, and what
   * that property held before.
   *
   * Inline `!important` is the one declaration a stylesheet cannot outrank, so the video is tried
   * first; each candidate is read back before it is accepted, because what the style engine
   * resolves is the only thing this side can check. Nothing here reports success it did not see: a
   * mirror that no layer accepts is reported as a failure, and the reason is recorded for a bug
   * report.
   */
  function applyMirror(on) {
    var saved = nativeControls;
    if (!saved) return true;
    if (!on) {
      if (saved.mirrorVideo) {
        setInlineDeclaration(saved.mirrorVideo, saved.mirrorProperty, saved.mirrorValue, saved.mirrorPriority);
        saved.mirrorVideo = null;
        saved.mirrorProperty = '';
        saved.mirrorValue = '';
        saved.mirrorPriority = '';
      }
      return true;
    }
    if (saved.mirrorVideo) return true;
    var chain = mirrorChain();
    for (var i = 0; i < chain.length; i++) {
      var element = chain[i];
      for (var j = 0; j < MIRROR_DECLARATIONS.length; j++) {
        var declaration = MIRROR_DECLARATIONS[j];
        var previousValue = element.style.getPropertyValue(declaration.property);
        var previousPriority = element.style.getPropertyPriority(declaration.property);
        if (!setInlineDeclaration(element, declaration.property, declaration.value, 'important')) continue;
        var computed = computedProperty(element, declaration.property);
        if (computed !== 'none' && computed !== '') {
          saved.mirrorVideo = element;
          saved.mirrorProperty = declaration.property;
          // A value of our own is not this element's original, so it is not what to restore later.
          saved.mirrorValue = previousValue === declaration.value ? '' : previousValue;
          saved.mirrorPriority = saved.mirrorValue ? previousPriority : '';
          return true;
        }
        setInlineDeclaration(element, declaration.property, previousValue, previousPriority);
      }
    }
    return false;
  }

  function refreshTransform() {
    var saved = nativeControls;
    if (saved && saved.baseStyle) {
      saved.style.textContent = saved.baseStyle +
        transformRules(saved.selector, saved.root, transform);
    }
    // The mirror lives on the element rather than in the sheet, so it is (re)applied here: at
    // takeover, on every choice, and after a rotation.
    var mirrored = !saved || applyMirror(!!transform && !!transform.mirror);
    // A rotation changes the viewport, and a ratio preset is derived from it. The listener is
    // registered while a ratio is in force and released when it is not — including when a takeover
    // ends, because restoreControls() and dispose() both call this with no takeover left.
    var needs = !!transform && fitRatio(transform.fit) > 0 && !!saved;
    if (needs && !resizeHandler) {
      resizeHandler = function() { refreshTransform(); };
      win.addEventListener('resize', resizeHandler);
      win.addEventListener('orientationchange', resizeHandler);
    } else if (!needs && resizeHandler) {
      win.removeEventListener('resize', resizeHandler);
      win.removeEventListener('orientationchange', resizeHandler);
      resizeHandler = null;
    }
    if (needs && !usableBox(saved.root)) {
      if (resizeRetry === null && resizeRetryCount < RESIZE_RETRY_LIMIT) {
        resizeRetryCount++;
        resizeRetry = win.setTimeout(function() { resizeRetry = null; refreshTransform(); }, RESIZE_RETRY_DELAY);
      }
    } else resizeRetryCount = 0;
    return mirrored;
  }
  function hideControls(video) {
    var root = fullscreenRoot(video), path = controlPath(video, root);
    if (!path) return false;
    if (!nativeControls || nativeControls.video !== video || nativeControls.root !== root) {
      restoreControls();
      var style = doc.createElement('style'), marker = frameId + '-' + id(video);
      var saved = nativeControls = { video: video, root: root, path: path, controls: video.controls,
        controlsList: video.getAttribute('controlslist'), suppressRotation: root === video,
        marks: [], marker: marker, style: style, observer: null, baseStyle: '', selector: '',
        mirrorVideo: null, mirrorProperty: '', mirrorValue: '', mirrorPriority: '' };
      function mark(element, name) {
        saved.marks.push({ element: element, name: name, value: element.getAttribute(name) });
        element.setAttribute(name, marker);
      }
      // Chromium can force its UA controls in fullscreen even with controls=false.
      // Scope suppression to this element, and remove both marker and style on exit.
      // Its rotate-to-fullscreen delegate also stays active with controls=false.
      // While native controls own fullscreen, prevent the launcher orientation
      // change during Activity PiP from making Chromium exit video fullscreen.
      if (saved.suppressRotation && !/(^|\s)nofullscreen(\s|$)/.test(saved.controlsList || '')) {
        video.setAttribute('controlslist', (saved.controlsList ? saved.controlsList + ' ' : '') + 'nofullscreen');
      }
      var selector = 'video[' + controlsAttribute + '="' + marker + '"]';
      var base = selector + '::-webkit-media-controls{display:none!important}' +
        selector + '::-webkit-media-controls-enclosure{display:none!important}';
      mark(video, controlsAttribute);
      if (root !== video) {
        var stage = '[' + stageAttribute + '="' + marker + '"]';
        var stageRoot = '[' + rootAttribute + '="' + marker + '"]';
        path.slice(1).forEach(function(element) { mark(element, stageAttribute); });
        mark(root, rootAttribute);
        // Keep the media node, source, event listeners and decoder in place. Hide sibling
        // branches (including controls added later) and remove containing-block constraints
        // along its ancestry so a nested, transformed video fills the fullscreen viewport.
        base += stage + '> :not(' + stage + '):not(' + selector + '){display:none!important}' +
          stage + '::before,' + stage + '::after{display:none!important;content:none!important}' +
          stage + '{display:block!important;position:static!important;transform:none!important;' +
          'translate:none!important;rotate:none!important;scale:none!important;perspective:none!important;' +
          'filter:none!important;backdrop-filter:none!important;contain:none!important;will-change:auto!important;' +
          'content-visibility:visible!important;clip:auto!important;clip-path:none!important;mask:none!important;' +
          'overflow:visible!important;opacity:1!important;visibility:hidden!important;pointer-events:none!important;}' +
          stageRoot + '{position:fixed!important;top:0!important;right:0!important;bottom:0!important;left:0!important;width:100%!important;height:100%!important;' +
          'margin:0!important;padding:0!important;border:0!important;max-width:none!important;max-height:none!important;' +
          'background:#000!important;overflow:hidden!important;}' +
          selector + '{position:fixed!important;top:0!important;right:0!important;bottom:0!important;left:0!important;width:100%!important;height:100%!important;' +
          'min-width:0!important;min-height:0!important;max-width:none!important;max-height:none!important;' +
          'margin:0!important;padding:0!important;border:0!important;box-sizing:border-box!important;' +
          'object-fit:contain!important;object-position:center!important;transform:none!important;' +
          'translate:none!important;rotate:none!important;scale:none!important;clip:auto!important;clip-path:none!important;' +
          'display:block!important;visibility:visible!important;opacity:1!important;z-index:2147483647!important;' +
          'background:#000!important;pointer-events:none!important;}';
      }
      saved.baseStyle = base;
      saved.selector = selector;
      // The rules go in before the element is attached: a style element with no text yet has no
      // sheet, and an empty sheet is this function's signal that the page refuses the takeover.
      style.textContent = base;
      (doc.head || doc.documentElement).appendChild(style);
      if (!style.sheet || !style.sheet.cssRules.length) throw new Error('Control styles unavailable');
      // Rewritten afterwards so an equal-specificity preset wins over the base rules.
      refreshTransform();
      saved.observer = new win.MutationObserver(schedule);
      saved.observer.observe(root, { childList: true, subtree: true, attributes: true,
        attributeFilter: ['class', 'style', 'controls', 'controlslist', controlsAttribute, stageAttribute, rootAttribute] });
    }
    video.controls = false;
    if (!controlsIntact(nativeControls)) throw new Error('Fullscreen layout cannot be isolated');
    return true;
  }
  function seek(video, requested) {
    if (!Number.isFinite(requested) || !Number.isFinite(video.duration) || video.duration <= 0 || !video.seekable.length) return false;
    // Clamp to a real buffered/seekable range, including streams with discontinuities.
    var target = Math.max(0, Math.min(requested, video.duration)), closest = null, distance = Infinity;
    for (var i = 0; i < video.seekable.length; i++) {
      var start = video.seekable.start(i), end = video.seekable.end(i);
      var candidate = Math.max(start, Math.min(target, end));
      if (Math.abs(candidate - target) < distance) { closest = candidate; distance = Math.abs(candidate - target); }
    }
    if (closest === null) return false;
    video.currentTime = closest;
    return true;
  }
  function command(message, complete) {
    complete = complete || function() {};
    if (disposed || !message || message.frameId !== frameId) { complete(false); return false; }
    var video = videos().find(function(item) { return id(item) === message.videoId; });
    var ok = false;
    try {
      if (message.type === 'suspend') {
        suspend(!!message.value); ok = true;
      } else if (message.type === 'pauseAll') {
        pauseAll(); ok = true;
      } else if (message.type === 'endBoost') {
        ok = restoreBoost();
      } else if (message.type === 'restoreControls') {
        if (!nativeControls || id(nativeControls.video) === message.videoId) {
          restoreBoost(); restoreControls(); ok = true;
        }
      } else if (!video) {
        complete(false); return false;
      } else if (message.type === 'setPlaybackRate') {
        var rate = Number(message.rate);
        if (validRate(rate)) {
          restoreBoost(); speeds.set(video, rate);
          video.defaultPlaybackRate = rate; video.playbackRate = rate;
          ok = Math.abs(video.playbackRate - rate) < 0.001;
        }
      } else if (message.type === 'beginBoost') {
        var boostRate = Number(message.rate);
        if (validRate(boostRate) && !video.paused && !video.ended) {
          restoreBoost();
          boost = { video: video, rate: video.playbackRate, source: video.currentSrc };
          video.playbackRate = Math.max(boostRate, boost.rate);
          ok = true;
        }
      } else if (message.type === 'setBoostRate') {
        // Only the held gesture moves this. boost.rate keeps the rate to restore on release,
        // so this must not go through setPlaybackRate, which clears the boost first.
        var heldRate = Number(message.rate);
        if (boost && boost.video === video && validRate(heldRate)) {
          video.playbackRate = Math.max(heldRate, boost.rate);
          ok = true;
        }
      } else if (message.type === 'seek') {
        ok = seek(video, Number(message.position));
      } else if (message.type === 'togglePlayback' || message.type === 'play' || message.type === 'pause') {
        var shouldPlay = message.type === 'play' || (message.type === 'togglePlayback' && (video.paused || video.ended));
        if (shouldPlay && !suspended) {
          var promise = video.play();
          if (promise && promise.then) {
            promise.then(function() { complete(true); post(); }, function() { complete(false); post(); });
            return true;
          }
        } else { restoreBoost(); video.pause(); }
        ok = true;
      } else if (message.type === 'nativeControls') {
        if (canUseNativeControls(video)) ok = hideControls(video);
      } else if (message.type === 'setVideoTransform') {
        // The choice is kept beyond the takeover that is showing it: the next takeover on this
        // page writes the same preset, which is what makes it survive a fullscreen round trip.
        transform = { mirror: message.mirror === true,
          fit: validFit(message.fit) ? message.fit : 'NATURAL' };
        // refreshTransform both writes the ratio rules and applies or clears the mirror, and it
        // answers for the mirror: what the cascade accepted, read back from the element.
        ok = refreshTransform();
        if (!ok) {
          lastCommandError = 'setVideoTransform: no layer accepted the mirror declaration ' +
            '(tried ' + mirrorChain().length + ' elements, ' +
            (mirrorChain().length * MIRROR_DECLARATIONS.length) + ' declarations)';
        }
      }
    } catch (error) {
      lastCommandError = String(message.type) + ': ' + ((error && error.message) || String(error));
      if (message.type === 'nativeControls') restoreControls();
      ok = false;
    }
    complete(ok); post(); return ok;
  }
  function mediaEvent(event) {
    var video = event.target;
    if (suspended && event.type === 'play' && video && typeof video.pause === 'function') video.pause();
    if (video && String(video.tagName).toLowerCase() === 'video') {
      if (boost && boost.video === video &&
          (event.type === 'emptied' || event.type === 'ended' || event.type === 'pause' || boost.source !== video.currentSrc)) restoreBoost();
      if ((!boost || boost.video !== video) && speeds.has(video)) {
        var rate = speeds.get(video);
        if (Math.abs(video.playbackRate - rate) > 0.001) video.playbackRate = rate;
      }
      if (video === selected) {
        if (event.type === 'waiting' || event.type === 'stalled') buffering = true;
        else if (event.type === 'playing' || event.type === 'canplay') buffering = false;
        if (event.type === 'seeked' || event.type === 'emptied' || event.type === 'loadedmetadata') lastPositionAt = 0;
      }
    }
    schedule();
  }
  var events = ['play', 'playing', 'timeupdate', 'loadedmetadata', 'pause', 'ended', 'emptied',
    'durationchange', 'ratechange', 'waiting', 'stalled', 'canplay', 'seeked'];
  function visibilityChanged() { if (doc.hidden) restoreBoost(); schedule(); }
  function pageHide() { restoreBoost(); restoreControls(); }
  function fullscreenChanged() {
    if (rejectedControls && fullscreenRoot(rejectedControls.video) !== rejectedControls.root) rejectedControls = null;
    if (nativeControls && !canUseNativeControls(nativeControls.video)) {
      restoreBoost(); restoreControls();
    }
    schedule();
  }
  events.forEach(function(name) { doc.addEventListener(name, mediaEvent, true); });
  doc.addEventListener('fullscreenchange', fullscreenChanged);
  doc.addEventListener('webkitfullscreenchange', fullscreenChanged);
  doc.addEventListener('visibilitychange', visibilityChanged);
  win.addEventListener('pagehide', pageHide);
  try {
    observer = new win.MutationObserver(schedule);
    observer.observe(doc.documentElement || doc, { childList: true, subtree: true });
  } catch (_) {}
  if (bridge) bridge.onmessage = function(event) {
    var message;
    try { message = JSON.parse(String(event.data)); } catch (_) { return; }
    command(message, function(ok) { emit({ type: 'ack', id: message.id, frameId: frameId, ok: ok }); });
  };
  function pauseAll() {
    restoreBoost();
    videos().forEach(function(video) { try { video.pause(); } catch (_) {} });
  }
  function suspend(value) {
    suspended = !!value;
    if (suspended) {
      restoreBoost();
      Array.prototype.slice.call(doc.querySelectorAll('video,audio'), 0, 128).forEach(function(media) { media.pause(); });
    }
  }
  var api = {
    snapshot: snapshot, post: post, command: command, suspend: suspend, pauseAll: pauseAll,
    dispose: function() {
      restoreBoost(); restoreControls(); unwatchDelivery(); disposed = true;
      transform = null;
      refreshTransform();
      win.clearTimeout(scheduled); win.clearTimeout(pulse); win.clearTimeout(resizeRetry);
      resizeRetry = null; resizeRetryCount = 0;
      if (observer) observer.disconnect();
      events.forEach(function(name) { doc.removeEventListener(name, mediaEvent, true); });
      doc.removeEventListener('fullscreenchange', fullscreenChanged);
      doc.removeEventListener('webkitfullscreenchange', fullscreenChanged);
      doc.removeEventListener('visibilitychange', visibilityChanged);
      win.removeEventListener('pagehide', pageHide);
      if (bridge) bridge.onmessage = null;
      delete win.__pureBrowserVideoV2;
    }
  };
  watchDelivery();
  win.__pureBrowserVideoV2 = api;
  schedule();
  return api;
})
