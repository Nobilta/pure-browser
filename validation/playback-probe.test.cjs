const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/playback-probe.js'), 'utf8');

function fixture(count = 1, options = {}) {
  const listeners = new Map(), timers = new Map(), messages = [], styles = [];
  let nextTimer = 0;
  const doc = {
    baseURI: 'https://example.com/watch', hidden: false, fullscreenElement: null,
    head: { appendChild(style) { styles.push(style); } },
    // A real style element holds no rules until its text is set, and the probe reads an empty
    // sheet as "the page refuses our styles" — so the fixture models that instead of handing out
    // a populated sheet unconditionally.
    createElement() {
      return { textContent: '', override: undefined,
        // A real style element holds no rules until its text is set, and the probe reads an empty
        // sheet as "the page refuses our styles" — so the fixture models that instead of handing
        // out a populated sheet unconditionally. Tests that simulate a blocked or vanished sheet
        // assign `sheet`, so the property stays writable.
        get sheet() {
          return this.override !== undefined ? this.override : { cssRules: this.textContent ? [{}] : [] };
        },
        set sheet(value) { this.override = value; },
        remove() { const i = styles.indexOf(this); if (i >= 0) styles.splice(i, 1); } };
    },
    addEventListener(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    querySelectorAll(name) { return name === 'video' || name === 'video,audio' ? videos : []; },
  };
  function event(type, target) { for (const fn of listeners.get(type) || []) fn({ type, target }); }
  const videos = Array.from({ length: count }, (_, i) => {
    let rate = 1;
    const attributes = new Map();
    const declarations = new Map();
    const video = {
      // A real element carries an inline declaration block; the probe writes the mirror there
      // because that is the only place a page's own stylesheet cannot outrank.
      style: {
        setProperty(name, value, priority) { declarations.set(name, { value: String(value), priority: priority || '' }); },
        getPropertyValue(name) { const entry = declarations.get(name); return entry ? entry.value : ''; },
        getPropertyPriority(name) { const entry = declarations.get(name); return entry ? entry.priority : ''; },
        removeProperty(name) { declarations.delete(name); },
      },
      tagName: 'VIDEO', currentSrc: `https://example.com/${i}.mp4`, src: '', controls: true,
      paused: false, ended: false, muted: false, currentTime: 20, duration: 120, readyState: 4,
      videoWidth: 1280, videoHeight: 720, defaultPlaybackRate: 1,
      seekable: { length: 1, start: () => 0, end: () => 120 },
      parentElement: null, isConnected: true,
      querySelectorAll: () => [], getBoundingClientRect: () => ({ left: 0, top: 0, width: 640, height: 360 }),
      contains(other) { return other === this; },
      getAttribute(name) { return attributes.get(name) ?? null; },
      setAttribute(name, value) { attributes.set(name, value); },
      removeAttribute(name) { attributes.delete(name); },
      pause() { this.paused = true; event('pause', this); },
      play() { this.paused = false; event('play', this); return Promise.resolve(); },
      get playbackRate() { return rate; },
      set playbackRate(value) { if (value !== rate) { rate = value; event('ratechange', this); } },
    };
    return video;
  });
  const win = {
    document: doc, location: { href: doc.baseURI },
    // Browsers expose URL on the window, and the probe reads it there to attribute MSE traffic.
    URL,
    performance: { getEntriesByType: () => [] },
    // Protocol tests model computed styles; the Android fixture checks actual CSS/layout.
    getComputedStyle(element) {
      const staged = styles.length && element.parentElement?.getAttribute('data-pure-browser-stage');
      const hidden = staged && !element.getAttribute('data-pure-browser-stage') && !element.getAttribute('data-pure-browser-controls');
      return { display: hidden ? 'none' : 'block', visibility: 'visible', opacity: '1',
        // Read back from the inline block, except where the test models a declaration the style
        // engine refuses: Chromium's `:fullscreen` UA rule forces `transform` (and `rotate`, and
        // `filter`) to `none` with `!important`, which no author declaration outranks, while the
        // individual `scale` property is left to the author. `dropTransform` is that reset; the
        // real page that exposed it was a player that fullscreens its own video.
        transform: element.dropTransform ? 'none'
          : (element.style ? element.style.getPropertyValue('transform') : ''),
        scale: element.dropScale ? 'none'
          : ((element.style && element.style.getPropertyValue('scale')) || 'none'),
        ...element.computedStyle };
    },
    setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { timers.delete(id); },
    MutationObserver: class { observe() {} disconnect() {} },
    // Window listeners land in the same map as the document's, so a test can fire a resize and see
    // exactly who is registered for it.
    addEventListener(name, fn) { doc.addEventListener(name, fn); },
    removeEventListener(name, fn) { doc.removeEventListener(name, fn); },
    mybrowserMediaProbe: { postMessage(raw) { messages.push(JSON.parse(raw)); } },
  };
  if (options.mse) {
    let blobs = 0;
    win.URL = { createObjectURL: () => `blob:https://example.com/${++blobs}` };
    // Minimal MediaSource/SourceBuffer pair: enough for the probe's delivery accounting, which is
    // the only part of MSE it touches.
    win.MediaSource = class MediaSource {};
    // appendBuffer lives on the prototype, as it does in a browser: the probe wraps it there.
    win.SourceBuffer = class SourceBuffer {
      appendBuffer(data) { this.appended = (this.appended || 0) + (data?.byteLength || 0); }
    };
    win.MediaSource.prototype.addSourceBuffer = function () { return new win.SourceBuffer(); };
  }
  const api = vm.runInNewContext(`${source}(window)`, { window: win, URL });
  let commandId = 0;
  function command(type, values = {}, target = api.snapshot()) {
    return api.command({ type, id: ++commandId, frameId: target.frameId, videoId: target.videoId, ...values });
  }
  function container(children = []) {
    const attributes = new Map(), declarations = new Map();
    const element = {
      style: {
        setProperty(name, value, priority) { declarations.set(name, { value: String(value), priority: priority || '' }); },
        getPropertyValue(name) { const entry = declarations.get(name); return entry ? entry.value : ''; },
        getPropertyPriority(name) { const entry = declarations.get(name); return entry ? entry.priority : ''; },
        removeProperty(name) { declarations.delete(name); },
      },
      tagName: 'DIV', parentElement: null, children, isConnected: true,
      // The picture presets derive their letterbox padding from the box they are fitted into.
      clientWidth: 640, clientHeight: 360,
      getBoundingClientRect: () => ({ left: 0, top: 0, width: 640, height: 360 }),
      contains(child) { return child === this || this.children.some(item => item.contains(child)); },
      getAttribute(name) { return attributes.get(name) ?? null; },
      setAttribute(name, value) { attributes.set(name, value); },
      removeAttribute(name) { attributes.delete(name); },
    };
    children.forEach(child => { child.parentElement = element; });
    return element;
  }
  return { api, videos, doc, win, event, command, messages, timers, styles, container,
    listeners: (name) => listeners.get(name)?.size ?? 0 };
}

test('loaded source stays distinct from src attributes and page resource hints', () => {
  const f = fixture(), v = f.videos[0];
  assert.equal(f.api.snapshot().sourceUrl, v.currentSrc);
  v.currentSrc = '';
  v.src = 'https://example.com/pending.mp4';
  f.win.performance.getEntriesByType = () => [{ name: 'https://example.com/unrelated.mp4' }];
  const state = f.api.snapshot();
  assert.equal(state.sourceUrl, null);
  assert.ok(state.urls.includes(v.src));
  assert.ok(state.urls.includes('https://example.com/unrelated.mp4'));
  v.currentSrc = 'blob:https://example.com/loaded';
  assert.equal(f.api.snapshot().sourceUrl, v.currentSrc);
});

test('long press restores an arbitrary original rate and never changes the default', () => {
  const f = fixture(), video = f.videos[0];
  video.playbackRate = 1.75;
  assert.equal(f.command('beginBoost', { rate: 3 }), true);
  assert.equal(video.playbackRate, 3);
  assert.equal(video.defaultPlaybackRate, 1);
  assert.equal(f.command('endBoost'), true);
  assert.equal(video.playbackRate, 1.75);
  assert.equal(video.defaultPlaybackRate, 1);
});
test('normal speed and repeated boost releases do not overwrite the selected rate', () => {
  const f = fixture(), v = f.videos[0];
  f.command('setPlaybackRate', { rate: 1.5 });
  f.command('beginBoost', { rate: 2 });
  f.command('endBoost'); f.command('endBoost');
  assert.equal(v.playbackRate, 1.5);
  assert.equal(v.defaultPlaybackRate, 1.5);
});
test('pausing, ending and a new source cancel a temporary boost', () => {
  for (const type of ['pause', 'ended', 'emptied']) {
    const f = fixture(), v = f.videos[0];
    v.playbackRate = 1.25;
    f.command('beginBoost', { rate: 2 });
    f.event(type, v);
    assert.equal(v.playbackRate, 1.25, type);
  }
  const f = fixture(), v = f.videos[0];
  f.command('beginBoost', { rate: 2 });
  v.currentSrc = 'https://example.com/next.mp4'; f.event('loadedmetadata', v);
  assert.equal(v.playbackRate, 1);
});
test('commands continue to target a paused video', async () => {
  const f = fixture(), v = f.videos[0];
  assert.equal(f.command('togglePlayback'), true);
  assert.equal(v.paused, true);
  assert.equal(f.api.snapshot().hasVideo, true);
  assert.equal(f.api.snapshot().playing, false);
  assert.equal(f.command('setPlaybackRate', { rate: 1.5 }), true);
  let ok;
  f.api.command({ ...f.api.snapshot(), type: 'togglePlayback' }, value => { ok = value; });
  await Promise.resolve();
  assert.equal(ok, true); assert.equal(v.paused, false);
});
test('a playback rejection is reported instead of claiming success', async () => {
  const f = fixture(), v = f.videos[0];
  v.paused = true; v.play = () => Promise.reject(new Error('blocked'));
  let ok;
  f.api.command({ ...f.api.snapshot(), type: 'togglePlayback' }, value => { ok = value; });
  await Promise.resolve();
  assert.equal(ok, false);
});
test('commands from another frame or a removed video do not affect a replacement', () => {
  const f = fixture(), target = f.api.snapshot();
  assert.equal(f.command('setPlaybackRate', { rate: 2 }, { ...target, frameId: 'wrong' }), false);
  assert.equal(f.command('setPlaybackRate', { rate: 2 }, { ...target, videoId: 'missing' }), false);
  assert.equal(f.videos[0].playbackRate, 1);
});
test('fullscreen target stays selected when another video starts playing', () => {
  const f = fixture(2), v = f.videos[0];
  f.doc.fullscreenElement = v;
  const target = f.api.snapshot();
  f.command('nativeControls'); v.pause();
  assert.equal(f.api.snapshot().videoId, target.videoId);
  f.command('setPlaybackRate', { rate: 2 });
  assert.equal(v.playbackRate, 2); assert.equal(f.videos[1].playbackRate, 1);
});
test('seek clamps to actual seekable ranges and rejects a live stream', () => {
  const f = fixture(), v = f.videos[0];
  v.seekable = { length: 2, start: i => i ? 60 : 5, end: i => i ? 120 : 30 };
  assert.equal(f.command('seek', { position: -10 }), true); assert.equal(v.currentTime, 5);
  assert.equal(f.command('seek', { position: 50 }), true); assert.equal(v.currentTime, 60);
  assert.equal(f.command('seek', { position: 500 }), true); assert.equal(v.currentTime, 120);
  v.duration = Infinity;
  assert.equal(f.command('seek', { position: 50 }), false);
  assert.equal(f.api.snapshot().duration, 0);
});
test('a new fullscreen element replaces a still-mounted native control target', () => {
  const f = fixture(2), first = f.videos[0], next = f.videos[1];
  f.doc.fullscreenElement = first;
  const previous = f.api.snapshot();
  f.command('nativeControls');
  f.doc.fullscreenElement = next;
  const replacement = f.api.snapshot();
  assert.notEqual(replacement.videoId, previous.videoId);
  f.command('restoreControls', {}, previous);
  f.command('nativeControls', {}, replacement);
  f.command('setPlaybackRate', { rate: 2 }, replacement);
  assert.equal(first.controls, true);
  assert.equal(first.playbackRate, 1);
  assert.equal(next.controls, false);
  assert.equal(next.playbackRate, 2);
});
test('native mode restores the exact original controls setting and speed', () => {
  const f = fixture(), v = f.videos[0];
  f.doc.fullscreenElement = v;
  assert.equal(f.command('nativeControls'), true); assert.equal(v.controls, false);
  assert.equal(f.api.snapshot().nativeControlsAvailable, true);
  f.command('beginBoost', { rate: 2 });
  f.command('restoreControls'); assert.equal(v.controls, true); assert.equal(v.playbackRate, 1);
});
test('native fullscreen suppresses Chromium auto-rotation and restores the original controls list', () => {
  for (const original of [null, '', 'nodownload', 'noremoteplayback nofullscreen']) {
    const f = fixture(), v = f.videos[0];
    if (original !== null) v.setAttribute('controlslist', original);
    f.doc.fullscreenElement = v;
    assert.equal(f.command('nativeControls'), true);
    assert.match(v.getAttribute('controlslist'), /(^|\s)nofullscreen(\s|$)/);
    f.command('restoreControls');
    assert.equal(v.getAttribute('controlslist'), original);
  }
});
test('a page taking back its fullscreen controls restores native takeover state', () => {
  const f = fixture(), v = f.videos[0];
  f.doc.fullscreenElement = v;
  assert.equal(f.command('nativeControls'), true);
  v.removeAttribute('controlslist');
  assert.equal(f.api.snapshot().nativeControlsAvailable, false);
  assert.equal(v.controls, true);
  assert.equal(v.getAttribute('data-pure-browser-controls'), null);
  assert.equal(f.styles.length, 0);
});
test('inline video stays with the page and fullscreen does not require a controls attribute', () => {
  const f = fixture(), v = f.videos[0];
  assert.equal(f.api.snapshot().nativeControlsAvailable, false);
  assert.equal(f.command('nativeControls'), false);
  assert.equal(v.controls, true);
  assert.equal(f.styles.length, 0);
  for (let i = 0; i < 5; i++) f.api.snapshot();
  assert.equal(v.controls, true);
  assert.equal(f.styles.length, 0);
  f.doc.fullscreenElement = v;
  v.controls = false;
  assert.equal(f.command('nativeControls'), true);
  f.command('restoreControls');
  assert.equal(v.controls, false);
});
test('custom fullscreen keeps the loaded signed source, DOM, progress and audio state', () => {
  const f = fixture(), v = f.videos[0], button = f.container();
  v.controls = false;
  v.currentSrc = 'https://cdn.example.com/play?token=a%2Fb%3D&expires=9999999999';
  v.playbackRate = 1.75; v.muted = true;
  const nested = f.container([v]), root = f.container([nested, button]);
  root.setAttribute('data-pure-browser-stage', 'old-stage');
  root.setAttribute('data-pure-browser-fullscreen', 'old-root');
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.api.snapshot().nativeControlsAvailable, true);
  assert.equal(f.win.getComputedStyle(button).display, 'none');
  assert.equal(v.parentElement, nested);
  assert.equal(v.currentSrc, 'https://cdn.example.com/play?token=a%2Fb%3D&expires=9999999999');
  assert.equal(v.currentTime, 20); assert.equal(v.playbackRate, 1.75);
  assert.equal(v.muted, true); assert.equal(v.paused, false);
  f.command('restoreControls');
  assert.equal(v.controls, false);
  assert.equal(root.getAttribute('data-pure-browser-stage'), 'old-stage');
  assert.equal(root.getAttribute('data-pure-browser-fullscreen'), 'old-root');
  assert.equal(nested.getAttribute('data-pure-browser-stage'), null);
  assert.equal(f.win.getComputedStyle(button).display, 'block');
  assert.equal(f.styles.length, 0);
});
test('custom Blob/MSE playback can hand off without copying its source to another player', () => {
  const f = fixture(), v = f.videos[0];
  v.currentSrc = 'blob:https://example.com/mse'; v.controls = false;
  f.doc.fullscreenElement = f.container([v, f.container()]);
  assert.equal(f.command('nativeControls'), true);
  assert.equal(v.currentSrc, 'blob:https://example.com/mse');
  assert.equal(v.currentTime, 20);
});
test('unloaded videos and URL hints cannot cause a fullscreen handoff', () => {
  const f = fixture(), v = f.videos[0];
  v.readyState = 0; v.videoWidth = v.videoHeight = 0;
  f.win.performance.getEntriesByType = () => [{ name: 'https://example.com/unrelated.mp4' }];
  f.doc.fullscreenElement = f.container([v]);
  assert.equal(f.command('nativeControls'), false);
  assert.equal(f.styles.length, 0);
});
test('a paused custom player remains selected when a sibling video starts playing', () => {
  const f = fixture(2), [v, other] = f.videos;
  other.paused = true; other.currentTime = 0;
  f.doc.fullscreenElement = f.container([v, other]);
  const target = f.api.snapshot();
  assert.equal(f.command('nativeControls'), true);
  v.pause(); other.play();
  assert.equal(f.api.snapshot().videoId, target.videoId);
  f.command('setPlaybackRate', { rate: 2 });
  assert.equal(v.playbackRate, 2); assert.equal(other.playbackRate, 1);
});
test('failed container layout or unsuppressed site controls restore the original player', () => {
  for (const failure of ['geometry', 'site-controls']) {
    const f = fixture(), v = f.videos[0], button = f.container();
    v.controls = false;
    const root = f.container([v, button]);
    f.doc.fullscreenElement = root;
    if (failure === 'geometry') v.getBoundingClientRect = () => ({ left: 20, top: 0, width: 300, height: 200 });
    else button.computedStyle = { display: 'block' };
    assert.equal(f.command('nativeControls'), false, failure);
    assert.equal(v.controls, false);
    assert.equal(root.getAttribute('data-pure-browser-stage'), null);
    assert.equal(v.getAttribute('data-pure-browser-controls'), null);
    assert.equal(f.styles.length, 0);
  }
});
test('lost container isolation restores the page once and permits the next fullscreen session', () => {
  const f = fixture(), v = f.videos[0], button = f.container();
  const root = f.container([v, button]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  f.command('beginBoost', { rate: 2 });
  button.computedStyle = { display: 'block' };
  assert.equal(f.api.snapshot().nativeControlsAvailable, false);
  assert.equal(v.controls, true); assert.equal(v.playbackRate, 1);
  assert.equal(f.styles.length, 0);
  assert.equal(f.command('nativeControls'), false);
  delete button.computedStyle;
  f.doc.fullscreenElement = null; f.event('fullscreenchange', f.doc);
  f.doc.fullscreenElement = root; f.event('fullscreenchange', f.doc);
  assert.equal(f.command('nativeControls'), true);
});
test('moving a custom video or removing the stylesheet releases all temporary markers', () => {
  for (const failure of ['reparent', 'stylesheet', 'marker']) {
    const f = fixture(), v = f.videos[0], root = f.container([v]);
    f.doc.fullscreenElement = root;
    assert.equal(f.command('nativeControls'), true);
    if (failure === 'reparent') v.parentElement = f.container();
    else if (failure === 'stylesheet') f.styles[0].sheet = null;
    else root.removeAttribute('data-pure-browser-stage');
    assert.equal(f.api.snapshot().nativeControlsAvailable, false, failure);
    assert.equal(v.controls, true);
    assert.equal(root.getAttribute('data-pure-browser-fullscreen'), null);
    assert.equal(f.styles.length, 0);
  }
});
test('UA control suppression is scoped and restores the exact previous marker', () => {
  const f = fixture(2), v = f.videos[0];
  v.setAttribute('data-pure-browser-controls', 'existing-value');
  f.doc.fullscreenElement = v;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.styles.length, 1);
  const marker = v.getAttribute('data-pure-browser-controls');
  assert.notEqual(marker, 'existing-value');
  assert.match(f.styles[0].textContent, /::-webkit-media-controls/);
  assert.ok(f.styles[0].textContent.includes(`video[data-pure-browser-controls="${marker}"]`));
  assert.equal(f.videos[1].getAttribute('data-pure-browser-controls'), null);
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.styles.length, 1);
  f.command('restoreControls');
  assert.equal(f.styles.length, 0);
  assert.equal(v.getAttribute('data-pure-browser-controls'), 'existing-value');
  assert.equal(v.controls, true);
});
test('failed suppression restores the page instead of leaving half a handoff', () => {
  const f = fixture(), v = f.videos[0];
  f.doc.fullscreenElement = v;
  f.doc.head.appendChild = () => { throw new Error('DOM unavailable'); };
  assert.equal(f.command('nativeControls'), false);
  assert.equal(v.controls, true);
  assert.equal(v.getAttribute('data-pure-browser-controls'), null);
  assert.equal(f.styles.length, 0);
});
test('a content policy blocking the stylesheet keeps the web player intact', () => {
  const f = fixture(), v = f.videos[0];
  f.doc.fullscreenElement = v;
  f.doc.head.appendChild = style => { f.styles.push(style); style.sheet = null; };
  assert.equal(f.command('nativeControls'), false);
  assert.equal(v.controls, true);
  assert.equal(v.getAttribute('data-pure-browser-controls'), null);
  assert.equal(f.styles.length, 0);
});
test('loaded videos use the same handoff rules on YouTube and other hosts', () => {
  for (const host of ['youtube.com', 'm.youtube.com', 'www.youtube.com', 'www.youtube-nocookie.com']) {
    const f = fixture(), v = f.videos[0];
    f.win.location.href = `https://${host}/watch?v=abc`;
    f.doc.fullscreenElement = v;
    assert.equal(f.api.snapshot().nativeControlsAvailable, true, host);
    assert.equal(f.command('nativeControls'), true, host);
    assert.equal(v.controls, false, host);
  }
});
test('lookalike hostnames do not change standard video ownership', () => {
  const f = fixture();
  f.win.location.href = 'https://notyoutube.com/watch';
  f.doc.fullscreenElement = f.videos[0];
  assert.equal(f.command('nativeControls'), true);
});
test('Blob source and direct casting are independent of fullscreen UI ownership', () => {
  const f = fixture(), v = f.videos[0];
  v.currentSrc = 'blob:https://example.com/local-video';
  f.doc.fullscreenElement = v;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(v.controls, false);
});
test('page-driven fullscreen exit restores controls and a held speed', () => {
  const f = fixture(), v = f.videos[0];
  f.doc.fullscreenElement = v;
  f.command('nativeControls'); f.command('beginBoost', { rate: 2 });
  f.doc.fullscreenElement = null;
  f.event('fullscreenchange', f.doc);
  assert.equal(v.controls, true); assert.equal(v.playbackRate, 1);
  assert.equal(f.api.snapshot().nativeControlsAvailable, false);
  assert.equal(f.command('nativeControls'), false);
});
test('late restore for a previous video cannot release its replacement', () => {
  const f = fixture(2), [first, next] = f.videos;
  f.doc.fullscreenElement = first;
  const previous = f.api.snapshot();
  f.command('nativeControls');
  f.doc.fullscreenElement = next;
  f.command('nativeControls');
  assert.equal(f.command('restoreControls', {}, previous), false);
  assert.equal(first.controls, true);
  assert.equal(next.controls, false);
});
test('unsafe speeds and unknown commands leave the player unchanged', () => {
  const f = fixture();
  for (const rate of [0, -1, 100, NaN, Infinity, 'alert(1)']) assert.equal(f.command('setPlaybackRate', { rate }), false);
  assert.equal(f.command('evaluate', { script: 'anything' }), false);
  assert.equal(f.videos[0].playbackRate, 1);
});
test('reply messages acknowledge the exact command id and frame', () => {
  const f = fixture(), target = f.api.snapshot();
  f.win.mybrowserMediaProbe.onmessage({ data: JSON.stringify({ type: 'setPlaybackRate', id: 73, rate: 2, frameId: target.frameId, videoId: target.videoId }) });
  const ack = f.messages.find(m => m.type === 'ack');
  assert.equal(ack.id, 73); assert.equal(ack.frameId, target.frameId); assert.equal(ack.ok, true);
});
test('disposing a detached WebView removes timers and restores transient state', () => {
  const f = fixture(), v = f.videos[0];
  f.doc.fullscreenElement = v;
  f.command('nativeControls'); f.command('beginBoost', { rate: 2 });
  f.api.dispose();
  assert.equal(v.controls, true); assert.equal(v.playbackRate, 1);
  assert.equal(v.getAttribute('data-pure-browser-controls'), null);
  assert.equal(f.styles.length, 0);
  assert.equal(f.timers.size, 0); assert.equal(f.win.__pureBrowserVideoV2, undefined);
});
test('video-free pages do not keep a recurring scan timer', () => {
  const f = fixture(0); f.api.post();
  assert.equal(f.api.snapshot().hasVideo, false);
  assert.equal(f.timers.size, 0);
});
test('hiding the document restores a held speed immediately', () => {
  const f = fixture(); f.videos[0].playbackRate = 1.25;
  f.command('beginBoost', { rate: 2 });
  f.doc.hidden = true; f.event('visibilitychange', f.doc);
  assert.equal(f.videos[0].playbackRate, 1.25);
});

