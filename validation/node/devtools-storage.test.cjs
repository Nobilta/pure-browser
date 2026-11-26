'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { JSDOM } = require('jsdom');

const root = path.join(__dirname, '../../app/src/main/java/com/mybrowser/devtools');
const source = fs.readFileSync(path.join(root, 'StorageReader.kt'), 'utf8');
const template = source.match(/private fun listScript[\s\S]*?"""([\s\S]*?)"""\.trimIndent\(\)/)[1];
const budgets = fs.readFileSync(path.join(root, 'DevToolsBudgets.kt'), 'utf8');
function budget(name) {
  return vm.runInNewContext(budgets.match(new RegExp('const val ' + name + ' = ([^\\n]+)'))[1]);
}
const limit = budget('STORAGE_TOTAL_PREVIEW_CHARS');

function fixture(t) {
  const dom = new JSDOM('', { url: 'https://storage.test/', runScripts: 'outside-only' });
  t.after(() => dom.window.close());
  function list(offset = 0, query = '') {
    const script = template.replace(/\$\{([^}]+)\}/g, (_, expression) => {
      if (expression.startsWith('DevToolsBudgets.')) return budget(expression.split('.')[1]);
      switch (expression) {
        case 'JSONObject.quote(documentMarker)': return JSON.stringify('__marker');
        case 'JSONObject.quote("$documentId")': return JSON.stringify('1');
        case 'kind.js': return 'localStorage';
        case 'JSONObject.quote(query.lowercase().take(200))': return JSON.stringify(query.toLowerCase().slice(0, 200));
        default: throw new Error('Unrecognized Kotlin template expression: ' + expression);
      }
    }).replace(/\$offset/g, String(offset));
    return JSON.parse(dom.window.eval(script));
  }
  return { storage: dom.window.localStorage, list };
}

test('storage pages budget whole keys and values together without changing edit identity', t => {
  const { storage, list } = fixture(t);
  const keys = Array.from({ length: 20 }, (_, i) => i + 'x'.repeat(60000));
  const value = 'v'.repeat(30000);
  for (const key of keys) storage.setItem(key, value);
  const seen = [];
  let offset = 0, pages = 0;
  do {
    const page = list(offset);
    assert.ok(page.entries.reduce((n, [key, value]) => n + key.length + value.length, 0) <= limit);
    for (const [key, preview, chars] of page.entries) {
      seen.push(key);
      assert.equal(preview, value);
      assert.equal(chars, value.length);
    }
    assert.ok(page.nextOffset === null || page.nextOffset > offset);
    offset = page.nextOffset;
    pages++;
  } while (offset !== null);
  assert.ok(pages > 1);
  assert.deepEqual(seen, keys);
});

test('an oversized storage key is skipped without blocking later pages or empty keys', t => {
  const { storage, list } = fixture(t);
  storage.setItem('x'.repeat(budget('STORAGE_VALUE_PREVIEW_CHARS') + 1), '');
  const onlyOversized = list();
  assert.equal(onlyOversized.total, 1);
  assert.equal(onlyOversized.truncated, true);
  assert.deepEqual(onlyOversized.entries, []);
  storage.setItem('', 'empty key is valid');
  const page = list();
  assert.equal(page.truncated, true);
  assert.equal(page.nextOffset, null);
  assert.deepEqual(page.entries, [['', 'empty key is valid', 18]]);
});

test('storage search includes key lengths in the response budget', t => {
  const { storage, list } = fixture(t);
  for (let i = 0; i < 20; i++) storage.setItem(i + 'x'.repeat(60000), 'match' + 'v'.repeat(30000));
  storage.setItem('x'.repeat(limit + 1), 'match');
  const result = list(0, 'match');
  assert.equal(result.total, 21);
  assert.equal(result.truncated, true);
  assert.ok(result.entries.length > 0 && result.entries.length < result.total);
  assert.ok(result.entries.reduce((n, [key, value]) => n + key.length + value.length, 0) <= limit);
});
