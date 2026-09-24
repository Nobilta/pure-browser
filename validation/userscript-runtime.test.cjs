const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/userscript-runtime.js'), 'utf8');

function fixture({ url = 'https://www.example.com/page', ready = 'loading', frame = false, bridge = true } = {}) {
  const documentEvents = {}, listeners = [], posts = [], styles = [], timers = new Map(), observers = [];
  let timerId = 0;
  const root = { appendChild(style) { styles.push(style); } };
  const document = {
    readyState: ready, head: root, documentElement: root,
    createElement(tag) { return { tag, textContent: '' }; },
    addEventListener(name, callback) { documentEvents[name] = callback; },
  };
  const context = vm.createContext({
    URL, location: new URL(url), document, apis: [], warnings: [], opened: [],
    setTimeout(fn) { const id = ++timerId; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); },
    console: { warn(...args) { context.warnings.push(args); }, log() {} },
    MutationObserver: class { constructor(callback) { this.callback = callback; observers.push(this); } observe() {} disconnect() { this.done = true; } },
  });
  context.window = context;
  context.top = frame ? {} : context;
  context.open = (url) => { context.opened.push(url); return null; };
  if (bridge) context.testBridge = {
    postMessage(raw) { posts.push(JSON.parse(raw)); },
    addEventListener(_, callback) { listeners.push(callback); },
  };
  const defaults = { id: 'a', name: 'Fixture', namespace: 'test', version: '1', description: '',
    matches: ['*://*.example.com/*'], includes: [], excludes: [], excludeMatches: [],
    runAt: 'document-start', noframes: false, grants: ['none'], needsStorage: false,
    bridge: bridge ? 'testBridge' : '', token: 'private-token', values: '{}', meta: '' };
  return {
    context, posts, styles, observers, timers, document,
    inject(config = {}, code = 'globalThis.apis.push(api);') {
      vm.runInContext(source + '(' + JSON.stringify({ ...defaults, ...config }) + ', function(api){' + code + '})', context);
    },
    domReady() { document.readyState = 'interactive'; documentEvents.DOMContentLoaded?.(); },
    timersRun() { const jobs = [...timers.values()]; timers.clear(); jobs.forEach(fn => fn()); },
    reply(index = 0, ok = true, override = {}) {
      const sent = posts[index];
      // Mirrors the native reply, which carries no token: the bridge object is visible to the
      // page's own scripts, so a reply echoing it would hand them a storage write credential.
      const data = JSON.stringify({ id: sent.id, requestId: sent.requestId, ok, ...override });
      listeners.forEach(listener => listener({ data }));
    },
  };
}

test('document-start executes before page DOM readiness', () => {
  const f = fixture(); f.inject();
  assert.equal(f.context.apis.length, 1);
  assert.equal(f.document.readyState, 'loading');
});
test('document-end waits for DOMContentLoaded and does not repeat at page finish', () => {
  const f = fixture(); f.inject({ runAt: 'document-end' });
  assert.equal(f.context.apis.length, 0);
  f.domReady(); f.inject({ runAt: 'document-end' });
  assert.equal(f.context.apis.length, 1);
});
test('document-idle schedules after DOM readiness', () => {
  const f = fixture(); f.inject({ runAt: 'document-idle' });
  f.domReady(); assert.equal(f.context.apis.length, 0);
  f.timersRun(); assert.equal(f.context.apis.length, 1);
});
test('host boundaries and exclusion matches reject unrelated pages', () => {
  for (const url of ['https://notexample.com/page', 'https://example.com.evil.test/page', 'file:///page']) {
    const f = fixture({ url }); f.inject(); assert.equal(f.context.apis.length, 0);
  }
  const f = fixture(); f.inject({ excludeMatches: ['https://www.example.com/*'] });
  assert.equal(f.context.apis.length, 0);
});
test('include and exclude globs work without interpreting regex punctuation', () => {
  const f = fixture({ url: 'https://another.test/a[1].js?q=1' });
  f.inject({ matches: [], includes: ['https://another.test/a[1].js*'] });
  assert.equal(f.context.apis.length, 1);
  const excluded = fixture(); excluded.inject({ excludes: ['*page*'] });
  assert.equal(excluded.context.apis.length, 0);
});
test('noframes suppresses matching subframes while normal scripts can run', () => {
  const f = fixture({ frame: true }); f.inject({ noframes: true });
  assert.equal(f.context.apis.length, 0);
  f.inject({ id: 'b', noframes: false }); assert.equal(f.context.apis.length, 1);
});
test('storage scripts cannot run through the late fallback without a bridge', () => {
  const f = fixture({ bridge: false });
  f.inject({ grants: ['GM_getValue'], needsStorage: true });
  assert.equal(f.context.apis.length, 0);
});
test('grant none never exposes storage, tab APIs or unsafeWindow', () => {
  const f = fixture(); f.inject();
  const api = f.context.apis[0];
  assert.equal(api.GM_getValue, undefined);
  assert.equal(api.GM_openInTab, undefined);
  assert.equal(api.unsafeWindow, undefined);
  assert.equal(api.GM_info.scriptHandler, 'PureBrowser');
});
test('modern storage API resolves only on the matching native acknowledgement', async () => {
  const f = fixture();
  f.inject({ grants: ['GM.getValue', 'GM.setValue'], needsStorage: true, values: '{"count":2}' });
  const api = f.context.apis[0];
  assert.equal(await api.GM.getValue('count', 0), 2);
  let done = false;
  const promise = api.GM.setValue('count', 3).then(() => { done = true; });
  // The request authenticates itself with the token; the reply must never carry it back.
  assert.equal(f.posts[0].token, 'private-token');
  assert.equal(f.posts[0].url, 'https://www.example.com/page');
  f.reply(0, true, { id: 'another-script' }); await Promise.resolve(); assert.equal(done, false);
  f.reply(0, true, { requestId: 9_999 }); await Promise.resolve(); assert.equal(done, false);
  f.reply(); await promise; assert.equal(done, true);
  assert.equal(api.GM_getValue('count'), 3);
});
test('a page-installed toJSON hook never sees the storage credential or a stored value', async () => {
  const f = fixture();
  f.inject({ grants: ['GM.setValue', 'GM.getValue'], needsStorage: true, values: '{"config":{"text":"keep"}}' });
  const api = f.context.apis[0];
  // The page shares this world and may install a hook on Object.prototype at any time. Both the
  // request (which carries the credential) and the values this runtime reads, writes and sends are
  // script-private, so neither may reach that hook — a hook handed a value could read or replace it.
  vm.runInContext(
    'globalThis.__seen = []; Object.prototype.toJSON = function () { globalThis.__seen.push(this); return "x"; };',
    f.context,
  );
  // Synchronous: send serialises and posts before returning. An object value, not a number, so the
  // value itself has to travel through the hook-free copy.
  api.GM.setValue('count', { nested: [1, 2] }).catch(() => {});
  const config = await api.GM.getValue('config', null);
  assert.equal(f.context.__seen.length, 0, 'the page hook must not run at all');
  // The script still gets an ordinary object back, with the stored content intact. Asserted by
  // field, not by stringifying: the hook above is still installed in that realm, so even this
  // test's own comparison would otherwise be answered by the page's function.
  assert.equal(config.text, 'keep');
  assert.equal(f.posts[0].value && f.posts[0].value.nested.length, 2);
});

