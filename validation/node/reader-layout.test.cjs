const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

test('reader bottom spacing follows measured controls and zoom, and survives style changes', () => {
  const dom = new JSDOM(fs.readFileSync(path.join(__dirname, '../fixtures/reader-fixture.html'), 'utf8'), {
    url: 'https://example.test/article', runScripts: 'outside-only'
  });
  const { window } = dom;
  window.scrollTo = () => {};
  window.CSSStyleSheet = class { replaceSync(text) { this.text = text; } };
  window.document.adoptedStyleSheets = [];
  const viewport = new window.EventTarget();
  viewport.scale = 1;
  Object.defineProperty(window, 'visualViewport', {value: viewport});
  Object.defineProperty(window, 'devicePixelRatio', {value: 2});
  const asset = name => fs.readFileSync(path.join(__dirname, '../../app/src/main/assets', name), 'utf8');
  window.eval(asset('readability.js'));
  window.eval(asset('reader.js'));
  const css = () => window.document.adoptedStyleSheets[0].text;
  try {
    assert.ok(window.__pureReaderState.active);
    window.__pureReaderSetBottomInset(272);
    assert.match(css(), /padding:24px 18px 160px!important/);
    window.__pureReaderApply({paper: 'dark', size: 24});
    assert.match(css(), /padding:24px 18px 160px!important/);
    viewport.scale = 2;
    viewport.dispatchEvent(new window.Event('resize'));
    assert.match(css(), /padding:24px 18px 92px!important/);
    window.__pureReaderSetBottomInset(0);
    assert.match(css(), /padding:24px 18px 24px!important/);
    window.__pureReaderToggle();
    assert.doesNotThrow(() => viewport.dispatchEvent(new window.Event('resize')));
    assert.equal(window.__pureReaderState, null);
  } finally { window.close(); }
});
