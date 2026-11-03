'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

test('DOM pagination survives eviction of the parent and child ids', () => {
  const dom = new JSDOM('<!doctype html><html><head></head><body><div id="big"></div></body></html>', {
    url: 'http://127.0.0.1:8875/dom',
    runScripts: 'outside-only',
  });
  const { window } = dom;
  const big = window.document.getElementById('big');
  for (let i = 0; i < 20500; i++) {
    const span = window.document.createElement('span');
    big.appendChild(span);
  }
  const ns = '__pureDomTest';
  window.__bridge = { postMessage: () => {} };
  const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge')
    .replace('__TOKEN__', 'token')
    .replace('__NAMESPACE__', ns);
  window.eval(source);
  const api = window[ns];
  assert.ok(api, 'DOM accessor was not installed');

  function children(id, start) {
    const result = JSON.parse(api.domChildren(id, start, 100));
    assert.ok(!result.invalid, 'parent became invalid at offset ' + start);
    return result;
  }

  const root = JSON.parse(api.domRoot()).root;
  const htmlKids = children(root.id, 0).nodes;
  const body = htmlKids.find(node => node.name === 'body');
  assert.ok(body);
  const bodyKids = children(body.id, 0).nodes;
  const bigNode = bodyKids.find(node => node.id && node.name === 'div');
  assert.ok(bigNode);

  const stableFirstId = children(bigNode.id, 0).nodes[0].id;
  let loaded = 0;
  while (loaded < 20100) {
    const page = children(bigNode.id, loaded);
    assert.ok(page.nodes.length > 0);
    loaded += page.nodes.length;
    assert.strictEqual(page.start, loaded - page.nodes.length);
  }
  const tail = children(bigNode.id, 20400);
  assert.strictEqual(tail.nodes.length, 100);
  assert.strictEqual(tail.total, 20500);
  // The parent and a much earlier child were evicted from the 20k map by now; path ids
  // must still resolve both.
  const early = children(bigNode.id, 0).nodes[0];
  assert.strictEqual(early.id, stableFirstId, 'evicted live node was assigned a new id');
  assert.ok(!JSON.parse(api.domHtml(early.id)).invalid, 'early child id became invalid');
  assert.ok(!JSON.parse(api.domHtml(tail.nodes[99].id)).invalid, 'tail child id became invalid');

  const inserted = window.document.createElement('span');
  big.insertBefore(inserted, big.firstChild);
  const shifted = children(bigNode.id, 0).nodes;
  assert.ok(shifted.some(node => node.id === stableFirstId));
  assert.ok(!JSON.parse(api.domHtml(stableFirstId)).invalid,
    'moved node id was not re-associated with its new path');
  assert.ok(!JSON.parse(api.domChildren(stableFirstId, 0, 100)).invalid);
  dom.window.close();
});

test('an old node id is rejected after the node is replaced with a same-tag node', () => {
  const dom = new JSDOM('<!doctype html><html><head></head><body><div id="old">old</div></body></html>', {
    url: 'http://127.0.0.1:8875/dom',
    runScripts: 'outside-only',
  });
  const { window } = dom;
  window.__bridge = { postMessage: () => {} };
  const ns = '__pureDomIdentity';
  const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge')
    .replace('__TOKEN__', 'token')
    .replace('__NAMESPACE__', ns);
  window.eval(source);
  const api = window[ns];
  const root = JSON.parse(api.domRoot()).root;
  const htmlKids = JSON.parse(api.domChildren(root.id, 0, 100)).nodes;
  const body = htmlKids.find(node => node.name === 'body');
  const bodyKids = JSON.parse(api.domChildren(body.id, 0, 100)).nodes;
  const old = bodyKids.find(node => node.name === 'div');
  assert.strictEqual(JSON.parse(api.domHtml(old.id)).invalid, undefined);

  const replacement = window.document.createElement('div');
  replacement.id = 'new';
  replacement.textContent = 'new';
  const oldElement = window.document.getElementById('old');
  oldElement.replaceWith(replacement);

  const stale = JSON.parse(api.domHtml(old.id));
  assert.strictEqual(stale.invalid, true, 'old id silently resolved to the replacement');
  const freshKids = JSON.parse(api.domChildren(body.id, 0, 100)).nodes;
  const fresh = freshKids.find(node => node.name === 'div');
  assert.notStrictEqual(fresh.id, old.id, 'replacement reused the old id');
  assert.strictEqual(JSON.parse(api.domHtml(fresh.id)).invalid, undefined);
  dom.window.close();
});

test('deep DOM nodes get short unique ids instead of truncated paths', () => {
  const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
    url: 'http://127.0.0.1:8875/dom', runScripts: 'outside-only',
  });
  const { window } = dom;
  let parent = window.document.body;
  for (let i = 0; i < 70; i++) {
    const div = window.document.createElement('div');
    div.id = 'depth-' + i;
    parent.appendChild(div);
    parent = div;
  }
  window.__bridge = { postMessage: () => {} };
  const ns = '__pureDomDeep';
  const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge').replace('__TOKEN__', 'token').replace('__NAMESPACE__', ns);
  window.eval(source);
  const api = window[ns];
  const root = JSON.parse(api.domRoot()).root;
  const body = JSON.parse(api.domChildren(root.id, 0, 100)).nodes.find(n => n.name === 'body');
  let node = JSON.parse(api.domChildren(body.id, 0, 100)).nodes[0];
  const seen = new Set();
  for (let depth = 0; depth < 70; depth++) {
    assert.ok(node.id.length <= 32, 'deep id grew to ' + node.id.length);
    assert.ok(!seen.has(node.id), 'duplicate deep id at depth ' + depth);
    seen.add(node.id);
    node = JSON.parse(api.domChildren(node.id, 0, 100)).nodes[0];
  }
  assert.strictEqual(seen.size, 70);
  dom.window.close();
});

