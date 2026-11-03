'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

test('fetch header snapshots never coerce a stateful value a second time', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'http://127.0.0.1:8875/fetch', runScripts: 'outside-only',
  });
  const { window } = dom;
  const messages = [];
  window.__bridge = { postMessage: raw => messages.push(JSON.parse(raw)) };
  window.fetch = function (input, init) {
    const headers = init && init.headers;
    if (Array.isArray(headers)) {
      headers.forEach(pair => String(pair[1]));
    } else if (headers && typeof headers === 'object') {
      Object.keys(headers).forEach(key => String(headers[key]));
    }
    return new Promise(function () {});
  };
  const ns = '__pureFetchTest';
  const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge')
    .replace('__TOKEN__', 'token')
    .replace('__NAMESPACE__', ns);
  window.eval(source);

  const arrayValue = { calls: 0, toString() { this.calls++; return this.calls === 1 ? 'first' : 'second'; } };
  window.fetch('/array', {method: 'POST', headers: [['X-Test', arrayValue]]});
  assert.strictEqual(arrayValue.calls, 1, 'array header was coerced after the real fetch');

  const objectValue = { calls: 0, toString() { this.calls++; return this.calls === 1 ? 'first' : 'second'; } };
  window.fetch('/object', {method: 'POST', headers: {'X-Test': objectValue}});
  assert.strictEqual(objectValue.calls, 1, 'object header was coerced after the real fetch');

  let outerReads = 0, pairReads = 0, getterStringCalls = 0;
  const getterPair = new Array(2);
  Object.defineProperty(getterPair, '0', {get() { pairReads++; return 'X-Getter'; }});
  Object.defineProperty(getterPair, '1', {get() {
    pairReads++;
    getterStringCalls++;
    return 'first';
  }});
  const getterHeaders = new Array(1);
  Object.defineProperty(getterHeaders, '0', {get() { outerReads++; return getterPair; }});
  window.fetch('/getters', {method: 'POST', headers: getterHeaders});
  assert.strictEqual(outerReads, 1, 'outer header array getter was read again');
  assert.strictEqual(pairReads, 1, 'header pair getter was read again');
  assert.strictEqual(getterStringCalls, 1);

  const requests = messages.filter(message => message.type === 'net-request');
  assert.strictEqual(requests.length, 3);
  for (const request of requests) {
    assert.strictEqual(request.headersUnavailable, true);
    assert.deepStrictEqual(request.headers, []);
  }
  dom.window.close();
});
