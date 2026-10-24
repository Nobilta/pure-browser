const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const { JSDOM, VirtualConsole } = require('jsdom');

const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/translation.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

function page(t, html) {
  const errors = [];
  const virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', error => errors.push(error.message));
  const dom = new JSDOM(html, { runScripts: 'outside-only', pretendToBeVisual: true, virtualConsole });
  const w = dom.window;
  // jsdom supplies DOM/MutationObserver behavior, but has no layout engine.
  w.HTMLElement.prototype.getBoundingClientRect = function () {
    for (let element = this; element; element = element.parentElement) {
      if (element.hidden || w.getComputedStyle(element).display === 'none') {
        return { top: 0, bottom: 0, width: 0, height: 0 };
      }
    }
    return { top: 0, bottom: 30, width: 100, height: 30 };
  };
  w.eval(source);
  t.after(() => {
    w.__pureTranslateCompareRestore();
    w.__pureTranslateRestore();
    dom.window.close();
    assert.deepEqual(errors, [], 'DOM callbacks must not throw');
  });
  return w;
}

const ready = w => JSON.parse(w.__pureTranslateTakeReady()).items;
const collect = w => JSON.parse(w.__pureTranslateCollect()).items;
const rescan = w => JSON.parse(w.__pureTranslateReplaceCollectNew());
const replace = (w, items, text = 'translated') => w.__pureTranslateApply(
  items.map(item => ({ id: item.id, version: item.version, text })),
);
async function compare(w, items = ready(w)) {
  w.__pureTranslateCompareApply(items.map(item => ({ id: item.id, text: 'translated:' + item.text })), 'zh');
  await tick();
}

test('removing a nested block merges source groups without retaining a child translation', async t => {
  const w = page(t, '<div id="outer">Leading <span><div id="block">Middle</div>Trailing</span></div>');
  w.__pureTranslateCompareStart();
  await compare(w);
  w.document.querySelector('#block').remove();
  await tick();
  const items = ready(w);
  assert.deepEqual(items.map(item => item.text), ['Leading Trailing']);
  await compare(w, items);
  assert.equal(w.document.querySelectorAll('pure-translation-block').length, 1);
  assert.equal(w.__pureTranslation.compareBlocks.size, 1);
  assert.equal(JSON.parse(w.__pureTranslateCompareStatus()).complete, true);
});

test('inserting a block splits an existing paragraph and invalidates its pending result', async t => {
  const w = page(t, '<div id="outer">Leading <span>Trailing</span></div>');
  w.__pureTranslateCompareStart();
  const original = ready(w);
  const block = w.document.createElement('div');
  block.textContent = 'Middle';
  w.document.querySelector('#outer').insertBefore(block, w.document.querySelector('span'));
  await tick();
  assert.equal(w.__pureTranslateCompareApply(original.map(item => ({ id: item.id, text: 'obsolete' })), 'zh'), 0);
  const items = ready(w);
  assert.deepEqual(items.map(item => item.text), ['Leading', 'Middle', 'Trailing']);
  await compare(w, items);
  assert.equal(w.document.querySelectorAll('pure-translation-block').length, 3);
});

test('ancestor class reveal preserves the existing host and adds a new sibling paragraph', async t => {
  const w = page(t, '<style>.closed #panel{display:none}</style><main class="closed"><p>Existing</p><section id="panel"><p>Revealed</p></section></main>');
  w.__pureTranslateCompareStart();
  await compare(w);
  const host = w.document.querySelector('pure-translation-block');
  w.document.querySelector('main').className = '';
  await tick();
  assert.deepEqual(ready(w).map(item => item.text), ['Revealed']);
  assert.equal(w.document.querySelector('pure-translation-block'), host);
});