test('parking pauses media and prevents a page or remote command from restarting it', () => {
  const f = fixture(), v = f.videos[0];
  f.api.suspend(true); assert.equal(v.paused, true);
  v.play(); assert.equal(v.paused, true);
  f.command('play'); assert.equal(v.paused, true);
  f.api.suspend(false); f.command('play'); assert.equal(v.paused, false);
  f.command('pause'); assert.equal(v.paused, true);
});
test('audio elements report media without claiming fullscreen video controls', () => {
  const f = fixture(), v = f.videos[0]; v.tagName = 'AUDIO';
  const state = f.api.snapshot();
  assert.equal(state.hasMedia, true); assert.equal(state.hasVideo, false);
  assert.equal(state.nativeControlsAvailable, false); assert.equal(state.playing, true);
});

test('system pause silences all audio/video elements while allowing explicit resume', () => {
  const f = fixture(3); f.videos[1].tagName = 'AUDIO';
  f.command('beginBoost', { rate: 2 });
  assert.equal(f.command('pauseAll'), true);
  assert.ok(f.videos.every(v => v.paused));
  assert.equal(f.videos[0].playbackRate, 1);
  f.command('play');
  assert.equal(f.api.snapshot().playing, true);
});

test('ended, unloaded and hidden paused videos retire system playback without removing page controls', () => {
  for (const close of [v => { v.ended = true; }, v => { v.readyState = 0; },
    v => { v.computedStyle = { display: 'none' }; }, v => { v.error = { code: 4 }; }]) {
    const f = fixture(), v = f.videos[0]; v.paused = true; close(v);
    const state = f.api.snapshot();
    assert.equal(state.hasMedia, true);
    assert.equal(state.playbackAvailable, false);
  }
});

