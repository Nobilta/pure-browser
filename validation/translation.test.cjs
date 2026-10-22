const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'translation.js'), 'utf8');

test('translation DOM script compiles and exposes the expected entry points', () => {
  assert.doesNotThrow(() => new vm.Script(source));
  const context = {
    window: {},
    NodeFilter: { SHOW_TEXT: 4 },
    document: {
      body: null, documentElement: null,
      createTreeWalker: () => ({ nextNode: () => false }),
      getElementById: () => null,
    },
    Map,
    String,
  };
  context.window.window = context.window;
  vm.runInNewContext(source, context);
  for (const name of [
    '__pureTranslateCollect', '__pureTranslateApply', '__pureTranslateRestore', '__pureTranslateSelection',
    '__pureTranslateCompareCollect', '__pureTranslateCompareApply', '__pureTranslateCompareRestore',
    '__pureTranslateVisibleIds', '__pureTranslateTranslatedMode',
  ]) {
    assert.equal(typeof context.window[name], 'function', name);
  }
  assert.equal(context.window.__pureTranslateCollect(), '{"items":[],"total":0,"truncated":false}');
  assert.equal(context.window.__pureTranslateTranslatedMode(), 'none');
});
