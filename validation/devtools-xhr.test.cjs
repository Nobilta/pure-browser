'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

test('XHR wrapper passes original open/setRequestHeader arguments before observing them', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'http://127.0.0.1:8875/xhr',
    runScripts: 'outside-only',
  });
  const { window } = dom;
  class FakeXHR {
    open(method, url) {
      this.openMethod = method;
      this.openUrl = url;
      this.openCallsAtOriginal = method && method.calls;
    }
    setRequestHeader(name, value) {
      this.headerName = name;
      this.headerValue = value;
      this.headerCallsAtOriginal = value && value.calls;
    }
    send() {}
    abort() {}
    addEventListener() {}
    removeEventListener() {}
  }
  window.XMLHttpRequest = FakeXHR;
  window.__bridge = { postMessage: () => {} };
  const ns = '__pureXhrTest';
  const source = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/assets/devtools-capture.js'), 'utf8')
    .replace('__BRIDGE__', '__bridge')
    .replace('__TOKEN__', 'token')
    .replace('__NAMESPACE__', ns);
  window.eval(source);

  const xhr = new window.XMLHttpRequest();
  const method = { calls: 0, toString() { this.calls++; return this.calls === 1 ? 'POST' : 'GET'; } };
  xhr.open(method, '/api/echo');
  assert.strictEqual(xhr.openMethod, method, 'open received a converted method');
  assert.strictEqual(method.calls, 0, 'method was converted before the real open');

  const header = { calls: 0, toString() { this.calls++; return this.calls === 1 ? 'first' : 'second'; } };
  xhr.setRequestHeader('X-Order', header);
  assert.strictEqual(xhr.headerValue, header, 'setRequestHeader received a converted value');
  assert.strictEqual(xhr.headerCallsAtOriginal, 0, 'header was converted before the real setRequestHeader');
  assert.strictEqual(header.calls, 0, 'capture coerced or re-evaluated the header value');
  dom.window.close();
});