test('ordinary pause remains resumable and hidden playing media can continue in background', () => {
  const f = fixture(), v = f.videos[0];
  v.paused = true;
  assert.equal(f.api.snapshot().playbackAvailable, true);
  v.computedStyle = { display: 'none' };
  v.paused = false;
  assert.equal(f.api.snapshot().playbackAvailable, true);
  v.paused = true; v.tagName = 'AUDIO';
  assert.equal(f.api.snapshot().playbackAvailable, true);
});

test('a rate the slider produced survives the float32 round trip', () => {
  const f = fixture(), v = f.videos[0];
  // 4.7f widens to 4.699999809265137 as a double, and the page used to reject exactly this kind of
  // value: the app sends what the slider produced, so the grid check has to tolerate it.
  for (const tenths of [5, 12, 15, 42, 43, 47, 48, 50]) {
    const rate = Math.fround(tenths / 10);
    assert.equal(f.command('setPlaybackRate', { rate }), true, `rate ${tenths / 10}`);
    assert.ok(Math.abs(v.playbackRate - tenths / 10) < 1e-6, `applied ${v.playbackRate}`);
  }
  for (const rate of [0.4, 4.75, 5.1, 0.05]) {
    assert.equal(f.command('setPlaybackRate', { rate }), false, `rate ${rate}`);
  }
});

