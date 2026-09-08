const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/playback-probe.js'), 'utf8');

function fixture(count = 1) {
  const listeners = new Map(), timers = new Map(), messages = [], styles = [];
  let nextTimer = 0;
  const doc = {
    baseURI: 'https://example.com/watch', hidden: false, fullscreenElement: null,
    head: { appendChild(style) { styles.push(style); } },
    createElement() { return { textContent: '', sheet: { cssRules: [{}] }, remove() { const i = styles.indexOf(this); if (i >= 0) styles.splice(i, 1); } }; },
    addEventListener(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
    removeEventListener(name, fn) { listeners.get(name)?.delete(fn); },
    querySelectorAll(name) { return name === 'video' ? videos : []; },
  };
  function event(type, target) { for (const fn of listeners.get(type) || []) fn({ type, target }); }
  const videos = Array.from({ length: count }, (_, i) => {
    let rate = 1;
    const attributes = new Map();
    const video = {
      tagName: 'VIDEO', currentSrc: `https://example.com/${i}.mp4`, src: '', controls: true,
      paused: false, ended: false, muted: false, currentTime: 20, duration: 120, readyState: 4,
      videoWidth: 1280, videoHeight: 720, defaultPlaybackRate: 1,
      seekable: { length: 1, start: () => 0, end: () => 120 },
      querySelectorAll: () => [], getBoundingClientRect: () => ({ width: 640, height: 360 }),
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
    performance: { getEntriesByType: () => [] },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible' }),
    setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { timers.delete(id); },
    MutationObserver: class { observe() {} disconnect() {} },
    addEventListener() {}, removeEventListener() {},
    mybrowserMediaProbe: { postMessage(raw) { messages.push(JSON.parse(raw)); } },
  };
  const api = vm.runInNewContext(`${source}(window)`, { window: win, URL });
  let commandId = 0;
  function command(type, values = {}, target = api.snapshot()) {
    return api.command({ type, id: ++commandId, frameId: target.frameId, videoId: target.videoId, ...values });
  }
  return { api, videos, doc, win, event, command, messages, timers, styles };
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
test('inline videos and custom fullscreen containers retain webpage controls', () => {
  const f = fixture(), v = f.videos[0];
  assert.equal(f.api.snapshot().nativeControlsAvailable, false);
  assert.equal(f.command('nativeControls'), false);
  f.doc.fullscreenElement = { contains: child => child === v };
  assert.equal(f.api.snapshot().fullscreen, true);
  assert.equal(f.command('nativeControls'), false);
  assert.equal(v.controls, true);
  f.doc.fullscreenElement = v;
  v.controls = false;
  assert.equal(f.command('nativeControls'), false);
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
test('YouTube and embedded YouTube players never hand off their controls', () => {
  for (const host of ['youtube.com', 'm.youtube.com', 'www.youtube.com', 'www.youtube-nocookie.com']) {
    const f = fixture(), v = f.videos[0];
    f.win.location.href = `https://${host}/watch?v=abc`;
    f.doc.fullscreenElement = v;
    assert.equal(f.api.snapshot().nativeControlsAvailable, false, host);
    assert.equal(f.command('nativeControls'), false, host);
    assert.equal(v.controls, true, host);
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
