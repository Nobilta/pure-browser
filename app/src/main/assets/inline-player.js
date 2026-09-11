(function createPureInlinePlayer(win, options, playback) {
  'use strict';
  var doc = win.document, sessions = new Map(), rejected = new WeakMap(), enabled = !!options.enabled;
  var labels = options.labels || {}, queued = null, closed = false, sequence = 0;
  var stageAttr = 'data-pure-inline-stage', videoAttr = 'data-pure-inline-video';
  function label(key) { return labels[key] || key; }
  function rect(element) { return element.getBoundingClientRect(); }
  function pathTo(video, root) {
    var path = [], node = video;
    while (node && path.length < 32) {
      path.push(node);
      if (node === root) return path;
      node = node.parentElement;
    }
    return null;
  }
  function full() { return doc.fullscreenElement || doc.webkitFullscreenElement; }
  function clip(video) {
    var r = rect(video), viewport = win.visualViewport;
    var left = Math.max(r.left, viewport ? viewport.offsetLeft : 0), top = Math.max(r.top, viewport ? viewport.offsetTop : 0);
    var right = Math.min(r.right, viewport ? viewport.offsetLeft + viewport.width : win.innerWidth);
    var bottom = Math.min(r.bottom, viewport ? viewport.offsetTop + viewport.height : win.innerHeight);
    for (var parent = video.parentElement, depth = 0; parent && depth++ < 32; parent = parent.parentElement) {
      var css = win.getComputedStyle(parent), p = rect(parent);
      if (/hidden|clip|scroll|auto/.test(css.overflowX)) { left = Math.max(left, p.left); right = Math.min(right, p.right); }
      if (/hidden|clip|scroll|auto/.test(css.overflowY)) { top = Math.max(top, p.top); bottom = Math.min(bottom, p.bottom); }
    }
    return { left: left, top: top, right: right, bottom: bottom, width: right - left, height: bottom - top };
  }
  function eligible(video) {
    if (!enabled || playback.suspended() || doc.hidden || full() || video.webkitDisplayingFullscreen ||
        video.isConnected === false || video.readyState < 1 || !video.videoWidth || !video.videoHeight) return false;
    var r = rect(video), c = clip(video), css = win.getComputedStyle(video);
    return r.width >= 160 && r.height >= 90 && c.width >= 100 && c.height >= 64 &&
      css.display !== 'none' && css.visibility === 'visible' && Number(css.opacity) > 0;
  }
  function playerRoot(video) {
    var result = video, bounds = rect(video), node = video.parentElement;
    for (var depth = 0; node && depth++ < 8 && node !== doc.body && node !== doc.documentElement; node = node.parentElement) {
      var r = rect(node);
      // Only isolate a tight player container, never the surrounding article, form or feed.
      if (Math.abs(r.left - bounds.left) > 16 || Math.abs(r.width - bounds.width) > 32 ||
          r.top < bounds.top - 24 || r.bottom > bounds.bottom + 64 || r.height < bounds.height - 2) break;
      if (node.querySelectorAll('video,audio,iframe,frame').length !== 1 ||
          node.querySelector('article,figcaption,p,h1,h2,h3,textarea,input:not([type=range]):not([type=button]),[contenteditable=true]')) break;
      result = node;
    }
    return result;
  }
  function restore(video, reject) {
    var saved = sessions.get(video);
    if (!saved) return;
    sessions.delete(video);
    if (reject) rejected.set(video, video.currentSrc);
    if (saved.observer) saved.observer.disconnect();
    saved.marks.forEach(function(mark) {
      try {
        if (mark.value === null) mark.element.removeAttribute(mark.name);
        else mark.element.setAttribute(mark.name, mark.value);
      } catch (_) {}
    });
    try { video.controls = saved.controls; } catch (_) {}
    saved.host.remove(); saved.style.remove();
  }
  function unobscured(saved) {
    var c = clip(saved.video);
    if (c.width < 100 || c.height < 64) return false;
    saved.host.style.setProperty('visibility', 'hidden', 'important');
    try {
      return [[.15,.15],[.85,.15],[.5,.5],[.15,.85],[.85,.85]].every(function(point) {
        var hit = doc.elementFromPoint(c.left + c.width * point[0], c.top + c.height * point[1]);
        return hit === saved.video || (saved.root !== saved.video && hit && saved.root.contains(hit));
      });
    } finally { saved.host.style.setProperty('visibility', 'visible', 'important'); }
  }
  function intact(saved) {
    var video = saved.video, r = rect(video), h = rect(saved.host), path = pathTo(video, saved.root);
    if (!saved.host.isConnected || !saved.style.isConnected || !saved.style.sheet || !saved.style.sheet.cssRules.length ||
        !saved.shadowStyle.isConnected || win.getComputedStyle(saved.panel).display !== 'flex' ||
        ['left','top','width','height'].some(function(key) { return Math.abs(r[key] - h[key]) > 2; }) ||
        video.controls || !path || path.length !== saved.path.length || path.some(function(n, i) { return n !== saved.path[i]; }) ||
        saved.marks.some(function(m) { return m.element.getAttribute(m.name) !== saved.marker; })) return false;
    // Hiding controls must never resize or move the original picture.
    if (Math.abs(r.width - saved.width) > 2 || Math.abs(r.height - saved.height) > 2) return false;
    var checked = 0;
    for (var i = 1; i < path.length; i++) {
      var children = path[i].children;
      for (var j = 0; j < children.length; j++) {
        if (++checked > 512) return false;
        if (children[j] === path[i - 1]) continue;
        var css = win.getComputedStyle(children[j]);
        if (css.display !== 'none' && css.visibility !== 'hidden' && Number(css.opacity) !== 0) return false;
      }
      if (['::before', '::after'].some(function(pseudo) {
        var css = win.getComputedStyle(path[i], pseudo);
        return css.display !== 'none' && css.content && css.content !== 'none' && css.content !== 'normal';
      })) return false;
    }
    return true;
  }
  function time(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return '—';
    return Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0');
  }
  function update(saved) {
    var video = saved.video, r = rect(video), c = clip(video), host = saved.host;
    ['left','top','width','height'].forEach(function(key) { host.style.setProperty(key, r[key] + 'px', 'important'); });
    host.style.setProperty('clip-path', 'inset(' + Math.max(0,c.top-r.top) + 'px ' + Math.max(0,r.right-c.right) +
      'px ' + Math.max(0,r.bottom-c.bottom) + 'px ' + Math.max(0,c.left-r.left) + 'px)', 'important');
    saved.panel.classList.toggle('compact', r.width < 320 || r.height < 160);
    saved.play.textContent = video.paused || video.ended ? '▶' : 'Ⅱ';
    saved.play.setAttribute('aria-label', label(video.paused || video.ended ? 'play' : 'pause'));
    saved.mute.textContent = video.muted ? '×♪' : '♪';
    saved.mute.setAttribute('aria-label', label(video.muted ? 'unmute' : 'mute'));
    saved.clock.textContent = time(video.currentTime) + ' / ' + time(video.duration);
    saved.speed.value = String(video.playbackRate);
    var seekable = Number.isFinite(video.duration) && video.duration > 0 && video.seekable.length;
    saved.seek.disabled = !seekable;
    if (seekable && !saved.seeking) {
      saved.seek.min = video.seekable.start(0); saved.seek.max = video.seekable.end(video.seekable.length - 1);
      saved.seek.value = video.currentTime;
    }
    saved.seek.setAttribute('aria-valuetext', saved.clock.textContent);
  }
  function mount(video) {
    var root = playerRoot(video), path = pathTo(video, root), r = rect(video);
    if (!path) return;
    var saved = { video: video, root: root, path: path, controls: video.controls, marks: [], width: r.width, height: r.height,
      marker: 'p' + Date.now().toString(36) + (++sequence), host: doc.createElement('div'), style: doc.createElement('style') };
    sessions.set(video, saved);
    try {
      function mark(element, name) {
        saved.marks.push({ element: element, name: name, value: element.getAttribute(name) }); element.setAttribute(name, saved.marker);
      }
      mark(video, videoAttr); path.slice(1).forEach(function(node) { mark(node, stageAttr); });
      var target = 'video[' + videoAttr + '="' + saved.marker + '"]', stage = '[' + stageAttr + '="' + saved.marker + '"]';
      saved.style.textContent = target + '::-webkit-media-controls,' + target + '::-webkit-media-controls-enclosure{display:none!important}' +
        target + '{pointer-events:auto!important;}' + stage + '> :not(' + stage + '):not(' + target + '){opacity:0!important;pointer-events:none!important;transition:none!important;animation:none!important;}' +
        stage + '::before,' + stage + '::after{display:none!important;content:none!important}';
      (doc.head || doc.documentElement).appendChild(saved.style);
      video.controls = false;
      var host = saved.host;
      host.setAttribute('data-pure-inline-player', saved.marker);
      host.style.cssText = 'all:initial!important;position:fixed!important;display:block!important;z-index:2147483646!important;' +
        'margin:0!important;padding:0!important;border:0!important;opacity:1!important;transform:none!important;visibility:visible!important;pointer-events:auto!important;';
      var shadow = host.attachShadow({ mode: 'open' }), style = saved.shadowStyle = doc.createElement('style');
      style.textContent = ':host{color-scheme:dark}*{box-sizing:border-box}.player{width:100%;height:100%;display:flex;flex-direction:column;justify-content:flex-end;color:white;font:14px system-ui;touch-action:pan-y;}' +
        '.surface{flex:1;min-height:0}.controls{background:linear-gradient(transparent,rgba(0,0,0,.88));padding:12px 4px 2px;}' +
        '.row{display:flex;align-items:center;gap:2px}button,select{min-width:40px;min-height:40px;color:#fff;background:rgba(0,0,0,.65);border:0;border-radius:6px;font:16px system-ui;padding:4px;cursor:pointer}' +
        'button:focus-visible,select:focus-visible,input:focus-visible{outline:2px solid #64dac7;outline-offset:-2px}.clock{flex:1;font-size:12px;white-space:nowrap}.speed{font-size:13px;max-width:65px}' +
        'input{display:block;width:100%;height:24px;margin:0;accent-color:#64dac7;touch-action:none}input:disabled{opacity:.4}.compact .clock,.compact .mute{display:none}.compact .row{justify-content:space-between}.compact .controls{padding-top:0}' +
        '.compact button,.compact select{min-width:32px;min-height:36px;font-size:14px;padding:2px}.compact .speed{max-width:48px}';
      shadow.appendChild(style);
      var panel = saved.panel = doc.createElement('div'); panel.className = 'player';
      panel.setAttribute('role', 'group'); panel.setAttribute('aria-label', label('player'));
      var surface = doc.createElement('div'); surface.className = 'surface'; panel.appendChild(surface);
      var controls = doc.createElement('div'); controls.className = 'controls'; panel.appendChild(controls);
      var seek = saved.seek = doc.createElement('input'); seek.type = 'range'; seek.step = '.1'; seek.setAttribute('aria-label', label('seek')); seek.dataset.action = 'seek'; controls.appendChild(seek);
      var row = doc.createElement('div'); row.className = 'row'; controls.appendChild(row);
      function action(type, values) {
        if (!sessions.has(video)) return;
        if (!eligible(video) || !intact(saved) || !unobscured(saved)) { restore(video, true); playback.changed(); return; }
        playback.command(video, type, values || {}, function(ok) { if (!ok) restore(video, true); else update(saved); playback.changed(); });
      }
      function button(name, text, click) {
        var b = doc.createElement('button'); b.type = 'button'; b.textContent = text; b.dataset.action = name;
        b.setAttribute('aria-label', label(name)); b.title = label(name); b.addEventListener('click', click); row.appendChild(b); return b;
      }
      saved.play = button('play', '▶', function() { action('togglePlayback'); });
      saved.mute = button('mute', '♪', function() { action('setMuted', { value: !video.muted }); }); saved.mute.className = 'mute';
      saved.clock = doc.createElement('span'); saved.clock.className = 'clock'; row.appendChild(saved.clock);
      var speed = saved.speed = doc.createElement('select'); speed.className = 'speed'; speed.dataset.action = 'speed'; speed.setAttribute('aria-label', label('speed'));
      [.5,.75,1,1.25,1.5,2,3].forEach(function(rate) { var option = doc.createElement('option'); option.value = rate; option.textContent = rate + '×'; speed.appendChild(option); });
      speed.addEventListener('change', function() { action('setPlaybackRate', { rate: Number(speed.value) }); }); row.appendChild(speed);
      button('fullscreen', '⛶', function() {
        if (!sessions.has(video)) return;
        // Restore the website before the fullscreen handoff takes ownership.
        restore(video, false);
        try {
          var request = root.requestFullscreen || root.webkitRequestFullscreen;
          if (!request) throw new Error('Fullscreen unavailable');
          var result = request.call(root);
          if (result && result.catch) result.catch(function() { playback.changed(); });
        } catch (_) { rejected.set(video, video.currentSrc); }
        playback.changed();
      });
      button('webControls', '↩', function() { restore(video, true); playback.changed(); });
      seek.addEventListener('pointerdown', function() { saved.seeking = true; });
      seek.addEventListener('input', function() { action('seek', { position: Number(seek.value) }); });
      ['change','pointerup','pointercancel','blur'].forEach(function(type) { seek.addEventListener(type, function() { saved.seeking = false; }); });
      surface.addEventListener('click', function() { action('togglePlayback'); });
      // Events from our controls cannot also activate the site's bubbling play/seek handlers.
      ['click','dblclick','pointerdown','pointerup','touchstart','touchend','keydown','keyup'].forEach(function(name) {
        host.addEventListener(name, function(event) { event.stopPropagation(); });
      });
      shadow.appendChild(panel); doc.documentElement.appendChild(host); update(saved);
      if (win.getComputedStyle(panel).display !== 'flex' || !intact(saved)) throw new Error('Player isolation unavailable');
      if (!unobscured(saved)) { restore(video, false); return; }
      saved.observer = new win.MutationObserver(function() { schedule(); });
      saved.observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['style','class','controls',stageAttr,videoAttr] });
    } catch (_) { restore(video, true); }
  }
  function refresh() {
    if (closed) return;
    sessions.forEach(function(saved, video) {
      try {
        if (!eligible(video)) restore(video, false);
        else if (Math.abs(rect(video).width - saved.width) > 2 || Math.abs(rect(video).height - saved.height) > 2) restore(video, false);
        else { update(saved);
        if (!intact(saved)) restore(video, true);
        else if (!unobscured(saved)) restore(video, false);
        }
      } catch (_) { restore(video, true); }
    });
    if (!enabled || doc.hidden || full() || playback.suspended()) return;
    Array.prototype.slice.call(doc.querySelectorAll('video'), 0, 64).forEach(function(video) {
      if (sessions.size >= 8 || sessions.has(video) || rejected.get(video) === video.currentSrc) return;
      try { if (eligible(video)) mount(video); } catch (_) { restore(video, true); }
    });
  }
  function schedule() {
    if (!closed && queued === null) queued = win.requestAnimationFrame(function() { queued = null; refresh(); });
  }
  function releaseAll() { sessions.forEach(function(_, video) { restore(video, false); }); }
  win.addEventListener('scroll', schedule, true); win.addEventListener('resize', schedule);
  if (win.visualViewport) { win.visualViewport.addEventListener('resize', schedule); win.visualViewport.addEventListener('scroll', schedule); }
  return {
    refresh: refresh, release: releaseAll,
    configure: function(value) { enabled = !!value; if (!enabled) releaseAll(); else schedule(); },
    dispose: function() {
      closed = true; releaseAll(); if (queued !== null) win.cancelAnimationFrame(queued);
      win.removeEventListener('scroll', schedule, true); win.removeEventListener('resize', schedule);
      if (win.visualViewport) { win.visualViewport.removeEventListener('resize', schedule); win.visualViewport.removeEventListener('scroll', schedule); }
    }
  };
})