test('the held rate moves without losing the rate to restore', () => {
  const f = fixture(), v = f.videos[0];
  assert.equal(f.command('setBoostRate', { rate: 3 }), false, 'no boost is running yet');
  f.command('setPlaybackRate', { rate: 1.5 });
  assert.equal(f.command('beginBoost', { rate: 2 }), true);
  assert.equal(f.command('setBoostRate', { rate: Math.fround(4.7) }), true);
  assert.ok(Math.abs(v.playbackRate - 4.7) < 1e-6, `applied ${v.playbackRate}`);
  assert.equal(f.command('endBoost'), true);
  assert.ok(Math.abs(v.playbackRate - 1.5) < 1e-6, 'release restores the selected rate');
  assert.ok(Math.abs(v.defaultPlaybackRate - 1.5) < 1e-6, 'a held gesture never rewrites the default');
});

test('delivery bytes belong to the video that produced them', () => {
  const f = fixture(2, { mse: true });
  const [mine, neighbour] = f.videos;
  neighbour.paused = true;
  const myMedia = new f.win.MediaSource();
  mine.currentSrc = f.win.URL.createObjectURL(myMedia);
  const neighbourMedia = new f.win.MediaSource();
  neighbour.currentSrc = f.win.URL.createObjectURL(neighbourMedia);
  const myBuffer = myMedia.addSourceBuffer('video/mp4');
  const neighbourBuffer = neighbourMedia.addSourceBuffer('video/mp4');

  myBuffer.appendBuffer({ byteLength: 1000 });
  // An ad or a preloading player must not inflate this video's rate.
  neighbourBuffer.appendBuffer({ byteLength: 1_000_000 });

  assert.equal(f.api.snapshot().receivedBytes, 1000, "an ad's appends must not count for this video");
  // The counter follows the selected video, so swapping which one plays swaps the number.
  mine.paused = true;
  neighbour.paused = false;
  assert.equal(f.api.snapshot().receivedBytes, 1_000_000);
});