test('first inline reveal and subsequent text append use one paragraph root', async t => {
  const w = page(t, '<p>Existing</p><p id="target"><span hidden>Revealed</span></p>');
  w.__pureTranslateCompareStart();
  await compare(w);
  w.document.querySelector('span').hidden = false;
  await tick();
  await compare(w);
  w.document.querySelector('#target').append(' tail');
  await tick();
  assert.deepEqual(ready(w).map(item => item.text), ['Revealed tail']);
  // Requeue this taken batch to exercise cancellation as well.
  w.__pureTranslateRequeue(Array.from(w.__pureTranslation.compareBlocks.keys()));
  await compare(w);
  assert.equal(w.document.querySelector('#target').querySelectorAll('pure-translation-block').length, 1);
});

test('inline class and text edits never register a second nested group', async t => {
  const w = page(t, '<p>Hello <span>world</span></p>');
  w.__pureTranslateCompareStart();
  await compare(w);
  w.document.querySelector('span').className = 'highlight';
  await tick();
  assert.deepEqual(ready(w), []);
  w.document.querySelector('span').firstChild.nodeValue = 'earth';
  await tick();
  assert.deepEqual(ready(w).map(item => item.text), ['Hello earth']);
  assert.equal(w.__pureTranslation.compareBlocks.size, 1);
});

for (const attribute of ['translate', 'contenteditable', 'role']) {
  const value = { translate: 'no', contenteditable: 'plaintext-only', role: 'button' }[attribute];
  test(`comparison removes its own translation when ${attribute} excludes a region`, async t => {
    const w = page(t, '<p id="target">Original</p>');
    w.__pureTranslateCompareStart();
    await compare(w);
    w.document.querySelector('#target').setAttribute(attribute, value);
    await tick();
    assert.equal(w.document.querySelectorAll('pure-translation-block').length, 0);
    assert.equal(w.document.querySelector('#target').textContent, 'Original');
    assert.equal(JSON.parse(w.__pureTranslateCompareStatus()).complete, true);
  });

  test(`replacement rejects in-flight results after ${attribute} changes, and removes queued ids`, async t => {
    const w = page(t, '<p id="target">Original</p>');
    const original = collect(w);
    const element = w.document.querySelector('#target');
    element.setAttribute(attribute, value);
    // No microtask wait: apply must check live eligibility independently of the observer.
    assert.equal(replace(w, original), 0);
    assert.equal(element.textContent, 'Original');
    await tick();
    const update = rescan(w);
    assert.deepEqual(update.items, []);
    assert.deepEqual(update.removedIds, [original[0].id]);
    assert.equal(update.total, 0);
    element.removeAttribute(attribute);
    await tick();
    const current = rescan(w).items;
    assert.equal(current.length, 1);
    assert.notEqual(current[0].id, original[0].id);
    assert.equal(replace(w, original), 0);
    assert.equal(replace(w, current), 1);
    w.__pureTranslateRestore();
    assert.equal(element.textContent, 'Original');
  });
}

test('a temporarily excluded in-flight node can retry even when its text is unchanged', async t => {
  const w = page(t, '<p>Original</p>');
  const original = collect(w);
  const p = w.document.querySelector('p');
  p.setAttribute('translate', 'no');
  assert.equal(replace(w, original), 0);
  p.removeAttribute('translate');
  await tick();
  const retry = rescan(w).items;
  assert.equal(retry.length, 1);
  assert.ok(retry[0].version > original[0].version);
  assert.equal(replace(w, retry), 1);
});

for (const scanFirst of [false, true]) {
  test(`a website update rejects stale replacement and preserves restore text (scan first: ${scanFirst})`, async t => {
    const w = page(t, '<p>Original</p>');
    const original = collect(w);
    const p = w.document.querySelector('p');
    p.firstChild.nodeValue = 'Updated';
    await tick();
    let current = scanFirst ? rescan(w).items : null;
    assert.equal(replace(w, original, 'stale'), 0);
    assert.equal(p.textContent, 'Updated');
    if (!current) current = rescan(w).items;
    assert.equal(replace(w, current), 1);
    w.__pureTranslateRestore();
    assert.equal(p.textContent, 'Updated');
  });
}

