(function pureUserScript(config, run) {
  'use strict';
  if (!/^https?:$/.test(location.protocol)) return;
  if (config.noframes && window.top !== window) return;
  function glob(pattern, value) {
    var p = 0, v = 0, star = -1, mark = 0;
    while (v < value.length) {
      if (p < pattern.length && pattern[p] === value[v]) { p++; v++; }
      else if (p < pattern.length && pattern[p] === '*') { star = p++; mark = v; }
      else if (star >= 0) { p = star + 1; v = ++mark; }
      else return false;
    }
    while (p < pattern.length && pattern[p] === '*') p++;
    return p === pattern.length;
  }
  function match(pattern) {
    if (pattern === '<all_urls>') return true;
    var parts = /^(\*|https?):\/\/(\*|\*\.[a-zA-Z0-9.-]+|[a-zA-Z0-9.-]+|\[[0-9a-fA-F:]+\])(\/.*)$/.exec(pattern);
    if (!parts) return false;
    if (parts[1] !== '*' && parts[1] + ':' !== location.protocol) return false;
    var host = parts[2].toLowerCase(), actual = location.hostname.toLowerCase();
    if (host !== '*' && host !== actual &&
        !(host.startsWith('*.') && (actual === host.slice(2) || actual.endsWith(host.slice(1))))) return false;
    return glob(parts[3], location.pathname + location.search);
  }
  if (!(config.matches.some(match) || config.includes.some(function (p) { return glob(p, location.href); })) ||
      config.excludes.some(function (p) { return glob(p, location.href); }) || config.excludeMatches.some(match)) return;
  var marker = '__pureUserScriptDone_' + config.id;
  if (window[marker]) return;
  var bridge = config.bridge ? window[config.bridge] : null;
  if (config.needsStorage && !bridge) return;
  Object.defineProperty(window, marker, { value: true, configurable: false });

  // Capture the transport and JSON helpers before page scripts can replace their globals.
  var encode = JSON.stringify.bind(JSON), decode = JSON.parse.bind(JSON);
  var post = bridge ? bridge.postMessage.bind(bridge) : null;
  var own = Function.call.bind(Object.prototype.hasOwnProperty);
  var create = Object.create.bind(Object);
  var values = decode(config.values), pending = new Map(), serial = 0, revisions = Object.create(null);
  var warn = console.warn.bind(console), log = console.log.bind(console);
  var send = function (operation, key, value) {
    if (!post || pending.size >= 32) return Promise.reject(new Error('GM storage unavailable or busy'));
    return new Promise(function (resolve, reject) {
      var requestId = ++serial;
      var timer = setTimeout(function () {
        pending.delete(requestId);
        reject(new Error('GM storage timeout'));
      }, 15000);
      pending.set(requestId, { resolve: resolve, reject: reject, timer: timer });
      try {
        // The request is built without a prototype. `JSON.stringify` asks any object for
        // `toJSON` before serialising it, so a `toJSON` the page installs on
        // `Object.prototype` would be handed the whole request — credential included — even
        // though the reply no longer carries it. A null prototype has nothing to look up, so
        // the hook never runs and the token stays in this closure.
        var message = create(null);
        message.id = config.id;
        message.token = config.token;
        message.requestId = requestId;
        message.operation = operation;
        message.key = key;
        message.value = value;
        message.url = location.href;
        post(encode(message));
      } catch (error) {
        clearTimeout(timer); pending.delete(requestId); reject(error);
      }
    });
  };
  if (bridge) bridge.addEventListener('message', function (event) {
    var data;
    try { data = decode(event.data); } catch (_) { return; }
    if (data.id !== config.id) return;
    var task = pending.get(data.requestId);
    if (!task) return;
    pending.delete(data.requestId); clearTimeout(task.timer);
    if (data.ok) task.resolve(); else task.reject(new Error('GM storage write rejected'));
  });
  function allowed(name) {
    return config.grants.indexOf(name) >= 0 || config.grants.indexOf(name.replace('GM_', 'GM.')) >= 0 ||
      (name === 'GM_getResourceURL' && config.grants.indexOf('GM.getResourceUrl') >= 0);
  }
  // A copy with no prototype anywhere in its graph. `JSON.stringify` asks every object it walks
  // for `toJSON`, and the page shares this realm, so a hook it installed on `Object.prototype`
  // would otherwise be handed script-private storage values as `this` — the same reason the
  // request object itself is built without a prototype.
  function bare(value) {
    if (value === null || typeof value !== 'object') return value;
    if (Array.isArray(value)) {
      var list = [];
      for (var i = 0; i < value.length; i++) list.push(bare(value[i]));
      // An array cannot lose its prototype and stay an array, so it gets an own, non-enumerable
      // `toJSON` instead: `JSON.stringify` only calls the property when it is a function, and an
      // own one shadows whatever the page put on Array.prototype or Object.prototype.
      Object.defineProperty(list, 'toJSON', { value: undefined });
      return list;
    }
    var copy = create(null);
    for (var key in value) if (own(value, key)) copy[key] = bare(value[key]);
    return copy;
  }
  /** The form this runtime keeps and sends: serialised through a hook-free graph. */
  function stored(value) {
    return value === undefined ? undefined : bare(decode(encode(bare(value))));
  }
  /** The form the script receives: an ordinary JSON value, so its own methods keep working. */
  function expose(value) {
    return value === undefined ? undefined : decode(encode(bare(value)));
  }
  function getValue(key, fallback) {
    key = String(key);
    return own(values, key) ? expose(values[key]) : fallback;
  }
  function setValue(key, value) {
    key = String(key);
    if (key.length > 256 || value === undefined) return Promise.reject(new Error('Invalid GM value'));
    value = stored(value);
    var previous = own(values, key) ? stored(values[key]) : undefined;
    var revision = revisions[key] = (revisions[key] || 0) + 1;
    Object.defineProperty(values, key, { value: value, writable: true, enumerable: true, configurable: true });
    return send('set', key, value).catch(function (error) {
      if (revisions[key] === revision) {
        if (previous === undefined) delete values[key];
        else Object.defineProperty(values, key, { value: previous, writable: true, enumerable: true, configurable: true });
      }
      throw error;
    });
  }
  function deleteValue(key) {
    key = String(key);
    var previous = own(values, key) ? stored(values[key]) : undefined;
    var revision = revisions[key] = (revisions[key] || 0) + 1;
    delete values[key];
    return send('delete', key, null).catch(function (error) {
      if (revisions[key] === revision && previous !== undefined) {
        Object.defineProperty(values, key, { value: previous, writable: true, enumerable: true, configurable: true });
      }
      throw error;
    });
  }
  function addStyle(css) {
    var style = document.createElement('style');
    style.textContent = String(css);
    function attach() {
      var root = document.head || document.documentElement;
      if (!root) return false;
      root.appendChild(style);
      return true;
    }
    if (!attach()) {
      var observer = new MutationObserver(function () { if (attach()) observer.disconnect(); });
      observer.observe(document, { childList: true, subtree: true });
    }
    return style;
  }
  var open = window.open.bind(window);
  function openInTab(raw) {
    var url = new URL(raw, location.href);
    if (!/^https?:$/.test(url.protocol)) throw new Error('Only HTTP(S) tabs are supported');
    return open(url.href, '_blank');
  }
  var info = { scriptHandler: 'PureBrowser', version: config.browserVersion || '',
    script: { name: config.name, namespace: config.namespace, version: config.version,
      description: config.description, matches: config.matches, includes: config.includes,
      excludes: config.excludes, grants: config.grants, 'run-at': config.runAt },
    scriptMetaStr: config.meta };
  var api = { GM_info: info, GM: { info: info } };
  if (config.grants.indexOf('unsafeWindow') >= 0) api.unsafeWindow = window;
  if (allowed('GM_getValue')) { api.GM_getValue = getValue; api.GM.getValue = function (k, d) { return Promise.resolve(getValue(k, d)); }; }
  if (allowed('GM_listValues')) { api.GM_listValues = function () { return Object.keys(values); }; api.GM.listValues = function () { return Promise.resolve(Object.keys(values)); }; }
  if (allowed('GM_setValue')) { api.GM_setValue = function (k, v) { setValue(k, v).catch(warn); }; api.GM.setValue = setValue; }
  if (allowed('GM_deleteValue')) { api.GM_deleteValue = function (k) { deleteValue(k).catch(warn); }; api.GM.deleteValue = deleteValue; }
  if (allowed('GM_addStyle')) { api.GM_addStyle = addStyle; api.GM.addStyle = addStyle; }
  if (allowed('GM_log')) { api.GM_log = log; api.GM.log = log; }
  if (allowed('GM_openInTab')) { api.GM_openInTab = openInTab; api.GM.openInTab = openInTab; }
  function resource(name, field) {
    name = String(name);
    if (!config.resources || !own(config.resources, name)) throw new Error('Unknown userscript resource');
    return config.resources[name][field];
  }
  if (allowed('GM_getResourceText')) {
    api.GM_getResourceText = function(name) { return resource(name, 'text'); };
    api.GM.getResourceText = function(name) { return Promise.resolve(resource(name, 'text')); };
  }
  if (allowed('GM_getResourceURL')) {
    api.GM_getResourceURL = function(name) { return resource(name, 'url'); };
    api.GM.getResourceUrl = api.GM.getResourceURL = function(name) { return Promise.resolve(resource(name, 'url')); };
  }
  function execute() {
    try { run(api); } catch (error) { warn('PureBrowser userscript: ' + config.name, error); }
  }
  function ready() {
    if (config.runAt === 'document-idle') setTimeout(execute, 0); else execute();
  }
  if (config.runAt === 'document-start') execute();
  else if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, { once: true });
  else ready();
})
