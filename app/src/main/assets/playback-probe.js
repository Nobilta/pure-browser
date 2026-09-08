(function installPureVideo(win) {
  'use strict';
  if (win.__pureBrowserVideoV2) return win.__pureBrowserVideoV2;
  var doc = win.document, bridge = win.mybrowserMediaProbe;
  var frameId = Date.now().toString(36) + Math.random().toString(36).slice(2);
  var ids = new WeakMap(), speeds = new WeakMap(), nextId = 0;
  var selected = null, boost = null, nativeControls = null, disposed = false;
  var scheduled = null, pulse = null, observer = null;
  var controlsAttribute = 'data-pure-browser-controls';
  var rates = [0.5, 0.75, 1, 1.25, 1.5, 2, 3];
  function finite(value, fallback) { return Number.isFinite(Number(value)) ? Number(value) : fallback; }
  function id(video) {
    if (!ids.has(video)) ids.set(video, 'v' + (++nextId));
    return ids.get(video);
  }
  function videos() { return Array.prototype.slice.call(doc.querySelectorAll('video'), 0, 64); }
  function visible(video) {
    try {
      var rect = video.getBoundingClientRect(), style = win.getComputedStyle(video);
      return rect.width > 1 && rect.height > 1 && style.display !== 'none' && style.visibility !== 'hidden';
    } catch (_) { return false; }
  }
  function fullscreen(video) {
    var root = doc.fullscreenElement || doc.webkitFullscreenElement;
    return !!video.webkitDisplayingFullscreen || !!(root && (root === video || root.contains(video)));
  }
  function canUseNativeControls(video) {
    if (!video) return false;
    var host = new URL(win.location.href).hostname.toLowerCase();
    if (/(^|\.)(youtube\.com|youtube-nocookie\.com)$/.test(host)) return false;
    var root = doc.fullscreenElement || doc.webkitFullscreenElement;
    var controls = nativeControls && nativeControls.video === video ? nativeControls.controls : video.controls;
    // A container may render its own controls. Only the video's built-in controls can
    // be handed off through video.controls; Blob/MSE alone says nothing about UI ownership.
    return !!controls && (root === video || !!video.webkitDisplayingFullscreen);
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
        !found.some(function(video) { return video !== nativeControls.video && fullscreen(video); })) {
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
      videoId: video ? id(video) : null, hasVideo: !!video,
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
    if (state.hasVideo && !doc.hidden) pulse = win.setTimeout(post, 1000);
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
      try {
        if (saved.attribute === null) saved.video.removeAttribute(controlsAttribute);
        else saved.video.setAttribute(controlsAttribute, saved.attribute);
        saved.video.controls = saved.controls;
      } catch (_) {}
      try { saved.style.remove(); } catch (_) {}
    }
  }
  function hideControls(video) {
    if (!nativeControls || nativeControls.video !== video) {
      restoreControls();
      var style = doc.createElement('style'), marker = frameId + '-' + id(video);
      nativeControls = { video: video, controls: video.controls,
        attribute: video.getAttribute(controlsAttribute), style: style };
      // Chromium can force its UA controls in fullscreen even with controls=false.
      // Scope suppression to this element, and remove both marker and style on exit.
      var selector = 'video[' + controlsAttribute + '="' + marker + '"]';
      style.textContent = selector + '::-webkit-media-controls{display:none!important}' +
        selector + '::-webkit-media-controls-enclosure{display:none!important}';
      (doc.head || doc.documentElement).appendChild(style);
      if (!style.sheet || !style.sheet.cssRules.length) throw new Error('Control styles unavailable');
      video.setAttribute(controlsAttribute, marker);
    }
    video.controls = false;
    return !video.controls;
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
      if (message.type === 'endBoost') {
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
      } else if (message.type === 'togglePlayback') {
        if (video.paused || video.ended) {
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
  function visibilityChanged() { if (doc.hidden) restoreBoost(); schedule(); }
  function pageHide() { restoreBoost(); restoreControls(); }
  function fullscreenChanged() {
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
  var api = {
    snapshot: snapshot, post: post, command: command,
    dispose: function() {
      restoreBoost(); restoreControls(); disposed = true;
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
