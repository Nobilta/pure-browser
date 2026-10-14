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
  const events = {}, posts = [], nodes = {};
  // The window listeners (scroll, resize) land in the same map as the document ones; the picker
  // only removes what it added, and the harness never fires them.
  const element = (id, tag) => ({
    nodeType: 1, id, tagName: tag || 'DIV', style: { setProperty() {}, display: '' }, classList: [],
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 10, height: 10, right: 10, bottom: 10 }),
    matches: () => false, closest: () => null, parentElement: null, children: [],
    // Tracked by id so a test can read the overlay and the preview sheet the picker creates.
    appendChild(child) { this.children.push(child); if (child.id) nodes[child.id] = child; return child; },
  });
  const body = element('', 'BODY');
  const target = element('hero', 'SPAN');
  target.parentElement = body;
  const document = {
    readyState: 'complete',
    body,
    documentElement: body,
    head: body,
    images: [],
    addEventListener(name, callback) { (events[name] = events[name] || []).push(callback); },
    removeEventListener() {},
    getElementById: (id) => nodes[id] || null,
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
  const scrolls = [];
  context.scrollBy = (opts) => { scrolls.push(opts); };
  // The asset is a function expression, not a self-invoking one: ElementPicker.startScript calls
  // it with `(window)`, and the harness has to do the same or the API is never installed.
  vm.runInContext(source, context)(context);
  return { api: context.__pureElementPicker, events, posts, document, target, body, element, scrolls, nodes };
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

test('touchstart does not scroll under the finger and touchend chooses the hovered element', () => {
  const { api, events, posts, document, target, body, element, scrolls } = fixture();
  const decoy = element('decoy', 'DIV');
  decoy.parentElement = body;
  target.getBoundingClientRect = () => ({ left: 0, top: 700, width: 100, height: 60, right: 100, bottom: 760 });
  assert.equal(api.start({ accent: '#000000', bottomInset: 176 }), true);

  const down = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [{ clientX: 10, clientY: 720 }] };
  events.touchstart.forEach((handler) => handler(down));
  assert.equal(scrolls.length, 0, 'hovering on touchstart must not scroll the viewport while the finger is down');

  // Even if elementFromPoint at touchend would resolve a different node, choose() must commit
  // the element that was highlighted under the finger.
  document.elementFromPoint = () => decoy;
  const up = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [], changedTouches: [{ clientX: 10, clientY: 720 }] };
  events.touchend.forEach((handler) => handler(up));
  assert.equal(scrolls.length, 1, 'choosing an occluded element scrolls it clear of the bottom bar');
  const pick = posts.find((payload) => payload.type === 'pick');
  assert.ok(pick && pick.selector.includes('hero'), `expected #hero to be chosen, got ${JSON.stringify(pick)}`);

  // A trailing click event must be claimed without restarting hover/onDown.
  const click = { cancelable: true, stopped: false, prevented: false,
    stopPropagation() { this.stopped = true; }, preventDefault() { this.prevented = true; },
    clientX: 10, clientY: 720 };
  events.click.forEach((handler) => handler(click));
  assert.equal(click.stopped, true);
  assert.equal(click.prevented, true);
  assert.equal(posts.filter((payload) => payload.type === 'pick').length, 1);
});

test('a finger that slides off its element does not select it on release', () => {
  const { api, events, posts, document, body, target } = fixture();
  api.start({ accent: '#000000', bottomInset: 176 });
  const down = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [{ clientX: 10, clientY: 10 }] };
  events.touchstart.forEach((handler) => handler(down));
  assert.equal(posts.filter((payload) => payload.type === 'pick').length, 0);

  // The finger moves onto the body, which is refused: the highlight has to be withdrawn rather
  // than kept, or the release would commit the element the finger already left.
  document.elementFromPoint = () => body;
  events.touchmove.forEach((handler) => handler(down));
  const up = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [], changedTouches: [{ clientX: 10, clientY: 10 }] };
  events.touchend.forEach((handler) => handler(up));
  assert.equal(posts.filter((payload) => payload.type === 'pick').length, 0,
    'releasing over a refused element must not select the one the finger left');
  assert.equal(api.state().depth, 0, 'the stale candidate must be dropped, not kept as a chain');

  // The element under the finger is still selectable once the finger returns to it.
  document.elementFromPoint = () => target;
  events.touchstart.forEach((handler) => handler(down));
  events.touchend.forEach((handler) => handler(up));
  assert.ok(posts.some((payload) => payload.type === 'pick'), 'a tap on the element still selects it');
});