test('a jump into unbuffered data reports buffering once it is not instant', async () => {
  const f = fixture(), v = f.videos[0];
  v.readyState = 2;
  v.seeking = true;
  assert.equal(f.api.snapshot().buffering, false, 'positioning is not a stall yet');
  await new Promise(resolve => setTimeout(resolve, 750));
  assert.equal(f.api.snapshot().buffering, true, 'waiting for the target data is a stall');
  v.seeking = false;
  v.currentTime += 1;
  assert.equal(f.api.snapshot().buffering, false);
});

test('a ratio preset is stretched into its box and the mirror is written onto the element', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'RATIO_3_4' }), true);
  const rules = f.styles[0].textContent;
  // The ratio travels in the takeover's own stylesheet, which is what keeps the element's box —
  // the thing the takeover's layout check and the device regression measure — exactly where the
  // takeover put it. It stretches the picture into that box (`fill`), because picking a ratio asks
  // to see the picture at that shape rather than letterboxed inside it. The mirror is not here at
  // all: a page stylesheet outranks this sheet, which is why the flip did nothing on pages that
  // style their own player, so it is written on the element instead.
  assert.doesNotMatch(rules, /transform:scaleX/);
  assert.match(rules, /box-sizing:border-box!important/);
  assert.match(rules, /object-fit:fill!important/);
  // The takeover's baseline still declares contain; the preset has to be the later declaration,
  // because a sheet's own rules are settled by source order.
  assert.ok(
    rules.lastIndexOf('object-fit:fill!important') > rules.lastIndexOf('object-fit:contain!important'),
    'the stretch must be declared after the letterbox baseline',
  );
  assert.equal(v.style.getPropertyValue('transform'), 'scaleX(-1)');
  // A 640x360 viewport holding a 3:4 box leaves 270 px for the picture: 185 px of side padding.
  assert.match(rules, /padding:0\.00px 185\.00px!important/);
  // The ratio writes nothing onto the element, and neither the marker nor the controls list is
  // touched by a preset change: the only thing this feature puts on the element is the mirror.
  const marker = v.getAttribute('data-pure-browser-controls');
  const controlsList = v.getAttribute('controlslist');
  assert.ok(marker, 'the takeover marker owns the element');
  assert.equal(v.getAttribute('style'), null);

  // Filling the screen crops instead of stretching, and the mirror stays on the element.
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'FILL' }), true);
  assert.equal(v.style.getPropertyValue('transform'), 'scaleX(-1)');
  assert.equal(v.style.getPropertyPriority('transform'), 'important');
  const filled = f.styles[0].textContent;
  assert.match(filled, /object-fit:cover!important/);
  assert.doesNotMatch(filled, /padding:\d+\.\d\dpx/);

  assert.equal(v.getAttribute('data-pure-browser-controls'), marker);
  assert.equal(v.getAttribute('controlslist'), controlsList);
  assert.equal(v.getAttribute('style'), null);

  // Back to the source's own ratio and orientation: the extra rules leave with the choice.
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('transform'), '', 'clearing the mirror frees the element');
  const natural = f.styles[0].textContent;
  assert.doesNotMatch(natural, /transform:scaleX/);
  assert.doesNotMatch(natural, /object-fit:cover/);
  assert.doesNotMatch(natural, /object-fit:contain!important;box-sizing/);
  assert.doesNotMatch(natural, /padding:\d+\.\d\dpx/);
});

