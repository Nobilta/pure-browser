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
  function isURLObject(value) {
    try { return typeof URL !== 'undefined' && value instanceof URL; } catch (e) { return false; }
  }
  function isRequestObject(value) {
    try { return typeof Request !== 'undefined' && value instanceof Request; } catch (e) { return false; }
  }
  // Only own data properties are inspected. Calling a getter to "observe" a request could
  // consume a one-shot value before fetch does, changing the request itself.
  function propertyDescriptor(object, key) {
    try {
      if (!object) return null;
      return Object.getOwnPropertyDescriptor(object, key);
    } catch (e) { return null; }
  }
  function dataProperty(object, key) {
    var descriptor = propertyDescriptor(object, key);
    return descriptor && Object.prototype.hasOwnProperty.call(descriptor, 'value')
      ? descriptor.value : undefined;
  }
  function hasProperty(object, key) {
    try { return !!object && key in Object(object); } catch (e) { return false; }
  }
  function snapshotHeaders(value) {
    if (value == null) return [];
    try {
      if (typeof Headers !== 'undefined' && value instanceof Headers) return headerList(value);
      if (Array.isArray(value)) return headerList(new Headers(value));
      if (typeof value === 'object') {
        var out = [], keys = Object.keys(value);
        for (var i = 0; i < keys.length && out.length < MAX_HEADERS; i++) {
          var item = dataProperty(value, keys[i]);
          if (item !== undefined) {
            out.push([String(keys[i]).slice(0, 256), String(item).slice(0, MAX_HEADER_VALUE)]);
          }
        }
        return out;
      }
    } catch (e) { }
    return [];
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
    if (text.length > MAX_CHARS) {
      text = text.slice(0, MAX_CHARS);
      // Do not leave half a surrogate pair at the preview boundary.
      if (text.length && text.charCodeAt(text.length - 1) >= 0xD800 &&
          text.charCodeAt(text.length - 1) <= 0xDBFF) text = text.slice(0, -1);
      truncated = true;
    }
    var bytes = encodedBytes(text);
    if (bytes > MAX_BYTES) {
      truncated = true;
      if (typeof TextEncoder === 'function' && typeof TextDecoder === 'function') {
        var encoded = new TextEncoder().encode(text).subarray(0, MAX_BYTES);
        text = new TextDecoder('utf-8').decode(encoded);
        if (text.length && text.charCodeAt(text.length - 1) === 0xFFFD) text = text.slice(0, -1);
        bytes = encodedBytes(text);
      } else {
        var cut = Math.floor(text.length * MAX_BYTES / bytes);
        while (cut > 0 && encodedBytes(text.slice(0, cut)) > MAX_BYTES) cut--;
        text = text.slice(0, Math.max(0, cut));
        bytes = encodedBytes(text);
      }
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

  function fetchRequestInfo(input, init) {
    var info = { method: 'GET', methodKnown: true, url: '', headers: [], body: null };
    var requestInput = isRequestObject(input);
    if (requestInput) {
      info.method = String(input.method || 'GET').toUpperCase();
      info.url = absolute(input.url || '');
      info.headers = headerList(input.headers);
      info.body = { state: 'NOT_COLLECTED', reason: 'request_stream' };
    } else if (typeof input === 'string') {
      info.url = absolute(input);
    } else if (isURLObject(input)) {
      info.url = absolute(input.href);
    } else if (input && typeof input.url === 'string') {
      info.url = absolute(input.url);
    }
    var methodDescriptor = propertyDescriptor(init, 'method');
    if (methodDescriptor) {
      if (Object.prototype.hasOwnProperty.call(methodDescriptor, 'value')) {
        var rawMethod = methodDescriptor.value;
        if (typeof rawMethod === 'string') info.method = rawMethod.toUpperCase();
        else if (rawMethod == null) info.method = 'GET';
        else { info.method = ''; info.methodKnown = false; }
      } else {
        // The getter was deliberately not invoked before fetch. Its value may differ now,
        // so never claim the default GET.
        info.method = '';
        info.methodKnown = false;
      }
    } else if (hasProperty(init, 'method')) {
      info.method = '';
      info.methodKnown = false;
    }
    var headersDescriptor = propertyDescriptor(init, 'headers');
    if (headersDescriptor && Object.prototype.hasOwnProperty.call(headersDescriptor, 'value')) {
      var headerValue = headersDescriptor.value;
      if (headerValue !== undefined && headerValue !== null) info.headers = snapshotHeaders(headerValue);
    }
    var bodyDescriptor = propertyDescriptor(init, 'body');
    if (bodyDescriptor) {
      if (Object.prototype.hasOwnProperty.call(bodyDescriptor, 'value')) {
        var bodyValue = bodyDescriptor.value;
        info.body = bodyValue == null
          ? { state: 'EMPTY', reason: 'no_body', bytes: 0 }
          : bodyFromValue(bodyValue);
      } else {
        info.body = { state: 'NOT_COLLECTED', reason: 'accessor' };
      }
    } else if (hasProperty(init, 'body')) {
      info.body = { state: 'NOT_COLLECTED', reason: 'accessor' };
    } else if (!info.body) {
      info.body = { state: 'EMPTY', reason: 'no_body', bytes: 0 };
    }
    return info;
  }

  if (typeof W.fetch === 'function') {
    originalFetch = W.fetch;
    wrappedFetch = function () {
      // The actual request must be the first operation. Inspecting init here and then
      // calling fetch again would read getters twice and consume one-shot header iterators.
      if (stopped) return originalFetch.apply(this, arguments);
      var started = now();
      var id = 'f' + (++seq).toString(36) + started.toString(36);
      var input = arguments[0], init = arguments[1];
      var promise = null, thrown = null;
      try { promise = originalFetch.apply(this, arguments); }
      catch (error) { thrown = error; }
      var info;
      try { info = fetchRequestInfo(input, init); }
      catch (e) {
        info = { method: '', methodKnown: false, url: '', headers: [],
          body: { state: 'FAILED', reason: 'read_error', bytes: 0 } };
      }
      send({ type: 'net-start', id: id, kind: 'fetch', method: info.method,
        methodKnown: info.methodKnown, url: info.url, startMs: Date.now() });
      send({ type: 'net-request', id: id, headers: info.headers });
      emitBody(id, 'request', info.body || { state: 'EMPTY', reason: 'no_body', bytes: 0 });
      if (thrown) {
        send({ type: 'net-end', id: id, outcome: 'failed', durationMs: Math.max(0, now() - started),
          reason: 'network', error: String((thrown && thrown.message) || thrown).slice(0, 512) });
        throw thrown;
      }
      if (!promise || typeof promise.then !== 'function') return promise;
      return promise.then(function (response) {
        if (!stopped) {
          try { observeResponse(id, response, started, info.method); }
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

  function finishResponse(id, durationMs, outcome, sizeBytes, state, reason) {
    // sizeBytes is the response size (Content-Length or a complete read), never the
    // truncated preview size. -1 means the actual size could not be determined.
    send({ type: 'net-end', id: id, outcome: outcome, durationMs: durationMs,
      sizeBytes: sizeBytes, reason: reason || '' });
  }
  function observeResponse(id, response, started, method) {
    var responseDuration = Math.max(0, now() - started);
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
      finishResponse(id, responseDuration, 'completed', length > 0 ? length : -1, 'UNAVAILABLE', 'opaque');
      return;
    }
    if (method === 'HEAD' || response.status === 204 || response.status === 304 || !response.body) {
      var emptyState = (length > 0) ? 'NOT_COLLECTED' : 'EMPTY';
      emitBody(id, 'response', { state: emptyState, bytes: length, reason: length > 0 ? 'capability' : 'no_body' });
      finishResponse(id, responseDuration, 'completed', length, emptyState, '');
      return;
    }
    if (ct === 'text/event-stream') {
      emitBody(id, 'response', { state: 'UNAVAILABLE', reason: 'stream', bytes: length });
      finishResponse(id, responseDuration, 'completed', length > 0 ? length : -1, 'UNAVAILABLE', 'stream');
      return;
    }
    if (!isTextual(ct)) {
      emitBody(id, 'response', { state: 'UNAVAILABLE', reason: 'binary', binary: true,
        bytes: length, contentType: ct });
      finishResponse(id, responseDuration, 'completed', length, 'UNAVAILABLE', 'binary');
      return;
    }
    var clone;
    try { clone = response.clone(); }
    catch (e) {
      emitBody(id, 'response', { state: 'FAILED', reason: 'capability', bytes: length, contentType: ct });
      finishResponse(id, responseDuration, 'completed', length > 0 ? length : -1, 'FAILED', 'capability');
      return;
    }
    var reader;
    try { reader = clone.body.getReader(); }
    catch (e) {
      emitBody(id, 'response', { state: 'FAILED', reason: 'read_error', bytes: length, contentType: ct });
      finishResponse(id, responseDuration, 'completed', length > 0 ? length : -1, 'FAILED', 'read_error');
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
      var responseSize = length > 0 ? length
        : (state === 'COLLECTED' || state === 'EMPTY' ? total : -1);
      finishResponse(id, responseDuration, 'completed', responseSize, state, reason);
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
        if (!value || !value.byteLength) { pump(); return; }
        var remaining = MAX_BYTES - total;
        // A reader result can be larger than the remaining preview. Feed only the bytes
        // that fit into TextDecoder; a partial multi-byte tail is held by the stream
        // decoder and never becomes a replacement character.
        var slice = value.byteLength > remaining ? value.subarray(0, Math.max(0, remaining)) : value;
        var over = value.byteLength > remaining;
        total += slice.byteLength;
        var text = '';
        try {
          text = decoder ? decoder.decode(slice, { stream: true })
            : String.fromCharCode.apply(null, slice);
        } catch (e) { text = ''; }
        if (!append(text)) return;
        if (over || total >= MAX_BYTES || sentChars >= MAX_CHARS) {
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

  function newXhrRecord(method, methodKnown, url) {
    return {
      method: methodKnown ? method.toUpperCase() : '',
      methodKnown: methodKnown,
      url: url,
      headers: [],
      aborted: false,
      started: 0,
      id: '',
      done: false,
      listener: null,
    };
  }

  // Must be called before the XHR is re-opened. Reading status/responseText after the page
  // has reused the object would read the next request (or an empty state), which is how a
  // successful request was previously recorded as failed.
  function finalizeXhr(record, xhr) {
    if (!record || record.done || !record.started) return;
    record.done = true;
    if (record.listener) {
      try { xhr.removeEventListener('loadend', record.listener); } catch (e) { }
      record.listener = null;
    }
    if (stopped) return;
    try { finishXhr(xhr, record); } catch (e) {
      send({ type: 'net-end', id: record.id, outcome: 'failed',
        durationMs: Math.max(0, now() - record.started), reason: 'read_error',
        error: String((e && e.message) || e).slice(0, 512) });
    }
  }

  function attachXhrListener(record, xhr) {
    if (record.done || stopped) return;
    if (xhr.readyState === 4) { finalizeXhr(record, xhr); return; }
    var listener = function () {
      // A loadend from the request that was replaced while this listener was being
      // attached must not settle the new request; only accept a completed state.
      if (xhr.readyState < 4) return;
      finalizeXhr(record, xhr);
    };
    record.listener = listener;
    try { xhr.addEventListener('loadend', listener); } catch (e) { }
  }

  function scheduleXhrListener(record, xhr) {
    setTimeout(function () { attachXhrListener(record, xhr); }, 0);
  }

  if (XHR && typeof originalOpen === 'function' && typeof originalSend === 'function') {
    wrappedOpen = function (method, url) {
      var xhr = this;
      var previous = null;
      try { previous = xhrRecords.get(xhr); } catch (e) { }
      // The page may call open() from the previous request's onload. Snapshot that request
      // now, while the object still holds its status and body.
      if (previous) finalizeXhr(previous, xhr);
      var result;
      try {
        // Do not coerce method/url here: open() must be the first and only conversion of
        // one-shot arguments, or the actual request could change from POST to GET.
        result = originalOpen.apply(this, arguments);
      } finally {
        try {
          var methodKnown = typeof method === 'string';
          var captureUrl = typeof url === 'string' ? absolute(url)
            : (isURLObject(url) ? absolute(url.href) : '');
          xhrRecords.set(xhr, newXhrRecord(methodKnown ? method : '', methodKnown, captureUrl));
        } catch (e) { }
      }
      return result;
    };
    wrappedSetHeader = function (name, value) {
      var result;
      try {
        // Let the real method perform its one conversion first, then record a copy.
        result = originalSetHeader.apply(this, arguments);
      } finally {
        try {
          var record = xhrRecords.get(this);
          if (record && record.headers.length < MAX_HEADERS) {
            record.headers.push([String(name).slice(0, 256), String(value).slice(0, MAX_HEADER_VALUE)]);
          }
        } catch (e) { }
      }
      return result;
    };
    wrappedAbort = function () {
      var record = null;
      try {
        record = xhrRecords.get(this);
        if (record) record.aborted = true;
      } catch (e) { }
      var result = originalAbort.apply(this, arguments);
      if (record && record.started) finalizeXhr(record, this);
      return result;
    };
    wrappedSend = function (body) {
      if (stopped) return originalSend.apply(this, arguments);
      var xhr = this;
      var record;
      try { record = xhrRecords.get(this); } catch (e) { record = null; }
      if (!record) record = newXhrRecord('', false, '');
      if (record.started && !record.done) finalizeXhr(record, xhr);
      var started = now();
      var id = 'x' + (++seq).toString(36) + started.toString(36);
      record.id = id;
      record.started = started;
      record.done = false;
      send({ type: 'net-start', id: id, kind: 'xhr', method: record.method,
        methodKnown: record.methodKnown, url: record.url, startMs: Date.now() });
      send({ type: 'net-request', id: id, headers: record.headers });
      emitBody(id, 'request', bodyFromValue(body));
      try {
        var result = originalSend.apply(this, arguments);
        // A synchronous XHR is already complete. For an async one, defer attaching the
        // loadend listener: when this send happens inside the previous request's onload,
        // that request's loadend is still queued and would otherwise be mistaken for this
        // request's completion.
        if (xhr.readyState === 4) finalizeXhr(record, xhr);
        else scheduleXhrListener(record, xhr);
        return result;
      }
      catch (error) {
        record.done = true;
        if (record.listener) {
          try { xhr.removeEventListener('loadend', record.listener); } catch (e) { }
          record.listener = null;
        }
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

  function finishXhr(xhr, record) {
    var status = 0, statusText = '', responseUrl = '';
    try { status = xhr.status || 0; } catch (e) { }
    try { statusText = String(xhr.statusText || ''); } catch (e) { }
    try { responseUrl = absolute(xhr.responseURL || record.url); } catch (e) { }
    var headers = [];
    try { headers = parseRawHeaders(xhr.getAllResponseHeaders()); } catch (e) { }
    var ct = '';
    try {
      headers.forEach(function (pair) {
        if (!ct && pair[0].toLowerCase() === 'content-type') ct = pair[1].split(';')[0].trim().toLowerCase();
      });
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
    send({ type: 'net-response', id: record.id, status: status, statusText: statusText.slice(0, 256),
      responseUrl: responseUrl, responseType: String(xhr.responseType || ''), headers: headers,
      contentType: ct, contentLength: length });
    var duration = Math.max(0, now() - record.started);
    if (status === 0) {
      var cancelled = record.aborted;
      emitBody(record.id, 'response', { state: cancelled ? 'NOT_COLLECTED' : 'FAILED',
        reason: cancelled ? 'aborted' : 'network' });
      send({ type: 'net-end', id: record.id, outcome: cancelled ? 'cancelled' : 'failed',
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
    emitBody(record.id, 'response', body || { state: 'EMPTY', reason: 'no_body' });
    var reportedSize = -1;
    if (length > 0) reportedSize = length;
    else if (rt === 'arraybuffer' || rt === 'blob') reportedSize = bytes;
    else if (body && (body.state === 'COLLECTED' || body.state === 'EMPTY')) reportedSize = body.bytes || 0;
    send({ type: 'net-end', id: record.id, outcome: 'completed', durationMs: duration,
      sizeBytes: reportedSize, reason: (body && body.reason) || '' });
  }

  // ---- DOM / source accessor (main frame only) -----------------------------
  var api = null;
  if (isTop) {
    var sourceCache = null;
    var nodeIds = new WeakMap();
    var nodes = new Map();
    var signatures = new Map();
    var MAX_NODES = 20000;
    var MAX_ATTR_VALUE = 4096;
    var MAX_TEXT_PREVIEW = 2048;
    var MAX_HTML_CHARS = 65536;
    var MAX_SEARCH_VISITED = 20000;
    var MAX_SEARCH_RESULTS = 50;

    function resetDom() {
      nodeIds = new WeakMap();
      nodes = new Map();
      signatures = new Map();
      sourceCache = null;
    }
    function nodeSignature(node) {
      return node.nodeType + ':' + String(node.tagName || '') + ':' +
        String(node.nodeValue == null ? '' : node.nodeValue).slice(0, 32);
    }
    // Path ids stay resolvable after the bounded node map evicts a reference. A signature
    // prevents an old id from silently pointing at a different node after DOM mutation.
    function rememberNode(node, id) {
      nodeIds.set(node, id);
      nodes.set(id, node);
      signatures.set(id, nodeSignature(node));
      while (nodes.size > MAX_NODES) {
        var first = nodes.keys().next();
        if (first.done || first.value === id) break;
        var old = nodes.get(first.value);
        nodes.delete(first.value);
        if (old) nodeIds.delete(old);
      }
      while (signatures.size > MAX_NODES * 2) {
        var oldest = signatures.keys().next();
        if (oldest.done) break;
        signatures.delete(oldest.value);
      }
    }
    function nodeId(node) {
      var id = nodeIds.get(node);
      if (id) return id;
      var indices = [];
      var current = node;
      while (current && current !== D.documentElement) {
        var parent = current.parentNode;
        if (!parent) return 'n-invalid';
        indices.unshift(Array.prototype.indexOf.call(parent.childNodes, current));
        current = parent;
      }
      if (current !== D.documentElement) return 'n-invalid';
      id = indices.length ? 'n.' + indices.join('.') : 'n';
      rememberNode(node, id);
      return id;
    }
    function resolvePath(id) {
      if (id === 'n') return D.documentElement;
      if (!/^n(\.[0-9]+)+$/.test(id)) return null;
      var parts = id.slice(2).split('.');
      var current = D.documentElement;
      for (var i = 0; i < parts.length; i++) {
        if (!current) return null;
        current = current.childNodes[Number(parts[i])];
      }
      return current || null;
    }
    function nodeOf(id) {
      var node = nodes.get(id);
      if (node && node.isConnected) return node;
      if (node) nodes.delete(id);
      node = resolvePath(id);
      if (!node || !node.isConnected) return null;
      var expected = signatures.get(id);
      if (expected && expected !== nodeSignature(node)) return null;
      nodes.set(id, node);
      nodeIds.set(node, id);
      signatures.set(id, nodeSignature(node));
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
    function describe(node, knownId) {
      var kind = kindOf(node);
      var id = knownId || nodeId(node);
      if (knownId) rememberNode(node, id);
      var out = { id: id, kind: kind, name: '', childCount: 0, attributes: [] };
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
      resetSource: function () { sourceCache = null; },
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
        var parentPath = nodeId(parent);
        var out = [];
        for (var i = start; i < total && out.length < count; i++) {
          out.push(describe(parent.childNodes[i], parentPath + '.' + i));
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
            var idValue = String(node.id || '').toLowerCase();
            var hasClass = function (wanted) {
              if (!node.classList) return false;
              return Array.prototype.some.call(node.classList, function (name) {
                return String(name).toLowerCase() === wanted;
              });
            };
            if (byId != null) matched = idValue === byId;
            else if (byClass != null) matched = hasClass(byClass);
            else matched = tag === tagNeedle || idValue === tagNeedle || hasClass(tagNeedle);
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

  function broadcastStop() {
    try {
      var frames = W.frames;
      for (var i = 0; i < frames.length; i++) {
        try { frames[i].postMessage({ __pureDevToolsStop: token }, '*'); } catch (e) { }
      }
    } catch (e) { }
  }
  var stopMessage = function (event) {
    try {
      if (event && event.data && event.data.__pureDevToolsStop === token) stop();
    } catch (e) { }
  };
  try { W.addEventListener('message', stopMessage); } catch (e) { }

  function stop() {
    if (stopped) return;
    // The script runs in every frame. The native stop only evaluates in the main frame,
    // so walk the frame tree with postMessage; cross-origin frames can receive it too.
    broadcastStop();
    stopped = true;
    try { W.removeEventListener('message', stopMessage); } catch (e) { }
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