test('retry reads the current revision and cannot resurrect removed source nodes', async t => {
  const w = page(t, '<p id="changed">Old</p><p id="removed">Removed</p>');
  const original = collect(w);
  w.document.querySelector('#changed').firstChild.nodeValue = 'New';
  w.document.querySelector('#removed').remove();
  await tick();
  w.__pureTranslateReplaceRequeue(original);
  const update = rescan(w);
  assert.deepEqual(update.items.map(item => item.text), ['New']);
  assert.deepEqual(update.removedIds, [original[1].id]);
  assert.equal(replace(w, original), 0);
  assert.equal(replace(w, update.items), 1);
});

test('tab cancellation requeues unchanged source without needing a native batch snapshot', async t => {
  const w = page(t, '<p>Original</p>');
  const original = collect(w);
  w.__pureTranslateReplaceRequeue(original);
  const update = rescan(w);
  assert.equal(update.items.length, 1);
  assert.ok(update.items[0].version > original[0].version);
  assert.equal(replace(w, update.items), 1);
});

test('translator-only writes stay clean while website observers can add content', async t => {
  const w = page(t, '<p id="target">Original</p>');
  const original = collect(w);
  replace(w, original);
  await tick();
  assert.equal(w.__pureTranslation.replaceDirty, false);
  const observer = new w.MutationObserver(() => {
    if (!w.document.querySelector('#added')) {
      const p = w.document.createElement('p');
      p.id = 'added'; p.textContent = 'Dynamic'; w.document.body.append(p);
    }
  });
  observer.observe(w.document.querySelector('#target'), { characterData: true, subtree: true });
  w.document.querySelector('#target').firstChild.nodeValue = 'Site edit';
  await tick();
  const update = rescan(w);
  assert.deepEqual(update.items.map(item => item.text), ['Site edit', 'Dynamic']);
  observer.disconnect();
});

test('eligibility on the page root also protects direct body text', t => {
  const w = page(t, '<body contenteditable="true">Private draft</body>');
  assert.deepEqual(collect(w), []);
  assert.equal(JSON.parse(w.__pureTranslateCompareStart()).totalGroups, 0);
});

test('removed and reinserted offscreen groups are observed again for lazy translation', async t => {
  const w = page(t, '<main><p>Offscreen</p></main>');
  let observed = 0;
  let notify;
  w.IntersectionObserver = class {
    constructor(callback) { notify = callback; }
    observe() { observed++; }
    unobserve() {}
    disconnect() {}
  };
  const p = w.document.querySelector('p');
  p.getBoundingClientRect = () => ({ top: 5000, bottom: 5030, width: 100, height: 30 });
  w.__pureTranslateCompareStart();
  assert.equal(observed, 1);
  assert.deepEqual(ready(w), []);
  p.remove();
  await tick();
  w.document.querySelector('main').append(p);
  await tick();
  assert.equal(observed, 2);
  notify([{ target: p, isIntersecting: true }]);
  assert.deepEqual(ready(w).map(item => item.text), ['Offscreen']);
});

test('existing text updates respect the same size limit as newly collected nodes', async t => {
  const w = page(t, '<p>Original</p>');
  const original = collect(w);
  const large = 'x'.repeat(5001);
  w.document.querySelector('p').firstChild.nodeValue = large;
  await tick();
  const update = rescan(w);
  assert.deepEqual(update.items, []);
  assert.deepEqual(update.removedIds, [original[0].id]);
  assert.equal(update.truncated, true);
  assert.equal(replace(w, original), 0);
  w.__pureTranslateRestore();
  assert.equal(w.document.querySelector('p').textContent, large);
});

test('excluding an already translated node preserves a website edit and frees its pending id', async t => {
  const w = page(t, '<p>Original</p>');
  const original = collect(w);
  assert.equal(replace(w, original), 1);
  const p = w.document.querySelector('p');
  p.contentEditable = 'true';
  // Set the attribute explicitly because jsdom does not implement the reflected property.
  p.setAttribute('contenteditable', 'true');
  p.firstChild.nodeValue = 'User draft';
  await tick();
  assert.deepEqual(rescan(w).removedIds, [original[0].id]);
  w.__pureTranslateRestore();
  assert.equal(p.textContent, 'User draft');
});
