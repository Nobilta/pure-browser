(function installPureVideo(win, options, createInline) {
  'use strict';
  if (win.__pureBrowserVideoV2) return win.__pureBrowserVideoV2;
  var doc = win.document, bridge = win.mybrowserMediaProbe;
  var frameId = Date.now().toString(36) + Math.random().toString(36).slice(2);
  var ids = new WeakMap(), speeds = new WeakMap(), nextId = 0;
  var selected = null, boost = null, nativeControls = null, disposed = false, suspended = false;
  var scheduled = null, pulse = null, observer = null, inline = null;
  var controlsAttribute = 'data-pure-browser-controls', stageAttribute = 'data-pure-browser-stage';
  var rootAttribute = 'data-pure-browser-fullscreen', rejectedControls = null;
  var rates = [0.5, 0.75, 1, 1.25, 1.5, 2, 3];
  function finite(value, fallback) { return Number.isFinite(Number(value)) ? Number(value) : fallback; }
  function id(video) {
    if (!ids.has(video)) ids.set(video, 'v' + (++nextId));
    return ids.get(video);
  }
  function videos() { return Array.prototype.slice.call(doc.querySelectorAll('video,audio'), 0, 64); }
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
    if (inline) inline.refresh();
    if (nativeControls && !controlsIntact(nativeControls)) {
      rejectedControls = { video: nativeControls.video, root: nativeControls.root };
      restoreBoost(); restoreControls();
    }
    var video = pick(), urls = [], sourceUrl = null, rangeStart = 0, rangeEnd = 0;
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
      hasVideo: !!video && String(video.tagName).toLowerCase() === 'video', muted: !!video && video.muted,
      playing: !!video && !video.paused && !video.ended,
      fullscreen: !!video && fullscreen(video), score: video ? rank(video) : 0,
      nativeControlsAvailable: canUseNativeControls(video),
      urls: urls, sourceUrl: sourceUrl, playbackRate: video ? finite(video.playbackRate, 1) : null,
      position: video ? Math.max(0, finite(video.currentTime, 0)) : 0,
      duration: video ? Math.max(0, finite(video.duration, 0)) : 0,
      seekStart: Math.max(0, finite(rangeStart, 0)), seekEnd: Math.max(0, finite(rangeEnd, 0)),
      width: video ? video.videoWidth : 0, height: video ? video.videoHeight : 0,
      boosting: !!boost && boost.video === video
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
      try { saved.style.remove(); } catch (_) {}
    }
  }
  function controlsIntact(saved) {
    try {
      if (saved.video.isConnected === false || saved.video.controls ||
          fullscreenRoot(saved.video) !== saved.root || saved.style.isConnected === false ||
          !saved.style.sheet || !saved.style.sheet.cssRules.length) return false;
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
  function hideControls(video) {
    if (inline) inline.release();
    var root = fullscreenRoot(video), path = controlPath(video, root);
    if (!path) return false;
    if (!nativeControls || nativeControls.video !== video || nativeControls.root !== root) {
      restoreControls();
      var style = doc.createElement('style'), marker = frameId + '-' + id(video);
      var saved = nativeControls = { video: video, root: root, path: path, controls: video.controls,
        marks: [], marker: marker, style: style, observer: null };
      function mark(element, name) {
        saved.marks.push({ element: element, name: name, value: element.getAttribute(name) });
        element.setAttribute(name, marker);
      }
      // Chromium can force its UA controls in fullscreen even with controls=false.
      // Scope suppression to this element, and remove both marker and style on exit.
      var selector = 'video[' + controlsAttribute + '="' + marker + '"]';
      style.textContent = selector + '::-webkit-media-controls{display:none!important}' +
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
        style.textContent += stage + '> :not(' + stage + '):not(' + selector + '){display:none!important}' +
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
      (doc.head || doc.documentElement).appendChild(style);
      if (!style.sheet || !style.sheet.cssRules.length) throw new Error('Control styles unavailable');
      saved.observer = new win.MutationObserver(schedule);
      saved.observer.observe(root, { childList: true, subtree: true, attributes: true,
        attributeFilter: ['class', 'style', 'controls', controlsAttribute, stageAttribute, rootAttribute] });
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
      if (message.type === 'configure') {
        configure(message.enabled); ok = true;
      } else if (message.type === 'suspend') {
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
        if (rates.indexOf(rate) >= 0) {
          restoreBoost(); speeds.set(video, rate);
          video.defaultPlaybackRate = rate; video.playbackRate = rate;
          ok = Math.abs(video.playbackRate - rate) < 0.001;
        }
      } else if (message.type === 'setMuted') {
        video.muted = !!message.value; ok = video.muted === !!message.value;
      } else if (message.type === 'beginBoost') {
        var boostRate = Number(message.rate);
        if ((boostRate === 2 || boostRate === 3) && !video.paused && !video.ended) {
          restoreBoost();
          boost = { video: video, rate: video.playbackRate, source: video.currentSrc };
          video.playbackRate = Math.max(boostRate, boost.rate);
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
      }
    } catch (_) {
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
    }
    schedule();
  }
  var events = ['play', 'playing', 'timeupdate', 'loadedmetadata', 'pause', 'ended', 'emptied', 'durationchange', 'ratechange'];
  function visibilityChanged() { if (doc.hidden) { restoreBoost(); if (inline) inline.release(); } schedule(); }
  function pageHide() { restoreBoost(); restoreControls(); if (inline) inline.release(); }
  function fullscreenChanged() {
    if (inline) inline.release();
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
      if (inline) inline.release();
      restoreBoost();
      Array.prototype.slice.call(doc.querySelectorAll('video,audio'), 0, 128).forEach(function(media) { media.pause(); });
    }
  }
  function configure(enabled) { if (inline) inline.configure(enabled); }
  if (createInline) inline = createInline(win, options || {}, {
    suspended: function() { return suspended || !!nativeControls; }, changed: schedule,
    command: function(video, type, values, complete) {
      command(Object.assign({}, values, { type: type, frameId: frameId, videoId: id(video) }), complete);
    }
  });
  var api = {
    snapshot: snapshot, post: post, command: command, suspend: suspend, pauseAll: pauseAll, configure: configure,
    dispose: function() {
      restoreBoost(); restoreControls(); if (inline) inline.dispose(); disposed = true;
      win.clearTimeout(scheduled); win.clearTimeout(pulse);
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
  win.__pureBrowserVideoV2 = api;
  schedule();
  return api;
})
