(function installPurePageFileDownload(win) {
  'use strict';
  if (win.__purePageFileDownload) return win.__purePageFileDownload;
  var doc = win.document, bridge = win.mybrowserPageFile;

  /*
   * Saving a file the page built in memory.
   *
   * A page can read its own bytes, show its own progress, then click an anchor at a blob: address
   * from URL.createObjectURL. Nothing can fetch that address -- the data is in this document and the
   * name means nothing outside it -- yet Chromium reports a download anyway, so the browser is asked
   * for a file it can neither name nor get. The way every browser answers this is to ask the page for
   * the bytes, and that is what this file arranges.
   *
   * The Blob is remembered when its address is created, which is what keeps it reachable after the
   * page revokes that address: revoking frees the name, not the object. The name can only come from
   * the anchor that was clicked, so a capture-phase listener on the document records it; a
   * programmatic a.click() dispatches a real event too, so no page prototype is replaced to see it.
   *
   * Nothing here starts a transfer by itself. The browser inspects one address, asks the user, and
   * only then requests the bytes -- one slice at a time, each slice requested after the previous one
   * has been written, so neither side holds the whole file.
   */

  // Bounded on purpose: the map keeps a Blob alive, and a page can create object URLs in a loop.
  var MAX_TRACKED = 16, MAX_CHUNK = 1 << 20;
  var blobs = new Map(), names = new Map(), active = null, probing = null;

  function post(payload) {
    if (!bridge || !bridge.postMessage) return false;
    try { bridge.postMessage(JSON.stringify(payload)); return true; } catch (e) { return false; }
  }

  function trim(map) {
    while (map.size > MAX_TRACKED) map.delete(map.keys().next().value);
  }

  function blobFor(url) {
    try {
      var blob = blobs.get(url);
      if (blob && typeof blob.size === 'number' && blob.size >= 0) return blob;
    } catch (e) { /* not a Blob this runtime can read */ }
    return null;
  }

  try {
    if (win.URL && typeof win.URL.createObjectURL === 'function') {
      var original = win.URL.createObjectURL;
      // No marker property here: the media probe wraps this same function and restores it only when
      // its own flag is present, so claiming that flag would make its restore drop this wrapper.
      win.URL.createObjectURL = function (object) {
        var url = original.apply(this, arguments);
        try {
          if (typeof win.Blob !== 'undefined' && object instanceof win.Blob) {
            blobs.set(url, object);
            trim(blobs);
          }
        } catch (e) { /* not a Blob this runtime knows */ }
        return url;
      };
    }
  } catch (e) { /* the runtime refused the patch; the browser then reports the file unavailable */ }

  try {
    doc.addEventListener('click', function (event) {
      var node = event.target;
      if (!node || !node.closest) return;
      var anchor = node.closest('a[download]');
      if (!anchor) return;
      var href = anchor.href || '';
      if (href.indexOf('blob:') !== 0) return;
      names.set(href, String(anchor.getAttribute('download') || '').slice(0, 127));
      trim(names);
    }, true);
  } catch (e) { /* no document to listen on */ }

  /**
   * Ends a failed job: stops the transfer and releases the Blob it was reading from.
   *
   * Every failure path funnels through here because forgetting the Blob is the part that is easy
   * to leave out, and the cost of leaving it out is a large object held until the map's own limit
   * evicts it.
   */
  function stop(reason) {
    var job = active;
    if (!job) return;
    job.done = true;
    active = null;
    var url = job.url;
    forget(url);
    post({ type: 'error', transfer: job.transfer, reason: reason });
  }

  /**
   * Forgets an address once its transfer is over.
   *
   * The map holds the Blob itself, and a page can build objects far larger than this document should
   * keep. Dropping the entry as soon as the bytes have crossed releases that memory; it is only kept
   * while a transfer might still read from it. The page’s own object URL becomes a dead address
   * from this point, which is also what it looks like after the page revokes it.
   */
  function forget(url) {
    try {
      blobs.delete(url);
      names.delete(url);
    } catch (e) { /* nothing to release */ }
  }

  /** Asks for one slice. The browser only sends this after the previous slice reached the disk. */
  function pull(offset, length) {
    var job = active;
    if (!job) return;
    job.blob.slice(offset, offset + length).arrayBuffer().then(function (buffer) {
      if (active !== job || job.done) return;
      // The description must land before its bytes: a raw ArrayBuffer carries no identifier, so the
      // browser pairs it with the chunk message that immediately preceded it. If the description
      // cannot be posted the channel is gone, and posting the bytes anyway would hand over a slice
      // that belongs to no transfer.
      if (!post({ type: 'chunk', transfer: job.transfer, offset: offset, length: buffer.byteLength })) {
        // The channel is gone. Ending the job here is what keeps the Blob from being pinned by a
        // transfer that can never finish; the app finds out when the writer stops seeing slices.
        stop('channel');
        return;
      }
      try {
        bridge.postMessage(buffer);
      } catch (e) {
        stop(String((e && e.name) || 'send'));
      }
    }).catch(function (error) {
      if (active !== job || job.done) return;
      stop(String((error && error.name) || 'read'));
    });
  }

  var api = {
    /** Reports what an address holds without reading a byte of it. */
    inspect: function (url, probe) {
      probing = probe;
      var blob = blobFor(url);
      // A Blob the page already released is reported as unavailable rather than as a download the
      // user would then agree to and watch fail.
      if (!blob) { post({ type: 'unknown', probe: probe }); return false; }
      post({ type: 'info', probe: probe, size: blob.size, name: names.get(url) || '' });
      return true;
    },

    /** Starts one transfer. Answers with begin or error; never throws into the caller. */
    start: function (url, transfer) {
      if (active) { post({ type: 'error', transfer: transfer, reason: 'busy' }); return false; }
      var blob = blobFor(url);
      if (!blob) { post({ type: 'error', transfer: transfer, reason: 'unavailable' }); return false; }
      active = { transfer: transfer, url: url, blob: blob, done: false };
      post({ type: 'begin', transfer: transfer, size: blob.size, name: names.get(url) || '' });
      return true;
    },

    /** One slice was written; pull the next one, or report the file complete. */
    advance: function (transfer, written) {
      var job = active;
      if (!job || job.transfer !== transfer) return false;
      if (written >= job.blob.size) {
        job.done = true; active = null;
        post({ type: 'end', transfer: transfer, size: job.blob.size });
        forget(job.url);
        return true;
      }
      pull(written, MAX_CHUNK);
      return true;
    },

    /** The browser gave up: a failed write, a cancel, or the page going away. */
    cancel: function (transfer) {
      if (!active || (transfer !== undefined && active.transfer !== transfer)) return false;
      var stopped = active.url;
      active = null;
      // A cancelled transfer has no reader left either, so its Blob goes too.
      forget(stopped);
      return true;
    },

    /** For diagnostics and tests: what this document is currently holding on to. */
    state: function () {
      return { tracked: blobs.size, named: names.size, active: active ? active.transfer : null, probing: probing };
    },
  };

  /** Announces the script so the app has a channel to answer on before it asks anything. */
  function hello() {
    post({ type: 'hello' });
  }

  try {
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', hello, { once: true });
  } catch (e) { /* no document to wait on */ }
  hello();

  try {
    if (bridge && bridge.addEventListener) {
      bridge.addEventListener('message', function (event) {
        var data = event && event.data;
        if (typeof data !== 'string' || data.length > 4096) return;
        var payload;
        try { payload = JSON.parse(data); } catch (e) { return; }
        if (!payload || typeof payload.type !== 'string') return;
        if (payload.type === 'inspect') api.inspect(payload.url, payload.probe);
        else if (payload.type === 'start') api.start(payload.url, payload.transfer);
        else if (payload.type === 'advance') api.advance(payload.transfer, payload.written);
        else if (payload.type === 'cancel') api.cancel(payload.transfer);
      });
    }
  } catch (e) { /* no channel; the browser reports the file unavailable */ }

  win.__purePageFileDownload = api;
  return api;
})(window);
