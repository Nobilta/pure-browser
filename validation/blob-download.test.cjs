const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/blob-download.js'), 'utf8');

const CHUNK = 1 << 20;
/** Lets the script's own promise chain (slice -> arrayBuffer -> postMessage) run to completion. */
const flush = () => new Promise(resolve => setImmediate(resolve));

/**
 * A page environment whose URL.createObjectURL and click behave the way Chromium's do.
 *
 * [platform] stands in for the browser's own registry: createObjectURL registers a Blob there and
 * revokeObjectURL removes it, which is exactly what the injected script must survive — it keeps its
 * own reference to the Blob so a revoked address is still readable.
 */
function fixture() {
  const posts = [], listeners = [], platform = new Map();
  let nextId = 0;

  class Blob {
    constructor(parts) { this.parts = parts.map(part => Buffer.from(part)); }
    get size() { return this.parts.reduce((total, part) => total + part.length, 0); }
    slice(start, end) {
      const all = Buffer.concat(this.parts);
      const part = all.subarray(start, Math.min(end, all.length));
      return { arrayBuffer: () => Promise.resolve(part.buffer.slice(part.byteOffset, part.byteOffset + part.length)) };
    }
  }

  const document = {
    listeners: {},
    addEventListener(name, callback) { (this.listeners[name] = this.listeners[name] || []).push(callback); },
    click(anchor) { (this.listeners.click || []).forEach(callback => callback({ target: { closest: () => anchor } })); },
  };

  const context = vm.createContext({
    Blob, Promise, JSON, String, Map, ArrayBuffer, console, document,
    URL: {
      createObjectURL(blob) { const url = 'blob:https://example.com/' + (++nextId); platform.set(url, blob); return url; },
      revokeObjectURL(url) { platform.delete(url); },
    },
  });
  context.window = context;
  context.mybrowserPageFile = {
    postMessage(raw) {
      if (raw instanceof ArrayBuffer) posts.push({ bytes: Buffer.from(raw) });
      else posts.push(JSON.parse(raw));
    },
    addEventListener(_, callback) { listeners.push(callback); },
  };
  vm.runInContext(source, context);
  // The script announces itself so the app has a reply channel before it asks anything; that
  // greeting is consumed here because every test below starts after the handshake.
  const greeting = posts.splice(0, posts.length).map(entry => entry.type);
  assert.deepEqual(greeting, ['hello']);

  const send = payload => listeners.forEach(listener => listener({ data: JSON.stringify(payload) }));
  return { context, posts, platform, send, Blob, document, api: context.__purePageFileDownload };
}

/** Groups each control message with the bytes that followed it. */
function drain(posts) {
  const events = [];
  for (const entry of posts) {
    if (entry.bytes) events[events.length - 1].bytes = entry.bytes;
    else events.push({ ...entry });
  }
  posts.length = 0;
  return events;
}

function produce(f, body, name = 'report.bin') {
  const url = f.context.URL.createObjectURL(new f.Blob([body]));
  f.document.click({ href: url, getAttribute: () => name });
  return url;
}

test('the page announces itself so the app has a reply channel before it asks', () => {
  const posts = [], listeners = [];
  const context = vm.createContext({ JSON, String, Map, console, document: { addEventListener() {} } });
  context.window = context;
  context.mybrowserPageFile = { postMessage(raw) { posts.push(JSON.parse(raw)); }, addEventListener(_, cb) { listeners.push(cb); } };
  vm.runInContext(source, context);
  assert.deepEqual(posts, [{ type: 'hello' }]);
});

test('a created object URL is remembered and reported with the anchor name', () => {
  const f = fixture();
  const url = produce(f, 'hello');
  f.send({ type: 'inspect', url, probe: 7 });
  assert.deepEqual(drain(f.posts), [{ type: 'info', probe: 7, size: 5, name: 'report.bin' }]);
});

test('a URL the page never created is reported as unknown', () => {
  const f = fixture();
  f.send({ type: 'inspect', url: 'blob:https://example.com/gone', probe: 1 });
  assert.deepEqual(drain(f.posts), [{ type: 'unknown', probe: 1 }]);
});

test('a revoked address is still readable: revoking frees the name, not the Blob', () => {
  const f = fixture();
  const url = produce(f, 'kept');
  f.context.URL.revokeObjectURL(url);
  assert.equal(f.platform.size, 0);
  f.send({ type: 'inspect', url, probe: 2 });
  assert.deepEqual(drain(f.posts), [{ type: 'info', probe: 2, size: 4, name: 'report.bin' }]);
});

test('a full transfer is pulled one slice at a time, in order, and ends once', async () => {
  const f = fixture();
  const body = Buffer.alloc(2 * CHUNK + 1234, 0x5a);
  const url = produce(f, body);
  f.send({ type: 'inspect', url, probe: 1 });
  f.send({ type: 'start', url, transfer: 42 });
  const opened = drain(f.posts);
  assert.deepEqual(opened[0], { type: 'info', probe: 1, size: body.length, name: 'report.bin' });
  assert.deepEqual(opened[1], { type: 'begin', transfer: 42, size: body.length, name: 'report.bin' });

  const written = [];
  let guard = 0;
  while (guard++ < 8) {
    f.send({ type: 'advance', transfer: 42, written: written.reduce((total, part) => total + part.length, 0) });
    await flush();
    const events = drain(f.posts);
    const end = events.find(event => event.type === 'end');
    if (end) { assert.equal(end.size, body.length); break; }
    const [chunk] = events.filter(event => event.type === 'chunk');
    assert.equal(chunk.offset, written.reduce((total, part) => total + part.length, 0));
    assert.equal(chunk.bytes.length, chunk.length);
    written.push(chunk.bytes);
  }
  assert.equal(Buffer.concat(written).length, body.length);
  assert.ok(Buffer.concat(written).equals(body));
});