test('a preset survives leaving fullscreen, and an unreadable one is not guessed at', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'RATIO_16_9' }), true);
  assert.match(f.styles[0].textContent, /padding:0\.00px 0\.00px!important/);

  assert.equal(f.command('restoreControls'), true);
  assert.equal(f.styles.length, 0, 'leaving fullscreen takes the preset rules with it');
  assert.equal(v.controls, true);

  // Re-entering fullscreen restores the choice: its rules belong to whichever takeover is live.
  assert.equal(f.command('nativeControls'), true);
  assert.equal(v.style.getPropertyValue('transform'), 'scaleX(-1)');
  assert.match(f.styles[0].textContent, /padding:\d+\.\d\dpx/);

  // A preset this build does not know is not a licence to guess: the picture keeps its own ratio.
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'RATIO_9_16' }), true);
  const rules = f.styles[0].textContent;
  assert.doesNotMatch(rules, /padding:\d+\.\d\dpx/);
  assert.doesNotMatch(rules, /transform:scaleX/);
});

test('the mirror gives the page back its own inline transform', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  // A page that lays its video out with an inline transform has to get it back on exit.
  v.style.setProperty('transform', 'translateZ(0)', 'important');
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('transform'), 'scaleX(-1)');
  assert.equal(f.command('restoreControls'), true);
  assert.equal(v.style.getPropertyValue('transform'), 'translateZ(0)');
  assert.equal(v.style.getPropertyPriority('transform'), 'important');
});

