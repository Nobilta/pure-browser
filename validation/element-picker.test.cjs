const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/element-picker.js'), 'utf8');

/**
 * A page just real enough to start the picker and deliver one touch.
 *
 * The question here is not what the picker draws but what a cancelled touch does: the system sends
 * touchcancel when it takes the gesture over, and treating it as a release chose whatever element
 * was under the finger and wrote a rule nobody asked for.
 */
function fixture() {
  const events = {}, posts = [];
  // The window listeners (scroll, resize) land in the same map as the document ones; the picker
  // only removes what it added, and the harness never fires them.
  const element = (id, tag) => ({
    nodeType: 1, id, tagName: tag || 'DIV', style: { setProperty() {} }, classList: [],
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 10, height: 10, right: 10, bottom: 10 }),
    matches: () => false, closest: () => null, parentElement: null, children: [],
    appendChild(child) { this.children.push(child); return child; },
  });
  const body = element('', 'BODY');
  const target = element('hero', 'SPAN');
  target.parentElement = body;
  const document = {
    readyState: 'complete',
    body,
    documentElement: body,
    images: [],
    addEventListener(name, callback) { (events[name] = events[name] || []).push(callback); },
    removeEventListener() {},
    getElementById: () => null,
    createElement: (tag) => element('', tag),
    querySelectorAll: () => [target],
    elementFromPoint: () => target,
    baseURI: 'https://example.com/',
  };
  const context = vm.createContext({
    document, JSON, String, Map, Number, Math, Array, Object, console,
    CSS: { escape: (value) => value },
    URL: class { constructor(text) { this.href = text; } },
    // The bridge name the Kotlin half registers; the asset posts through it.
    mybrowserElementPicker: { postMessage(raw) { posts.push(JSON.parse(raw)); } },
  });
  const add = (name, callback) => { (events[name] = events[name] || []).push(callback); };
  context.addEventListener = add;
  context.removeEventListener = () => {};
  context.window = context;
  context.top = context;
  context.self = context;
  context.innerWidth = 400;
  context.innerHeight = 800;
  context.scrollBy = () => {};
  // The asset is a function expression, not a self-invoking one: ElementPicker.startScript calls
  // it with `(window)`, and the harness has to do the same or the API is never installed.
  vm.runInContext(source, context)(context);
  return { api: context.__pureElementPicker, events, posts };
}

test('a cancelled touch ends the pick instead of choosing an element', () => {
  const { api, events, posts } = fixture();
  assert.equal(api.start({ accent: '#000000', bottomInset: 0 }), true);
  const down = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [{ clientX: 5, clientY: 5 }] };
  const cancel = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [], changedTouches: [{ clientX: 5, clientY: 5 }] };
  events.touchstart.forEach((handler) => handler(down));
  events.touchcancel.forEach((handler) => handler(cancel));
  assert.equal(api.state().active, false, 'the picker must not stay active after a cancelled touch');
  assert.ok(posts.some((payload) => payload.type === 'ended'), 'the app has to be told the pick ended');
  assert.ok(!posts.some((payload) => payload.type === 'pick' && payload.selector),
    'a cancelled touch must not report a chosen element');
});
