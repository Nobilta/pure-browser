(function installPureElementPicker(win) {
  'use strict';
  if (win.__pureElementPicker) return win.__pureElementPicker;
  var doc = win.document, bridge = win.mybrowserElementPicker;
  // What can never be a target. Hiding the document itself is hiding the page, and every picker
  // that offered it has produced a rule that blanks a site; the rest are not boxes on screen at
  // all, so a rule for them would look like it did nothing.
  var REFUSED = {
    HTML: 1, BODY: 1, HEAD: 1, SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, TITLE: 1, BASE: 1,
    NOSCRIPT: 1, TEMPLATE: 1
  };
  // How far up the tree the parent button may climb, and the most elements a class selector may
  // match and still be offered: past that the class names nothing in particular, and the path
  // through the tree is the honest answer.
  var MAX_DEPTH = 12, MAX_CLASS_MATCHES = 12;
  var STYLE_ID = '__purePickerStyle', BOX_ID = '__purePickerBox', LABEL_ID = '__purePickerLabel';
  var accent = '#6750a4', bottomInset = 0;
  var active = false, chain = [], index = 0, hovered = null;
  var box = null, label = null, style = null;
  var preview = null;

  function post(payload) {
    if (!bridge || !bridge.postMessage) return;
    try { bridge.postMessage(JSON.stringify(payload)); } catch (e) { /* the app is gone */ }
  }

  function esc(value) {
    var text = String(value);
    if (win.CSS && win.CSS.escape) return win.CSS.escape(text);
    return text.replace(/[^A-Za-z0-9_-]/g, function (c) { return '\\' + c; });
  }

  function countOf(selector) {
    try { return doc.querySelectorAll(selector).length; } catch (e) { return -1; }
  }

  function tagOf(el) { return el && el.tagName ? el.tagName.toLowerCase() : ''; }

  function refused(el) { return !el || el.nodeType !== 1 || REFUSED[el.tagName] === 1; }

  function classesOf(el) {
    var out = [], list = el.classList;
    if (!list) return out;
    for (var i = 0; i < list.length && out.length < 4; i++) {
      var name = list[i];
      if (name.length <= 64 && /^[A-Za-z][A-Za-z0-9_-]*$/.test(name)) out.push(name);
    }
    return out;
  }

  /*
   * The best selector an element's own classes can give, or null when none of them is a name
   * worth writing.
   *
   * A class that matches a handful of elements is preferred over the exact path through the tree:
   * ad slots repeat, the same file is often served into several of them, and a path breaks the
   * first time the page is redesigned. Fewest matches wins, then the shortest text, and the tag
   * keeps it from catching an unrelated element that happens to share the name.
   */
  function classSelector(el) {
    var tag = tagOf(el), classes = classesOf(el), best = null;
    for (var i = 0; i < classes.length; i++) {
      var plain = '.' + esc(classes[i]), scoped = tag + plain;
      for (var j = 0; j < 2; j++) {
        var candidate = j === 0 ? scoped : plain;
        var count = countOf(candidate);
        if (count < 1 || count > MAX_CLASS_MATCHES) continue;
        var score = count * 1000 + candidate.length;
        if (!best || score < best.score) best = { selector: candidate, count: count, score: score };
      }
    }
    return best;
  }

  /*
   * The exact path from this element up to the document, stopping at the first level that already
   * identifies it alone.
   *
   * `body` and `html` never appear in it: the walk stops below them, so the shortest path that is
   * unique anywhere in the document is what gets written, and a rule that names the body  -  which
   * would blank the page  -  cannot be written at all.
   */
  function pathSelector(el) {
    var parts = [], node = el, guard = 0;
    while (!refused(node) && guard++ < MAX_DEPTH) {
      var part = tagOf(node), classes = classesOf(node);
      if (classes.length) part += '.' + esc(classes[0]);
      var parent = node.parentElement;
      if (parent) {
        var same = [];
        for (var i = 0; i < parent.children.length; i++) {
          if (parent.children[i].tagName === node.tagName) same.push(parent.children[i]);
        }
        if (same.length > 1) part += ':nth-of-type(' + (same.indexOf(node) + 1) + ')';
      }
      parts.unshift(part);
      if (node !== el && countOf(parts.join(' > ')) === 1) return parts.join(' > ');
      node = parent;
    }
    return parts.join(' > ');
  }

  function selectorFor(el) {
    var id = el.id;
    if (id && id.length <= 64 && /^[A-Za-z][A-Za-z0-9_-]*$/.test(id)) {
      var byId = '#' + esc(id);
      if (countOf(byId) === 1) return { selector: byId, count: 1 };
    }
    var byClass = classSelector(el);
    if (byClass) return byClass;
    var byPath = pathSelector(el);
    return { selector: byPath, count: countOf(byPath) };
  }

  function ensureNodes() {
    style = doc.getElementById(STYLE_ID);
    if (!style) {
      style = doc.createElement('style');
      style.id = STYLE_ID;
      (doc.head || doc.documentElement).appendChild(style);
    }
    // Rewritten per start so the outline follows the app's own accent, including in dark mode.
    style.textContent =
      '#' + BOX_ID + '{position:fixed;z-index:2147483646;pointer-events:none;box-sizing:border-box;' +
        'border:2px solid ' + accent + ';border-radius:3px;' +
        'box-shadow:0 0 0 1px rgba(0,0,0,.35),0 0 10px rgba(0,0,0,.25)}' +
      '#' + LABEL_ID + '{position:fixed;z-index:2147483647;pointer-events:none;max-width:92vw;' +
        'padding:1px 6px;border-radius:4px;background:' + accent + ';color:#fff;' +
        'font:11px/16px monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}';
    box = doc.getElementById(BOX_ID);
    if (!box) {
      box = doc.createElement('div');
      box.id = BOX_ID;
    }
    label = doc.getElementById(LABEL_ID);
    if (!label) {
      label = doc.createElement('div');
      label.id = LABEL_ID;
    }
    // Last children of the body: a page rule cannot take them away with a stacking order.
    var host = doc.body || doc.documentElement;
    host.appendChild(box);
    host.appendChild(label);
    box.style.display = 'none';
    label.style.display = 'none';
  }

  function place(el, text) {
    if (!el || !el.getBoundingClientRect) return;
    var rect = el.getBoundingClientRect();
    /*
     * The bar the app draws sits over the bottom of the page, so an element down there would be
     * outlined behind it. Bringing it up is the only scroll the picker does, and it is the page's
     * own scroll position changing, which is why it happens before anything is drawn from the
     * rectangle: the outline has to be placed from where the element ends up, not where it was.
     */
    if (bottomInset > 0 && rect.bottom > win.innerHeight - bottomInset && rect.height < win.innerHeight - bottomInset) {
      win.scrollBy(0, rect.bottom - (win.innerHeight - bottomInset));
      rect = el.getBoundingClientRect();
    }
    if (rect.width <= 0 && rect.height <= 0) { clear(); return; }
    box.style.display = 'block';
    box.style.left = rect.left + 'px';
    box.style.top = rect.top + 'px';
    box.style.width = rect.width + 'px';
    box.style.height = rect.height + 'px';
    label.style.display = 'block';
    label.textContent = text;
    // Above the outline, or below it when the element touches the top of the screen.
    var above = rect.top >= 22;
    label.style.top = (above ? rect.top - 20 : rect.bottom + 4) + 'px';
    label.style.left = Math.max(2, Math.min(rect.left, win.innerWidth - 40)) + 'px';
  }

  function clear() {
    if (box) box.style.display = 'none';
    if (label) label.style.display = 'none';
  }

  var PREVIEW_STYLE_ID = '__purePickerPreview';

  /*
   * Showing what the chosen rule would do, on the page, before it is written.
   *
   * The matches are hidden by a stylesheet rule with the same `{display:none!important}` declaration
   * the filter engine writes for the saved rule, so the preview is the real thing rather than a
   * sketch of it. It has to be the same mechanism, not merely the same visible result: an inline
   * declaration wins over a page's own `display:block!important` while the engine's stylesheet
   * loses to it, so previewing inline let the picker promise a hidden element that came straight
   * back on the next load. Whatever happens under the rule happens in the preview too.
   *
   * The rule is scoped to this stylesheet and removed on the way out, so the page is left exactly
   * as it was found and nothing about the picker outlives the pick.
   */
  function applyPreview(selector) {
    restorePreview();
    if (!selector) return;
    var sheet = doc.getElementById(PREVIEW_STYLE_ID);
    if (!sheet) {
      sheet = doc.createElement('style');
      sheet.id = PREVIEW_STYLE_ID;
      (doc.head || doc.documentElement).appendChild(sheet);
    }
    sheet.textContent = selector + '{display:none!important;}';
    preview = true;
  }

  function restorePreview() {
    if (!preview) return;
    preview = null;
    var sheet = doc.getElementById(PREVIEW_STYLE_ID);
    if (sheet) sheet.textContent = '';
  }

  function selection() {
    var el = chain[index];
    if (!el) return null;
    var found = selectorFor(el);
    return {
      type: 'pick',
      selector: found.selector,
      count: found.count,
      index: index,
      depth: chain.length,
      canParent: index < chain.length - 1,
      canChild: index > 0
    };
  }

  /**
   * Paints what is selected now, previews it, and tells the app, which is what draws the bar.
   *
   * The order is the point. The preview is lifted first and the outline placed from the element's
   * own box, and only then are the matches hidden again: hiding first would leave the outline with
   * nothing to measure, which is not just the first draw  -  a scroll repaints through here, and the
   * preview is usually still hiding the element when it does.
   */
  function report() {
    var current = selection();
    if (!current) return null;
    restorePreview();
    place(chain[index], current.selector + ' \u00b7 ' + current.count);
    applyPreview(current.selector);
    post(current);
    return current;
  }

  function targetAt(x, y) {
    var el = doc.elementFromPoint(x, y);
    return refused(el) ? null : el;
  }

  function pointOf(event) {
    var touch = (event.touches && event.touches[0]) || (event.changedTouches && event.changedTouches[0]);
    if (touch) return { x: touch.clientX, y: touch.clientY };
    return { x: event.clientX || 0, y: event.clientY || 0 };
  }

  /** The element under the finger while it is down, previewed but not yet chosen. */
  function hover(event) {
    var point = pointOf(event);
    var el = targetAt(point.x, point.y);
    if (!el || el === hovered) return;
    hovered = el;
    var found = selectorFor(el);
    place(el, found.selector + ' \u00b7 ' + found.count);
  }

  function choose(event) {
    var point = pointOf(event);
    var el = targetAt(point.x, point.y) || hovered;
    if (!el) return;
    chain = [];
    index = 0;
    var node = el, guard = 0;
    while (!refused(node) && guard++ < MAX_DEPTH) {
      chain.push(node);
      node = node.parentElement;
    }
    if (!chain.length) return;
    hovered = el;
    report();
  }

  /*
   * Every event the page could act on is taken in the capture phase and stopped there, so a tap
   * selects instead of following a link and a drag outlines instead of scrolling. The touch
   * listeners are explicitly not passive: a document-level touch listener is passive by default,
   * and a passive one is not allowed to prevent the scroll it is there to prevent.
   *
   * `wheel` is not in this list because nothing needs it: a wheel scroll moves the page and leaves
   * the outline correct, since the scroll listener below re-reads the element's box. A non-passive
   * wheel listener on the document was the first suspect when this picker crashed the app on
   * device; the cause turned out to be where the message channel was attached (see
   * ElementPicker.install), and neither listener list is dangerous on its own.
   */
  function claim(event) {
    if (event.cancelable) event.preventDefault();
    event.stopPropagation();
    if (event.stopImmediatePropagation) event.stopImmediatePropagation();
  }

  function claimAll(event) { if (active) claim(event); }
  function onDown(event) { if (active) { claim(event); restorePreview(); hover(event); } }
  function onMove(event) { if (active) { claim(event); hover(event); } }
  function onUp(event) { if (active) { claim(event); choose(event); } }
  /*
   * A cancelled touch is not a tap. The system sends touchcancel when it takes the gesture over —
   * a call arriving, a system edge swipe, the app being backgrounded — and treating it as a release
   * used to choose whatever element was under the finger and write a rule nobody asked for. The pick
   * ends here instead, with the page restored and the app told nothing was chosen.
   */
  function onCancel(event) {
    if (!active) return;
    claim(event);
    // The app has to be told, or its bar would outlive the pick: the page stops outlining here,
    // and the app is the only side that can take the bar away.
    post({ type: "ended" });
    api.stop();
  }
  function reposition() { if (active && chain[index]) report(); }

  function listen(on) {
    var method = on ? 'addEventListener' : 'removeEventListener';
    doc[method]('touchstart', onDown, { capture: true, passive: false });
    doc[method]('touchmove', onMove, { capture: true, passive: false });
    doc[method]('touchend', onUp, { capture: true, passive: false });
    doc[method]('touchcancel', onCancel, { capture: true, passive: false });
    doc[method]('mousemove', onMove, true);
    doc[method]('mousedown', onDown, true);
    doc[method]('mouseup', onUp, true);
    doc[method]('click', onDown, true);
    doc[method]('contextmenu', claimAll, true);
    win[method]('scroll', reposition, true);
    win[method]('resize', reposition, true);
  }

  var api = {
    start: function (options) {
      if (active) return true;
      // Only the top document. An embedded frame has its own overlay and its own coordinates, and
      // a rule written for an element in one would not match in the other.
      if (win.top !== win.self || !doc.body) return false;
      if (options && options.accent) accent = String(options.accent).slice(0, 32);
      bottomInset = options && options.bottomInset > 0 ? Math.min(options.bottomInset, 2000) : 0;
      ensureNodes();
      active = true;
      chain = [];
      index = 0;
      hovered = null;
      listen(true);
      return true;
    },
    stop: function () {
      if (!active) return false;
      active = false;
      listen(false);
      // Before anything else: the page goes back to how it was found. A preview that outlived the
      // pick would be a rule nobody asked for, hiding something with nothing on screen to undo it.
      restorePreview();
      clear();
      chain = [];
      hovered = null;
      return true;
    },
    /** Moves one level towards the document (1) or back into the element (-1). */
    step: function (delta) {
      if (!active || !chain.length) return false;
      var next = index + (delta > 0 ? 1 : -1);
      if (next < 0 || next > chain.length - 1) return false;
      index = next;
      return !!report();
    },
    /** What the picker is showing, for the app and for tests. */
    state: function () {
      var current = active && chain[index] ? selectorFor(chain[index]) : null;
      return {
        active: active,
        selector: current ? current.selector : '',
        count: current ? current.count : 0,
        index: index,
        depth: chain.length
      };
    }
  };
  win.__pureElementPicker = api;
  return api;
})