test('a video the fullscreen UA rule strips transforms from is mirrored through scale', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  // The video is the fullscreen element: `transform: scaleX(-1)` is written, accepted by the style
  // object, and then resolved to `none` by the UA rule for `:fullscreen`. `scale` is the
  // declaration that reaches the screen there, so it has to be what the element carries.
  v.dropTransform = true;
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('scale'), '-1 1');
  assert.equal(v.style.getPropertyPriority('scale'), 'important');
  assert.equal(v.style.getPropertyValue('transform'), '', 'the refused declaration is not left behind');
  assert.equal(root.style.getPropertyValue('transform'), '', 'the video itself carried the mirror');
  assert.equal(root.style.getPropertyValue('scale'), '');
  // Turning it off restores the property that was actually used.
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('scale'), '');
  // ...and a page's own scale survives the round trip, like its transform does.
  v.style.setProperty('scale', '1.2 1.2', 'important');
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('scale'), '-1 1');
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('scale'), '1.2 1.2');
  assert.equal(v.style.getPropertyPriority('scale'), 'important');
});

test('a video that refuses both declarations still mirrors through the takeover wrapper', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  // A page can refuse the declaration on the video itself: its own !important, an id-scoped rule, a
  // frozen style object, or a player that paints the video outside the element. The wrapper is the
  // next layer showing the same thing, and it is the one the takeover already owns.
  v.dropTransform = true;
  v.dropScale = true;
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'NATURAL' }), true);
  assert.equal(v.style.getPropertyValue('transform'), '', 'the video is left as the page had it');
  assert.equal(root.style.getPropertyValue('transform'), 'scaleX(-1)');
  assert.equal(root.style.getPropertyPriority('transform'), 'important');
  // Leaving fullscreen restores that wrapper and nothing else.
  assert.equal(f.command('restoreControls'), true);
  assert.equal(root.style.getPropertyValue('transform'), '');
});