test('a slice never exceeds the page-side cap', async () => {
  const f = fixture();
  const url = produce(f, Buffer.alloc(3 * CHUNK));
  f.send({ type: 'start', url, transfer: 1 });
  drain(f.posts);
  f.send({ type: 'advance', transfer: 1, written: 0 });
  await flush();
  const chunk = drain(f.posts).find(event => event.type === 'chunk');
  assert.equal(chunk.length, CHUNK);
});

test('a finished transfer releases its Blob', async () => {
  const f = fixture();
  const body = Buffer.alloc(CHUNK);
  const url = produce(f, body);
  f.send({ type: 'inspect', url, probe: 1 });
  f.send({ type: 'start', url, transfer: 3 });
  drain(f.posts);
  assert.equal(f.api.state().tracked, 1);
  f.send({ type: 'advance', transfer: 3, written: 0 });
  await flush();
  drain(f.posts);
  f.send({ type: 'advance', transfer: 3, written: body.length });
  const events = drain(f.posts);
  assert.equal(events.find(event => event.type === 'end').size, body.length);
  // The bytes are across, so the document must not still be holding the Blob they came from.
  assert.equal(f.api.state().tracked, 0);
});

test('a cancelled transfer releases its Blob', () => {
  const f = fixture();
  const url = produce(f, Buffer.alloc(4 * CHUNK));
  f.send({ type: 'start', url, transfer: 4 });
  drain(f.posts);
  assert.equal(f.api.state().tracked, 1);
  f.send({ type: 'cancel', transfer: 4 });
  assert.equal(f.api.state().tracked, 0);
});

test('a released address cannot be started again', () => {
  const f = fixture();
  const url = produce(f, Buffer.alloc(2 * CHUNK));
  f.send({ type: 'start', url, transfer: 5 });
  drain(f.posts);
  f.send({ type: 'cancel', transfer: 5 });
  f.send({ type: 'start', url, transfer: 6 });
  assert.deepEqual(drain(f.posts), [{ type: 'error', transfer: 6, reason: 'unavailable' }]);
});

test('a channel failure while slicing releases the Blob', async () => {
  const f = fixture();
  const url = produce(f, Buffer.alloc(2 * CHUNK));
  f.send({ type: 'start', url, transfer: 7 });
  drain(f.posts);
  assert.equal(f.api.state().tracked, 1);
  // The bytes cannot be delivered: the channel is gone. The transfer has to end and the Blob go.
  f.context.mybrowserPageFile.postMessage = () => { throw new Error('channel closed'); };
  f.send({ type: 'advance', transfer: 7, written: 0 });
  await flush();
  assert.equal(f.api.state().tracked, 0);
  assert.equal(f.api.state().active, null);
});

test('a second transfer is refused while one is running', () => {
  const f = fixture();
  const first = produce(f, 'one');
  const second = produce(f, 'two');
  f.send({ type: 'start', url: first, transfer: 1 });
  drain(f.posts);
  f.send({ type: 'start', url: second, transfer: 2 });
  assert.deepEqual(drain(f.posts), [{ type: 'error', transfer: 2, reason: 'busy' }]);
});

test('cancelling stops the slices', async () => {
  const f = fixture();
  const url = produce(f, Buffer.alloc(2 * CHUNK));
  f.send({ type: 'start', url, transfer: 9 });
  drain(f.posts);
  f.send({ type: 'cancel', transfer: 9 });
  f.send({ type: 'advance', transfer: 9, written: 0 });
  await flush();
  assert.deepEqual(drain(f.posts), []);
});

test('an advance for a transfer that is not running is ignored', async () => {
  const f = fixture();
  const url = produce(f, Buffer.alloc(CHUNK));
  f.send({ type: 'start', url, transfer: 5 });
  drain(f.posts);
  f.send({ type: 'advance', transfer: 6, written: 0 });
  await flush();
  assert.deepEqual(drain(f.posts), []);
});

test('the tracked Blob map is bounded', () => {
  const f = fixture();
  for (let index = 0; index < 40; index++) f.context.URL.createObjectURL(new f.Blob(['x']));
  assert.equal(f.api.state().tracked, 16);
});

test('a navigation without the download attribute is not remembered', () => {
  const f = fixture();
  const url = f.context.URL.createObjectURL(new f.Blob(['x']));
  f.document.click({ href: url, getAttribute: () => null, closest: () => null });
  f.send({ type: 'inspect', url, probe: 3 });
  assert.equal(drain(f.posts)[0].name, '');
});

test('a click on a blob: anchor inside a nested element is still seen', () => {
  const f = fixture();
  const url = produce(f, 'x', 'inner.zip');
  f.send({ type: 'inspect', url, probe: 4 });
  assert.equal(drain(f.posts)[0].name, 'inner.zip');
});

test('the page script installs itself once', () => {
  const f = fixture();
  const before = f.context.URL.createObjectURL;
  vm.runInContext(source, f.context);
  assert.equal(f.context.URL.createObjectURL, before);
});