for (const input of ['touch', 'mouse']) {
  for (const destination of ['element', 'blank']) {
    test(`${input} release uses its updated coordinates over ${destination}`, () => {
      const { api, events, posts, document, target, body, element } = fixture();
      const other = element('other', 'DIV');
      other.parentElement = body;
      document.elementFromPoint = (x) => x < 100 ? target : destination === 'element' ? other : body;
      api.start({ bottomInset: 0 });
      const event = (x, released) => {
        const base = { cancelable: true, stopPropagation() {}, preventDefault() {} };
        return input === 'touch'
          ? { ...base, touches: released ? [] : [{ clientX: x, clientY: 10 }],
              changedTouches: [{ clientX: x, clientY: 10 }] }
          : { ...base, clientX: x, clientY: 10 };
      };
      const names = input === 'touch' ? ['touchstart', 'touchmove', 'touchend'] : ['mousedown', 'mousemove', 'mouseup'];
      events[names[0]].forEach((handler) => handler(event(10, false)));
      events[names[1]].forEach((handler) => handler(event(95, false)));
      // The last move is on hero; the release crosses its edge without another move event.
      events[names[2]].forEach((handler) => handler(event(105, true)));
      const picks = posts.filter((payload) => payload.type === 'pick');
      if (destination === 'element') {
        assert.equal(picks.length, 1);
        assert.equal(picks[0].selector, '#other');
      } else {
        assert.equal(picks.length, 0);
        assert.equal(api.state().depth, 0);
      }
    });
  }
}

test('a blank tap after choosing keeps the choice and shows it again', () => {
  const { api, events, posts, document, body, nodes } = fixture();
  api.start({ accent: '#000000', bottomInset: 176 });
  const at = (x, y, kind) => {
    const base = { cancelable: true, stopPropagation() {}, preventDefault() {} };
    if (kind === 'down') return { ...base, touches: [{ clientX: x, clientY: y }] };
    return { ...base, touches: [], changedTouches: [{ clientX: x, clientY: y }] };
  };
  const tap = (x, y) => {
    events.touchstart.forEach((handler) => handler(at(x, y, 'down')));
    events.touchend.forEach((handler) => handler(at(x, y, 'up')));
  };

  tap(10, 10);
  assert.equal(api.state().selector, '#hero');
  assert.equal(posts.filter((payload) => payload.type === 'pick').length, 1);

  // Pressing the blank part of the page lifts the preview to start a fresh candidate. Releasing
  // there finds nothing, so the choice stands — and because it stands, what the page shows has to
  // go back to matching it. Leaving the element visible while the app's bar still offers to block
  // it was the two halves of the picker disagreeing.
  document.elementFromPoint = () => body;
  tap(10, 300);

  assert.equal(api.state().selector, '#hero', 'a blank tap must not discard the existing choice');
  assert.equal(api.state().depth, 1);
  assert.equal(nodes['__purePickerPreview'].textContent, '#hero{display:none!important;}',
    'the preview has to come back, or the page contradicts the bar');
  assert.equal(nodes['__purePickerBox'].style.display, 'block', 'the outline follows the choice');
  assert.equal(posts.filter((payload) => payload.type === 'pick').length, 1,
    'the choice did not change, so the app must not hear about it a second time');
});

test('a blank tap with nothing chosen selects nothing and reports nothing', () => {
  const { api, events, posts, document, body } = fixture();
  api.start({ accent: '#000000', bottomInset: 176 });
  document.elementFromPoint = () => body;
  const down = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [{ clientX: 10, clientY: 300 }] };
  const up = { cancelable: true, stopPropagation() {}, preventDefault() {}, touches: [], changedTouches: [{ clientX: 10, clientY: 300 }] };
  events.touchstart.forEach((handler) => handler(down));
  events.touchend.forEach((handler) => handler(up));
  assert.equal(api.state().depth, 0);
  assert.equal(posts.filter((payload) => payload.type === 'pick').length, 0);
});
