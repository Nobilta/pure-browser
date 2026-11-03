(function (config) {
  'use strict';
  // Runs at document-start. Everything the page could redefine later is captured here.
  if (!/^https?:$/.test(location.protocol)) return;
  var W = window, D = document;
  var stringify = JSON.stringify.bind(JSON);
  var stringifyJson = stringify;
  var now = function () { return (W.performance && performance.now) ? performance.now() : Date.now(); };
  var isTop = false;
  try { isTop = W.top === W; } catch (e) { isTop = false; }
  var MESSAGE_LIMIT = 60000;
  var MAX_BYTES = 256 * 1024;
  var MAX_CHARS = 256 * 1024;
  var CHUNK_CHARS = 6000;
  var READ_TIMEOUT_MS = 8000;
  var MAX_HEADERS = 32;
  var MAX_HEADER_VALUE = 1024;
  var token = config.token;
  var doc = 'd' + Math.random().toString(36).slice(2) + now().toString(36);
  var seq = 0;
  var stopped = false;
  var bridge = null;
  var queue = [];
  var queueChars = 0;
  var reads = Object.create(null);
  var xhrRecords = new WeakMap();
  var flushTimer = null;

  function bridgeObject() {
    try {
      var b = W[config.bridge];
      return (b && typeof b.postMessage === 'function') ? b : null;
    } catch (e) { return null; }
  }
  function flushQueue() {
    if (stopped) { queue = []; queueChars = 0; return; }
    var b = bridge || bridgeObject();
    if (!b) return;
    bridge = b;
    while (queue.length) {
      var raw = queue.shift();
      queueChars -= raw.length;
      try { b.postMessage(raw); } catch (e) { /* bridge refused: drop this diagnostic message */ }
    }
    if (flushTimer) { clearInterval(flushTimer); flushTimer = null; }
  }
  function cancelQuietly(reader) {
    try {
      var pending = reader.cancel();
      if (pending && typeof pending.catch === 'function') pending.catch(function () { });
    } catch (e) { }
  }
  function send(message) {
    if (stopped) return;
    message.token = token;
    message.doc = doc;
    var raw;
    try { raw = stringify(message); } catch (e) { return; }
    if (typeof raw !== 'string' || raw.length > MESSAGE_LIMIT) return;
    var b = bridge || bridgeObject();
    if (!b) {
      // The bridge is injected before this script in normal operation. Queue a bounded
      // amount anyway so a provider that attaches it one frame later still gets the start.
      if (queue.length < 256) {
        queue.push(raw);
        queueChars += raw.length;
        while (queueChars > 1024 * 1024 && queue.length > 1) queueChars -= queue.shift().length;
      }
      return;
    }
    try { b.postMessage(raw); } catch (e) { /* a lost diagnostic message must not break the page */ }
  }
  bridge = bridgeObject();
  if (!bridge) flushTimer = setInterval(flushQueue, 50);

  function absolute(url) {
    try { return new URL(String(url), location.href).href; } catch (e) { return String(url); }
  }
  function headerValue(headers, name) {
    try {
      if (!headers) return '';
      if (typeof headers.get === 'function') return headers.get(name) || '';
    } catch (e) { }
    return '';
  }
  function headerList(headers) {
    var out = [];
    try {
      if (!headers || typeof headers.forEach !== 'function') return out;
      headers.forEach(function (value, key) {
        if (out.length >= MAX_HEADERS) return;
        out.push([String(key).slice(0, 256), String(value).slice(0, MAX_HEADER_VALUE)]);
      });
    } catch (e) { }
    return out;
  }
  function parseRawHeaders(raw) {
    var out = [];
    if (!raw) return out;
    String(raw).split(/\r?\n/).forEach(function (line) {
      if (!line || out.length >= MAX_HEADERS) return;
      var at = line.indexOf(':');
      if (at <= 0) return;
      out.push([line.slice(0, at).trim().slice(0, 256), line.slice(at + 1).trim().slice(0, MAX_HEADER_VALUE)]);
    });
    return out;
  }
  function contentTypeOf(headers) {
    return headerValue(headers, 'content-type').split(';')[0].trim().toLowerCase();
  }
  function isTextual(ct) {
    if (!ct) return false;
    if (ct === 'text/event-stream') return false;
    if (ct.indexOf('text/') === 0) return true;
    if (ct === 'application/json' || /\+json$/.test(ct)) return true;
    if (ct === 'application/xml' || /\+xml$/.test(ct)) return true;
    if (ct === 'application/xhtml+xml' || ct === 'application/javascript' ||
        ct === 'application/x-javascript' || ct === 'application/ecmascript' ||
        ct === 'application/x-www-form-urlencoded' || ct === 'application/graphql') return true;
    return false;
  }
  function encodedBytes(text) {
    if (typeof TextEncoder === 'function') {
      try { return new TextEncoder().encode(text).length; } catch (e) { }
    }
    return text.length;
  }
  function clampText(value) {
    var text = String(value == null ? '' : value);
    var truncated = false;
    if (text.length > MAX_CHARS) { text = text.slice(0, MAX_CHARS); truncated = true; }
    var bytes = encodedBytes(text);
    if (bytes > MAX_BYTES) {
      var cut = Math.floor(text.length * MAX_BYTES / bytes);
      text = text.slice(0, Math.max(0, cut - 1));
      bytes = encodedBytes(text);
      truncated = true;
    }
    return { text: text, bytes: bytes, truncated: truncated };
  }

  function beginBody(id, phase, state, meta) {
    send({ type: 'body-meta', id: id, phase: phase, state: state,
      bytes: meta && meta.bytes || 0, chars: meta && meta.chars || 0,
      contentType: meta && meta.contentType || '', reason: meta && meta.reason || '',
      binary: !!(meta && meta.binary) });
  }
  function bodyText(id, phase, text) {
    for (var i = 0; i < text.length; i += CHUNK_CHARS) {
      send({ type: 'body-chunk', id: id, phase: phase, text: text.slice(i, i + CHUNK_CHARS) });
    }
  }
  function endBody(id, phase, state, bytes, chars, reason) {
    send({ type: 'body-end', id: id, phase: phase, state: state, bytes: bytes || 0,
      chars: chars || 0, reason: reason || '' });
  }
  function emitBody(id, phase, body) {
    if (!body) { beginBody(id, phase, 'NOT_COLLECTED', { reason: 'not_attempted' });
      endBody(id, phase, 'NOT_COLLECTED', 0, 0, 'not_attempted'); return; }
    var state = body.state || 'COLLECTED';
    var meta = { bytes: body.bytes || 0, chars: (body.text || '').length,
      contentType: body.contentType || '', reason: body.reason || '', binary: !!body.binary };
    beginBody(id, phase, state, meta);
    if (body.text) bodyText(id, phase, body.text);
    endBody(id, phase, state, body.bytes || 0, (body.text || '').length, body.reason || '');
  }

  function bodyFromValue(value) {
    if (value == null) return { state: 'EMPTY', reason: 'no_body', bytes: 0 };
    try {
      if (typeof value === 'string') {
        var clamped = clampText(value);
        if (!clamped.text.length) return { state: 'EMPTY', reason: 'no_body', bytes: 0 };
        return { state: clamped.truncated ? 'TRUNCATED' : 'COLLECTED', text: clamped.text,
          bytes: clamped.bytes, reason: clamped.truncated ? 'size_limit' : '' };
      }
      if (typeof URLSearchParams !== 'undefined' && value instanceof URLSearchParams) {
        return bodyFromValue(String(value));
      }
      if (typeof FormData !== 'undefined' && value instanceof FormData) {
        var parts = [], omitted = false;
        try {
          value.forEach(function (v, k) {
            if (typeof v === 'string') parts.push(String(k) + '=' + v);
            else if (v && typeof v === 'object' && v.name != null) {
              parts.push(String(k) + '=[File ' + String(v.name) + ' ' +
                String(v.type || '') + ' ' + String(v.size || 0) + ' bytes]');
            } else { omitted = true; }
          });
        } catch (e) { omitted = true; }
        var joined = clampText(parts.join('\n'));
        return { state: joined.truncated || omitted ? 'TRUNCATED' : 'COLLECTED', text: joined.text,
          bytes: joined.bytes, contentType: 'multipart/form-data',
          reason: omitted ? 'request_file' : (joined.truncated ? 'size_limit' : '') };
      }
      if (typeof Blob !== 'undefined' && value instanceof Blob) {
        return { state: 'UNAVAILABLE', binary: true, bytes: value.size || 0,
          contentType: value.type || '', reason: 'request_file' };
      }
      if (typeof ArrayBuffer !== 'undefined' &&
          (value instanceof ArrayBuffer || ArrayBuffer.isView(value))) {
        var size = value.byteLength || (value.buffer && value.buffer.byteLength) || 0;
        return { state: 'UNAVAILABLE', binary: true, bytes: size, reason: 'binary' };
      }
      if (typeof ReadableStream !== 'undefined' && value instanceof ReadableStream) {
        return { state: 'UNAVAILABLE', reason: 'request_stream' };
      }
      if (typeof Document !== 'undefined' && value instanceof Document) {
        try { return bodyFromValue(new XMLSerializer().serializeToString(value)); } catch (e) { }
      }
      return { state: 'UNAVAILABLE', reason: 'request_stream' };
    } catch (e) {
      return { state: 'FAILED', reason: 'read_error', bytes: 0 };
    }
  }

  // ---- Fetch ---------------------------------------------------------------
  var originalFetch = null;
  var wrappedFetch = null;
  if (typeof W.fetch === 'function') {
    originalFetch = W.fetch;
    wrappedFetch = function () {
      if (stopped) return originalFetch.apply(this, arguments);
      var input = arguments[0], init = arguments[1];
      var id = 'f' + (++seq).toString(36) + now().toString(36);
      var method = String((init && init.method) || (input && input.method) ||
        (typeof input === 'string' ? 'GET' : 'GET')).toUpperCase();
      var url;
      try { url = absolute(typeof input === 'string' ? input : (input && input.url) || ''); }
      catch (e) { url = String(input); }
      var headers = [];
      try {
        headers = headerList(new Headers((init && init.headers) || (input && input.headers) || undefined));
      } catch (e) { headers = []; }
      var requestBody = null;
      if (init && Object.prototype.hasOwnProperty.call(init, 'body') && init.body != null) {
        requestBody = bodyFromValue(init.body);
      } else if (typeof Request !== 'undefined' && input instanceof Request) {
        requestBody = { state: 'NOT_COLLECTED', reason: 'request_stream' };
      }
      var started = now();
      send({ type: 'net-start', id: id, kind: 'fetch', method: method, url: url, startMs: Date.now() });
      send({ type: 'net-request', id: id, headers: headers });
      if (requestBody) emitBody(id, 'request', requestBody);
      else emitBody(id, 'request', { state: 'EMPTY', reason: 'no_body', bytes: 0 });
      var promise;
      try { promise = originalFetch.apply(this, arguments); }
      catch (error) {
        send({ type: 'net-end', id: id, outcome: 'failed', durationMs: Math.max(0, now() - started),
          reason: 'network', error: String((error && error.message) || error).slice(0, 512) });
        throw error;
      }
      return promise.then(function (response) {
        if (!stopped) {
          try { observeResponse(id, response, started, method); }
          catch (e) {
            send({ type: 'net-end', id: id, outcome: 'failed', durationMs: Math.max(0, now() - started),
              reason: 'read_error', error: String((e && e.message) || e).slice(0, 512) });
          }
        }
        return response;
      }, function (error) {
        if (!stopped) {
          var aborted = !!(error && (error.name === 'AbortError' || error.code === 20));
          send({ type: 'net-end', id: id, outcome: aborted ? 'cancelled' : 'failed',
            durationMs: Math.max(0, now() - started), reason: aborted ? 'aborted' : 'network',
            error: String((error && error.message) || error).slice(0, 512) });
        }
        throw error;
      });
    };
    try { W.fetch = wrappedFetch; } catch (e) { wrappedFetch = null; }
  }

  function finishResponse(id, started, outcome, totalBytes, state, reason) {
    send({ type: 'net-end', id: id, outcome: outcome, durationMs: Math.max(0, now() - started),
      sizeBytes: totalBytes || 0, reason: reason || '' });
  }
  function observeResponse(id, response, started, method) {
    var headers = headerList(response.headers);
    var ct = contentTypeOf(response.headers);
    var length = parseInt(headerValue(response.headers, 'content-length'), 10);
    if (!isFinite(length) || length < 0) length = 0;
    send({ type: 'net-response', id: id, status: response.status || 0,
      statusText: String(response.statusText || '').slice(0, 256),
      responseUrl: absolute(response.url || ''), responseType: String(response.type || ''),
      headers: headers, contentType: ct, contentLength: length });
    if (response.type === 'opaque' || response.type === 'opaqueredirect') {
      emitBody(id, 'response', { state: 'UNAVAILABLE', reason: 'opaque', binary: true, bytes: length });
      finishResponse(id, started, 'completed', length, 'UNAVAILABLE', 'opaque');
      return;
    }
    if (method === 'HEAD' || response.status === 204 || response.status === 304 || !response.body) {
      var emptyState = (length > 0) ? 'NOT_COLLECTED' : 'EMPTY';
      emitBody(id, 'response', { state: emptyState, bytes: length, reason: length > 0 ? 'capability' : 'no_body' });
      finishResponse(id, started, 'completed', length, emptyState, '');
      return;
    }
    if (ct === 'text/event-stream') {
      emitBody(id, 'response', { state: 'UNAVAILABLE', reason: 'stream', bytes: length });
      finishResponse(id, started, 'completed', length, 'UNAVAILABLE', 'stream');
      return;
    }
    if (!isTextual(ct)) {
      emitBody(id, 'response', { state: 'UNAVAILABLE', reason: 'binary', binary: true,
        bytes: length, contentType: ct });
      finishResponse(id, started, 'completed', length, 'UNAVAILABLE', 'binary');
      return;
    }
    var clone;
    try { clone = response.clone(); }
    catch (e) {
      emitBody(id, 'response', { state: 'FAILED', reason: 'capability', bytes: length, contentType: ct });
      finishResponse(id, started, 'completed', length, 'FAILED', 'capability');
      return;
    }
    var reader;
    try { reader = clone.body.getReader(); }
    catch (e) {
      emitBody(id, 'response', { state: 'FAILED', reason: 'read_error', bytes: length, contentType: ct });
      finishResponse(id, started, 'completed', length, 'FAILED', 'read_error');
      return;
    }
    beginBody(id, 'response', 'COLLECTING', { bytes: 0, contentType: ct });
    var decoder = (typeof TextDecoder === 'function') ? new TextDecoder('utf-8') : null;
    var total = 0, sentChars = 0, settled = false, timer = null;
    function end(state, reason) {
      if (settled) return;
      settled = true;
      if (timer) { clearTimeout(timer); timer = null; }
      delete reads[id];
      cancelQuietly(reader);
      endBody(id, 'response', state, total, sentChars, reason);
      finishResponse(id, started, 'completed', total, state, reason);
    }
    reads[id] = function () { end('TRUNCATED', 'aborted'); };
    try {
      timer = setTimeout(function () { end('TRUNCATED', 'time_limit'); }, READ_TIMEOUT_MS);
    } catch (e) { }
    function pump() {
      reader.read().then(function (result) {
        if (settled) return;
        if (result.done) {
          if (decoder) {
            try { append(decoder.decode()); } catch (e) { }
          }
          end(sentChars > 0 || total > 0 ? 'COLLECTED' : 'EMPTY', '');
          return;
        }
        var value = result.value;
        total += (value && value.byteLength) || 0;
        var text = '';
        try {
          text = decoder ? decoder.decode(value, { stream: true }) : String.fromCharCode.apply(null, value);
        } catch (e) { text = ''; }
        if (!append(text)) return;
        if (total >= MAX_BYTES || sentChars >= MAX_CHARS) {
          end('TRUNCATED', 'size_limit');
          return;
        }
        pump();
      }).then(null, function () { end('FAILED', 'read_error'); });
    }
    function sendResponseChunks(text) {
      // A reader result can be tens of KiB; the bridge's per-message budget counts JSON
      // encoding, so split it before posting or the native side rejects the whole message.
      for (var start = 0; start < text.length; start += CHUNK_CHARS) {
        send({ type: 'body-chunk', id: id, phase: 'response',
          text: text.slice(start, start + CHUNK_CHARS) });
      }
    }
    function append(text) {
      if (!text) return true;
      if (sentChars >= MAX_CHARS) { end('TRUNCATED', 'size_limit'); return false; }
      if (sentChars + text.length > MAX_CHARS) {
        text = text.slice(0, MAX_CHARS - sentChars);
        sentChars += text.length;
        sendResponseChunks(text);
        end('TRUNCATED', 'size_limit');
        return false;
      }
      sentChars += text.length;
      sendResponseChunks(text);
      return true;
    }
    pump();
  }

  // ---- XMLHttpRequest ------------------------------------------------------
  var XHR = W.XMLHttpRequest;
  var originalOpen = XHR && XHR.prototype.open;
  var originalSend = XHR && XHR.prototype.send;
  var originalSetHeader = XHR && XHR.prototype.setRequestHeader;
  var originalAbort = XHR && XHR.prototype.abort;
  var wrappedOpen = null, wrappedSend = null, wrappedSetHeader = null, wrappedAbort = null;
  if (XHR && typeof originalOpen === 'function' && typeof originalSend === 'function') {
    wrappedOpen = function (method, url) {
      try {
        xhrRecords.set(this, {
          method: String(method || 'GET').toUpperCase(), url: absolute(url),
          headers: [], aborted: false, started: 0, id: '',
        });
      } catch (e) { }
      return originalOpen.apply(this, arguments);
    };
    wrappedSetHeader = function (name, value) {
      try {
        var record = xhrRecords.get(this);
        if (record && record.headers.length < MAX_HEADERS) {
          record.headers.push([String(name).slice(0, 256), String(value).slice(0, MAX_HEADER_VALUE)]);
        }
      } catch (e) { }
      return originalSetHeader.apply(this, arguments);
    };
    wrappedAbort = function () {
      try {
        var record = xhrRecords.get(this);
        if (record) record.aborted = true;
      } catch (e) { }
      return originalAbort.apply(this, arguments);
    };
    wrappedSend = function (body) {
      if (stopped) return originalSend.apply(this, arguments);
      var record;
      try { record = xhrRecords.get(this); } catch (e) { record = null; }
      if (!record) {
        record = { method: 'GET', url: absolute(''), headers: [], aborted: false, started: 0, id: '' };
      }
      var started = now();
      var id = 'x' + (++seq).toString(36) + started.toString(36);
      record.id = id;
      record.started = started;
      send({ type: 'net-start', id: id, kind: 'xhr', method: record.method, url: record.url, startMs: Date.now() });
      send({ type: 'net-request', id: id, headers: record.headers });
      emitBody(id, 'request', bodyFromValue(body));
      var xhr = this;
      var onLoadEnd = function () {
        try { xhr.removeEventListener('loadend', onLoadEnd); } catch (e) { }
        if (stopped) return;
        try { finishXhr(id, xhr, record, started); } catch (e) {
          send({ type: 'net-end', id: id, outcome: 'failed', durationMs: Math.max(0, now() - started),
            reason: 'read_error', error: String((e && e.message) || e).slice(0, 512) });
        }
      };
      try { this.addEventListener('loadend', onLoadEnd); } catch (e) { }
      try { return originalSend.apply(this, arguments); }
      catch (error) {
        send({ type: 'net-end', id: id, outcome: 'failed', durationMs: Math.max(0, now() - started),
          reason: 'network', error: String((error && error.message) || error).slice(0, 512) });
        throw error;
      }
    };
    try {
      XHR.prototype.open = wrappedOpen;
      XHR.prototype.setRequestHeader = wrappedSetHeader;
      XHR.prototype.abort = wrappedAbort;
      XHR.prototype.send = wrappedSend;
    } catch (e) { }
  }

  function finishXhr(id, xhr, record, started) {
    var status = 0, statusText = '', responseUrl = '';
    try { status = xhr.status || 0; } catch (e) { }
    try { statusText = String(xhr.statusText || ''); } catch (e) { }
    try { responseUrl = absolute(xhr.responseURL || record.url); } catch (e) { }
    var headers = [];
    try { headers = parseRawHeaders(xhr.getAllResponseHeaders()); } catch (e) { }
    var ct = '';
    try {
      headers.forEach(function (pair) { if (!ct && pair[0].toLowerCase() === 'content-type') ct = pair[1].split(';')[0].trim().toLowerCase(); });
    } catch (e) { }
    var length = 0;
    try {
      headers.forEach(function (pair) {
        if (pair[0].toLowerCase() === 'content-length') {
          var parsed = parseInt(pair[1], 10);
          if (isFinite(parsed) && parsed > 0) length = parsed;
        }
      });
    } catch (e) { }
    send({ type: 'net-response', id: id, status: status, statusText: statusText.slice(0, 256),
      responseUrl: responseUrl, responseType: String(xhr.responseType || ''), headers: headers,
      contentType: ct, contentLength: length });
    var duration = Math.max(0, now() - started);
    if (status === 0) {
      var cancelled = record.aborted;
      emitBody(id, 'response', { state: cancelled ? 'NOT_COLLECTED' : 'FAILED',
        reason: cancelled ? 'aborted' : 'network' });
      send({ type: 'net-end', id: id, outcome: cancelled ? 'cancelled' : 'failed',
        durationMs: duration, reason: cancelled ? 'aborted' : 'network' });
      return;
    }
    var rt = String(xhr.responseType || '');
    var body = null, bytes = length;
    try {
      if (rt === '' || rt === 'text') {
        var text = xhr.responseText;
        if (typeof text === 'string') body = bodyFromValue(text);
        else body = { state: 'UNAVAILABLE', reason: 'capability' };
      } else if (rt === 'json') {
        body = bodyFromValue(stringifyJson(xhr.response));
      } else if (rt === 'document') {
        var serialized = '';
        try { serialized = new XMLSerializer().serializeToString(xhr.response); } catch (e) { }
        body = serialized ? bodyFromValue(serialized) : { state: 'UNAVAILABLE', reason: 'capability' };
      } else if (rt === 'arraybuffer') {
        var buffer = xhr.response;
        bytes = (buffer && buffer.byteLength) || 0;
        body = { state: 'UNAVAILABLE', binary: true, bytes: bytes, reason: 'binary' };
      } else if (rt === 'blob') {
        var blob = xhr.response;
        bytes = (blob && blob.size) || 0;
        body = { state: 'UNAVAILABLE', binary: true, bytes: bytes,
          contentType: (blob && blob.type) || '', reason: 'binary' };
      } else {
        body = { state: 'UNAVAILABLE', reason: 'capability' };
      }
    } catch (e) {
      body = { state: 'FAILED', reason: 'read_error' };
    }
    emitBody(id, 'response', body || { state: 'EMPTY', reason: 'no_body' });
    send({ type: 'net-end', id: id, outcome: 'completed', durationMs: duration,
      sizeBytes: body && body.bytes || bytes, reason: (body && body.reason) || '' });
  }

  // ---- DOM / source accessor (main frame only) -----------------------------
  var api = null;
  if (isTop) {
    var sourceCache = null;
    var nodeIds = new WeakMap();
    var nodes = new Map();
    var nextNode = 1;
    var MAX_NODES = 20000;
    var MAX_ATTR_VALUE = 4096;
    var MAX_TEXT_PREVIEW = 2048;
    var MAX_HTML_CHARS = 65536;
    var MAX_SEARCH_VISITED = 20000;
    var MAX_SEARCH_RESULTS = 50;

    function resetDom() {
      nodeIds = new WeakMap();
      nodes = new Map();
      nextNode = 1;
      sourceCache = null;
    }
    function nodeId(node) {
      var id = nodeIds.get(node);
      if (id) return id;
      id = 'n' + (nextNode++);
      nodeIds.set(node, id);
      nodes.set(id, node);
      while (nodes.size > MAX_NODES) {
        var first = nodes.keys().next();
        if (first.done || first.value === id) break;
        var old = nodes.get(first.value);
        nodes.delete(first.value);
        if (old && old.nodeType === 1) nodeIds.delete(old);
      }
      return id;
    }
    function nodeOf(id) {
      var node = nodes.get(id);
      if (!node || !node.isConnected) return null;
      return node;
    }
    function kindOf(node) {
      if (node.nodeType === 1) return 'element';
      if (node.nodeType === 3) return 'text';
      if (node.nodeType === 8) return 'comment';
      if (node.nodeType === 10) return 'doctype';
      return 'other';
    }
    function previewOf(node) {
      var text = node.nodeValue == null ? '' : String(node.nodeValue);
      if (text.length > MAX_TEXT_PREVIEW) return { text: text.slice(0, MAX_TEXT_PREVIEW), truncated: true };
      return { text: text, truncated: false };
    }
    function describe(node) {
      var kind = kindOf(node);
      var out = { id: nodeId(node), kind: kind, name: '', childCount: 0, attributes: [] };
      if (kind === 'element') {
        out.name = String(node.tagName || '').toLowerCase();
        out.childCount = node.childNodes.length;
        var attrs = node.attributes || [];
        for (var i = 0; i < attrs.length && i < MAX_HEADERS; i++) {
          out.attributes.push({ name: String(attrs[i].name).slice(0, 256),
            value: String(attrs[i].value).slice(0, MAX_ATTR_VALUE) });
        }
      } else if (kind === 'text' || kind === 'comment') {
        var preview = previewOf(node);
        out.text = preview.text;
        out.textTruncated = preview.truncated;
        out.textLength = node.nodeValue == null ? 0 : String(node.nodeValue).length;
      } else if (kind === 'doctype') {
        out.name = String(node.name || 'html').toLowerCase();
      }
      return out;
    }
    function pathOf(node) {
      var path = [];
      var current = node;
      while (current && current !== D) {
        var parent = current.parentNode;
        if (!parent) break;
        path.unshift({ id: nodeId(current), index: Array.prototype.indexOf.call(parent.childNodes, current) });
        current = parent;
      }
      return path;
    }
    function lineAt(starts, offset) {
      var low = 0, high = starts.length - 1;
      while (low <= high) {
        var mid = (low + high) >> 1;
        if (starts[mid] <= offset) low = mid + 1; else high = mid - 1;
      }
      return Math.max(0, low - 1);
    }
    function ensureSource() {
      if (sourceCache && sourceCache.doc === D) return sourceCache;
      var text = D.documentElement ? String(D.documentElement.outerHTML || '') : '';
      var starts = [0];
      for (var i = 0; i < text.length; i++) {
        if (text.charCodeAt(i) === 10) starts.push(i + 1);
      }
      sourceCache = { doc: D, text: text, starts: starts };
      return sourceCache;
    }
    api = {
      __pureDevTools: true,
      doc: doc,
      reset: resetDom,
      sourceInfo: function () {
        var state = ensureSource();
        return stringify({ totalChars: state.text.length, totalLines: state.starts.length,
          url: location.href, title: D.title || '' });
      },
      sourcePage: function (offset, maxChars, maxLines) {
        var state = ensureSource();
        var text = state.text;
        var total = text.length;
        offset = Math.max(0, Math.min(total, Math.floor(Number(offset) || 0)));
        maxChars = Math.max(256, Math.min(262144, Math.floor(Number(maxChars) || 65536)));
        maxLines = Math.max(1, Math.min(5000, Math.floor(Number(maxLines) || 1000)));
        var startLine = lineAt(state.starts, offset);
        var lineLimit = Math.min(state.starts.length, startLine + maxLines);
        var end = lineLimit < state.starts.length ? state.starts[lineLimit] : total;
        if (end > offset + maxChars) end = offset + maxChars;
        if (end < total) {
          var newline = text.lastIndexOf('\n', Math.max(offset, end - 1));
          if (newline >= offset + Math.floor(maxChars * 0.4)) end = newline + 1;
        }
        if (end > offset + 1 && end < total) {
          var lastCode = text.charCodeAt(end - 1);
          var nextCode = text.charCodeAt(end);
          if (lastCode >= 0xD800 && lastCode <= 0xDBFF && nextCode >= 0xDC00 && nextCode <= 0xDFFF) end--;
        }
        var lastLine = lineAt(state.starts, Math.max(offset, end - 1));
        var page = text.slice(offset, end);
        return stringify({ offset: offset, end: end, text: page,
          startLine: startLine, endLine: lastLine + 1,
          totalChars: total, totalLines: state.starts.length,
          next: end < total ? end : null,
          continued: offset > state.starts[startLine] });
      },
      domRoot: function () {
        if (!D.documentElement) return stringify({ empty: true });
        return stringify({ root: describe(D.documentElement) });
      },
      domChildren: function (id, start, count) {
        var parent = nodeOf(String(id));
        if (!parent) return stringify({ invalid: true });
        start = Math.max(0, Math.floor(Number(start) || 0));
        count = Math.max(1, Math.min(100, Math.floor(Number(count) || 100)));
        var total = parent.childNodes.length;
        var out = [];
        for (var i = start; i < total && out.length < count; i++) {
          out.push(describe(parent.childNodes[i]));
        }
        return stringify({ nodes: out, start: start, total: total });
      },
      domHtml: function (id) {
        var node = nodeOf(String(id));
        if (!node) return stringify({ invalid: true });
        var html = '';
        try {
          if (node.nodeType === 1) html = String(node.outerHTML || '');
          else if (node.nodeType === 10) html = '<!DOCTYPE ' + String(node.name || 'html') + '>';
          else html = String(node.nodeValue == null ? '' : node.nodeValue);
        } catch (e) { return stringify({ invalid: true }); }
        var truncated = html.length > MAX_HTML_CHARS;
        return stringify({ html: truncated ? html.slice(0, MAX_HTML_CHARS) : html, truncated: truncated });
      },
      domPath: function (id) {
        var node = nodeOf(String(id));
        if (!node) return stringify({ invalid: true });
        return stringify({ path: pathOf(node) });
      },
      domSearch: function (query, limit) {
        query = String(query == null ? '' : query).trim().slice(0, 200);
        if (!query) return stringify({ results: [], truncated: false });
        var needle = query.toLowerCase();
        var byId = needle.charAt(0) === '#' ? needle.slice(1) : null;
        var byClass = needle.charAt(0) === '.' ? needle.slice(1) : null;
        var tagNeedle = byId == null && byClass == null ? needle : null;
        limit = Math.max(1, Math.min(MAX_SEARCH_RESULTS, Math.floor(Number(limit) || MAX_SEARCH_RESULTS)));
        var results = [], seen = new Set(), visited = 0, truncated = false;
        var stack = [D.documentElement];
        function add(node, match) {
          if (!node || node.nodeType !== 1) return;
          var id = nodeId(node);
          if (seen.has(id)) return;
          seen.add(id);
          if (results.length >= limit) { truncated = true; return; }
          results.push({ id: id, name: String(node.tagName || '').toLowerCase(),
            preview: String(node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 160),
            path: pathOf(node), match: match });
        }
        while (stack.length && results.length < limit) {
          var node = stack.pop();
          if (!node) continue;
          if (++visited > MAX_SEARCH_VISITED) { truncated = true; break; }
          if (node.nodeType === 1) {
            var tag = String(node.tagName || '').toLowerCase();
            var matched = false;
            if (byId != null) matched = String(node.id || '') === byId;
            else if (byClass != null) matched = node.classList && node.classList.contains(byClass);
            else matched = tag === tagNeedle || String(node.id || '') === tagNeedle ||
              (node.classList && node.classList.contains(tagNeedle));
            if (matched) add(node, 'tag');
            var children = node.childNodes;
            for (var i = children.length - 1; i >= 0; i--) stack.push(children[i]);
          } else if (node.nodeType === 3) {
            if (String(node.nodeValue || '').toLowerCase().indexOf(needle) >= 0) {
              add(node.parentNode, 'text');
            }
          }
        }
        return stringify({ results: results, truncated: truncated });
      },
      stop: function () { stop(); }
    };
    try {
      Object.defineProperty(W, config.ns, { value: api, configurable: true, enumerable: false });
    } catch (e) { try { W[config.ns] = api; } catch (e2) { } }
    send({ type: 'dom-ready' });
  }

  function announce() {
    send({ type: 'init', url: location.href, origin: location.origin,
      isMainFrame: isTop, title: D.title || '' });
  }
  announce();
  try {
    D.addEventListener('DOMContentLoaded', announce, { once: true });
    W.addEventListener('load', announce, { once: true });
  } catch (e) { }

  function stop() {
    if (stopped) return;
    stopped = true;
    if (flushTimer) { clearInterval(flushTimer); flushTimer = null; }
    queue = [];
    queueChars = 0;
    for (var id in reads) {
      try { reads[id](); } catch (e) { }
    }
    reads = Object.create(null);
    try {
      if (wrappedFetch && W.fetch === wrappedFetch) W.fetch = originalFetch;
    } catch (e) { }
    try {
      if (wrappedOpen && XHR.prototype.open === wrappedOpen) XHR.prototype.open = originalOpen;
      if (wrappedSend && XHR.prototype.send === wrappedSend) XHR.prototype.send = originalSend;
      if (wrappedSetHeader && XHR.prototype.setRequestHeader === wrappedSetHeader) XHR.prototype.setRequestHeader = originalSetHeader;
      if (wrappedAbort && XHR.prototype.abort === wrappedAbort) XHR.prototype.abort = originalAbort;
    } catch (e) { }
    // The DOM/source accessor stays usable for the document that already loaded it:
    // stopping capture must not hide content the user can still inspect.
    if (api) { try { api.__active = false; } catch (e) { } }
  }
})({
  bridge: '__BRIDGE__',
  token: '__TOKEN__',
  ns: '__NAMESPACE__'
});