test('rejected writes restore the previous local value', async () => {
  const f = fixture();
  f.inject({ grants: ['GM_getValue', 'GM_setValue'], needsStorage: true, values: '{"count":2}' });
  const api = f.context.apis[0], pending = api.GM.setValue('count', 9);
  f.reply(0, false);
  await assert.rejects(pending, /rejected/);
  assert.equal(api.GM_getValue('count'), 2);
});
test('prototype keys are isolated values and objects are copied on read', async () => {
  const f = fixture();
  f.inject({ grants: ['GM_getValue', 'GM_setValue'], needsStorage: true });
  const api = f.context.apis[0], pending = api.GM.setValue('__proto__', { answer: 42 });
  f.reply(); await pending;
  const read = api.GM_getValue('__proto__'); read.answer = 0;
  assert.equal(api.GM_getValue('__proto__').answer, 42);
  assert.equal(api.GM_getValue('answer', 'none'), 'none');
});
test('delete is acknowledged and missing values return defaults', async () => {
  const f = fixture();
  f.inject({ grants: ['GM_getValue', 'GM_deleteValue', 'GM_listValues'], needsStorage: true, values: '{"x":1}' });
  const api = f.context.apis[0], pending = api.GM.deleteValue('x');
  f.reply(); await pending;
  assert.equal(api.GM_getValue('x', 9), 9);
  assert.equal(api.GM_listValues().length, 0);
});
test('addStyle waits for a document root and inserts text without executing it', () => {
  const f = fixture(); f.document.head = null; f.document.documentElement = null;
  f.inject({ grants: ['GM_addStyle'] });
  const style = f.context.apis[0].GM_addStyle('.ad { display: none }');
  assert.equal(f.styles.length, 0);
  f.document.head = { appendChild(s) { f.styles.push(s); } };
  f.observers[0].callback();
  assert.equal(f.styles[0], style);
  assert.equal(style.textContent, '.ad { display: none }');
});
test('openInTab permits web URLs and rejects app and script protocols', () => {
  const f = fixture(); f.inject({ grants: ['GM_openInTab'] });
  const api = f.context.apis[0];
  api.GM_openInTab('/other');
  assert.equal(f.context.opened[0], 'https://www.example.com/other');
  assert.throws(() => api.GM_openInTab('javascript:alert(1)'), /HTTP/);
  assert.throws(() => api.GM_openInTab('intent://outside'), /HTTP/);
});
test('one broken script does not prevent the next installed script from running', () => {
  const f = fixture(); f.inject({}, 'throw new Error("fixture failure")');
  f.inject({ id: 'second' });
  assert.equal(f.context.warnings.length, 1);
  assert.equal(f.context.apis.length, 1);
});

test('resource APIs are grant-scoped and cannot name undeclared resources', async () => {
  const f=fixture();f.inject({ grants:['GM_getResourceText','GM.getResourceUrl'],resources:{theme:{text:'body{color:red}',url:'data:text/css;base64,Ym9keQ=='}} });
  const api=f.context.apis[0];assert.equal(api.GM_getResourceText('theme'),'body{color:red}');
  assert.equal(await api.GM.getResourceUrl('theme'),'data:text/css;base64,Ym9keQ==');
  assert.throws(()=>api.GM_getResourceText('__proto__'),/Unknown userscript resource/);
  assert.throws(()=>api.GM_getResourceURL('missing'),/Unknown userscript resource/);
  const other=fixture();other.inject({resources:{theme:{text:'secret',url:'data:'}}});
  assert.equal(other.context.apis[0].GM_getResourceText,undefined);
});