test('a mirror no layer accepts is reported as failed with its reason', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  v.dropTransform = true;
  v.dropScale = true;
  root.dropTransform = true;
  root.dropScale = true;
  assert.equal(f.command('setVideoTransform', { mirror: true, fit: 'NATURAL' }), false);
  const state = f.messages.filter((message) => message.type === 'state').pop();
  assert.match(String(state.lastCommandError), /no layer accepted the mirror declaration/);
  assert.match(String(state.lastCommandError), /2 elements, 4 declarations/, 'the report says what was tried');
});

test('the ratio box comes from the viewport, not from the box our own padding sized', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  // Measured on an API 37 device after a rotation: the element reported 606x914 against a 411x914
  // portrait viewport — the stale landscape padding plus a collapsed content box. Its client box
  // contains the padding this feature wrote, so deriving the next padding from it computes the
  // padding from its own output. The viewport is the box the takeover pins the element to.
  f.win.innerWidth = 411;
  f.win.innerHeight = 914;
  root.clientWidth = 606;
  root.clientHeight = 914;
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'RATIO_3_4' }), true);
  assert.match(f.styles[0].textContent, /padding:183\.00px 0\.00px!important/);
});

test('a rotation that reports a zero-sized box is retried until the box is usable', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'RATIO_3_4' }), true);
  assert.match(f.styles[0].textContent, /padding:0\.00px 185\.00px!important/);
  // A rotation reports the box at 0x0 for a frame while the new layout is being committed. Deriving
  // the padding from that would drop it for good: the rotation is the last resize the page hears
  // about, so nothing would put it back and the picture would stay stretched into a box nothing
  // sized.
  const before = f.timers.size;
  root.clientWidth = 0;
  root.clientHeight = 0;
  f.event('resize');
  assert.doesNotMatch(f.styles[0].textContent, /padding:\d+\.\d\dpx/, 'no padding can be derived from 0x0');
  assert.equal(f.timers.size, before + 1, 'the probe has to ask again for a usable box');
  root.clientWidth = 320;
  root.clientHeight = 640;
  const pending = [...f.timers.values()];
  f.timers.clear();
  pending.forEach(fn => fn());
  assert.match(f.styles[0].textContent, /padding:106\.67px 0\.00px!important/);
});

test('a box that never becomes usable is retried a bounded number of times', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'RATIO_3_4' }), true);
  const retries = [];
  f.win.setTimeout = (fn) => { retries.push(fn); return retries.length; };
  root.clientWidth = 0;
  root.clientHeight = 0;
  f.event('resize');
  assert.equal(retries.length, 1, 'a zero-sized box is asked about again');
  let rounds = 0;
  while (retries.length && rounds < 6) { retries.shift()(); rounds++; }
  // Three attempts after the initial one, then quiet: a page whose box never becomes usable must
  // not leave a timer polling behind it.
  assert.equal(rounds, 3, 'the retry chain is bounded');
  assert.equal(retries.length, 0);
});

test('a ratio preset follows the viewport and releases its listener when it ends', () => {
  const f = fixture(), v = f.videos[0], root = f.container([v, f.container()]);
  f.doc.fullscreenElement = root;
  assert.equal(f.command('nativeControls'), true);
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'RATIO_3_4' }), true);
  // A rotated viewport changes the padding, so the page has to be listening for it.
  assert.equal(f.listeners('resize'), 1, 'the ratio preset has to hear about a new viewport');
  assert.equal(f.listeners('orientationchange'), 1);
  const before = f.styles[0].textContent;
  root.clientWidth = 320;
  root.clientHeight = 640;
  f.event('resize');
  assert.notEqual(f.styles[0].textContent, before, 'the padding is derived from the viewport');
  // Choosing another preset releases it...
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'NATURAL' }), true);
  assert.equal(f.listeners('resize'), 0, 'a preset that no longer needs a viewport must stop listening');
  // ...and so does leaving fullscreen, which is the only path that clears a live ratio.
  assert.equal(f.command('setVideoTransform', { mirror: false, fit: 'RATIO_16_9' }), true);
  assert.equal(f.listeners('resize'), 1);
  assert.equal(f.command('restoreControls'), true);
  assert.equal(f.listeners('resize'), 0, 'leaving fullscreen releases the listener');
  assert.equal(f.listeners('orientationchange'), 0);
});
