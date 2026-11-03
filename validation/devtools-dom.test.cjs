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
  assert.ok(!JSON.parse(api.domHtml(early.id)).invalid, 'early child id became invalid');
  assert.ok(!JSON.parse(api.domHtml(tail.nodes[99].id)).invalid, 'tail child id became invalid');
  dom.window.close();
});