test('source snapshots have a new id after reset so an export can reject a mixed document', () => {
  const dom = new JSDOM('<!doctype html><html><body><p>first</p></body></html>', {
    url: 'http://127.0.0.1:8875/source', runScripts: 'outside-only',
  });
  const { window } = dom;
  window.__bridge = { postMessage: () => {} };
  const ns = '__pureSourceTest';
  const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge').replace('__TOKEN__', 'token').replace('__NAMESPACE__', ns);
  window.eval(source);
  const api = window[ns];
  const first = JSON.parse(api.sourceInfo());
  const firstPage = JSON.parse(api.sourcePage('0', '65536', '1000'));
  assert.strictEqual(firstPage.sourceId, first.sourceId);
  window.document.body.innerHTML = '<p>second</p>';
  api.resetSource();
  const second = JSON.parse(api.sourceInfo());
  assert.ok(second.sourceId > first.sourceId, 'source snapshot id did not advance');
  assert.ok(second.sourceId !== first.sourceId);
  dom.window.close();
});

function whitespaceFixture(html, check) {
  const dom = new JSDOM(html, {url: 'http://127.0.0.1:8875/dom', runScripts: 'outside-only'});
  const { window } = dom;
  window.__bridge = {postMessage() {}};
  window.eval(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge').replace('__TOKEN__', 'token').replace('__NAMESPACE__', '__whitespace'));
  const api = window.__whitespace;
  function children(selector, offset = 0) {
    const hit = JSON.parse(api.domSearch(selector, 50)).results.find(n =>
      n.path && n.id && n.match === 'selector') || JSON.parse(api.domSearch(selector, 50)).results[0];
    assert.ok(hit, 'Missing search result for ' + selector);
    return JSON.parse(api.domChildren(hit.id, offset, 100));
  }
  try { check(window, api, children); } finally { window.close(); }
}

test('formatting whitespace is metadata; inline, preformatted and nonbreaking spaces remain visible', () => {
  whitespaceFixture(`<html><body>
    <div id="blocks">\n  <div>A</div>\n\t<div>B</div>\n</div>
    <div id="inline"><span>A</span>\n <span>B</span></div>
    <span id="inline-parent">\n <b>B</b>\n</span>
    <pre id="pre">A<b>B</b>\n\t</pre><textarea id="textarea"> \n\t </textarea>
    <div id="css" style="white-space:pre-wrap"> \n\t </div>
    <div id="preline" style="white-space:pre-line"> \n </div>
    <div id="breaking" style="white-space:break-spaces">   </div>
    <div id="nbsp">&nbsp;</div><div id="long"></div>
  </body></html>`, (window, api, children) => {
    assert.ok(children('#blocks').nodes.filter(n => n.kind === 'text').every(n => n.formattingWhitespace));
    for (const selector of ['#inline', '#inline-parent', '#pre', '#textarea', '#css', '#preline', '#breaking', '#nbsp']) {
      const texts = children(selector).nodes.filter(n => n.kind === 'text');
      assert.ok(texts.length > 0, selector);
      assert.ok(texts.every(n => !n.formattingWhitespace), selector + ' meaningful whitespace was hidden');
    }
    window.document.querySelector('#long').textContent = ' '.repeat(3000) + 'actual content';
    const long = children('#long').nodes[0];
    assert.ok(long.textTruncated);
    assert.equal(long.text.trim(), '');
    assert.equal(long.formattingWhitespace, false, 'full nodeValue must determine classification');
  });
});

test('whitespace filtering leaves raw page offsets, node ids and search paths intact', () => {
  whitespaceFixture('<html><body><div id="paged"></div></body></html>', (window, api, children) => {
    const parent = window.document.querySelector('#paged');
    for (let i = 0; i < 160; i++) {
      parent.appendChild(window.document.createTextNode('\n  '));
      const node = window.document.createElement('div');
      node.id = 'child-' + i;
      parent.appendChild(node);
    }
    const loaded = [];
    for (let start = 0; start < 320; start += 100) {
      const page = children('#paged', start);
      assert.equal(page.start, start);
      assert.equal(page.total, 320);
      loaded.push(...page.nodes);
    }
    assert.equal(loaded.length, 320);
    assert.equal(loaded.filter(n => !n.formattingWhitespace).length, 160);
    const target = JSON.parse(api.domSearch('#child-120', 50)).results[0];
    assert.equal(target.path.at(-1).index, 241);
    assert.equal(target.id, loaded[241].id);
    assert.ok(JSON.parse(api.domHtml(target.id)).html.includes('child-120'));
  });
});
