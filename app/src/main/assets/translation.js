(function () {
  if (window.__pureTranslation) return;

  var state = {
    nodes: new Map(),
    compareBlocks: new Map(),
    nextId: 1,
    translated: false,
    compareTranslated: false
  };
  window.__pureTranslation = state;

  var SKIP = {
    SCRIPT: true, STYLE: true, NOSCRIPT: true, CODE: true, PRE: true,
    TEXTAREA: true, INPUT: true, SELECT: true, OPTION: true, SVG: true,
  };
  var BLOCKS = {
    P: true, LI: true, H1: true, H2: true, H3: true, H4: true, H5: true, H6: true,
    BLOCKQUOTE: true, FIGCAPTION: true, DT: true, DD: true, TD: true, TH: true, DIV: true,
  };

  function isEditable(element) {
    try {
      return !!element.closest('[contenteditable="true"],[contenteditable=""]');
    } catch (_) {
      return false;
    }
  }

  function isNearViewport(element) {
    try {
      var rect = element.getBoundingClientRect();
      var margin = window.innerHeight * 0.75;
      return rect.bottom >= -margin && rect.top <= window.innerHeight + margin;
    } catch (_) {
      return true;
    }
  }

  function isVisible(element) {
    try {
      var style = window.getComputedStyle(element);
      if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
      var rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    } catch (_) {
      return true;
    }
  }

  function isSkipped(element) {
    if (!element || SKIP[element.tagName] || isEditable(element)) return true;
    if (element.getAttribute && (element.getAttribute('aria-hidden') === 'true' ||
        element.getAttribute('role') === 'button')) return true;
    try {
      return !!element.closest('nav,aside,button');
    } catch (_) {
      return false;
    }
  }

  function blockText(element) {
    try {
      return String(element.innerText || element.textContent || '').trim();
    } catch (_) {
      return '';
    }
  }

  function ensureStyle() {
    if (document.getElementById('__pureTranslationStyle')) return;
    var style = document.createElement('style');
    style.id = '__pureTranslationStyle';
    style.textContent = '.__pure-translation-block{' +
      'display:block;margin:.45em 0 1em;padding:.1em 0 .1em .8em;' +
      'border-left:2px solid rgba(58,155,114,.72);color:inherit;opacity:.86;' +
      'font:inherit;line-height:inherit;white-space:pre-wrap;}' +
      '.__pure-translation-block[dir="rtl"]{border-left:0;border-right:2px solid rgba(58,155,114,.72);' +
      'padding:.1em .8em .1em 0;}';
    (document.head || document.documentElement).appendChild(style);
  }

  window.__pureTranslateCompareRestore = function () {
    state.compareBlocks.forEach(function (entry) {
      if (entry.translation && entry.translation.parentNode) entry.translation.parentNode.removeChild(entry.translation);
    });
    state.compareBlocks.clear();
    state.compareTranslated = false;
    var style = document.getElementById('__pureTranslationStyle');
    if (style) style.remove();
    return true;
  };

  window.__pureTranslateCompareCollect = function () {
    window.__pureTranslateCompareRestore();
    if (window.__pureTranslateRestore) window.__pureTranslateRestore();
    state.compareBlocks.clear();
    state.nextId = 1;
    var root = document.body || document.documentElement;
    if (!root) return JSON.stringify({ items: [], total: 0, truncated: false });

    var candidates = [];
    var nodes = root.querySelectorAll('p,li,h1,h2,h3,h4,h5,h6,blockquote,figcaption,dt,dd,td,th,div');
    for (var i = 0; i < nodes.length; i++) {
      var element = nodes[i];
      if (isSkipped(element) || !BLOCKS[element.tagName] || !isVisible(element)) continue;
      var text = blockText(element);
      if (!text) continue;
      candidates.push(element);
    }

    // A wrapper and its child paragraph can both match. Translate the deepest block only,
    // otherwise the same sentence appears twice in the comparison.
    var candidateSet = new Set(candidates);
    var nested = new Set();
    for (var j = 0; j < candidates.length; j++) {
      var parent = candidates[j].parentElement;
      while (parent && parent !== root) {
        if (candidateSet.has(parent)) { nested.add(parent); break; }
        parent = parent.parentElement;
      }
    }

    var items = [];
    var total = 0;
    var truncated = false;
    for (var k = 0; k < candidates.length; k++) {
      var block = candidates[k];
      if (nested.has(block)) continue;
      var value = blockText(block);
      if (!value) continue;
      if (value.length > 5000) { truncated = true; continue; }
      if (items.length >= 1200 || total + value.length > 60000) { truncated = true; break; }
      var id = state.nextId++;
      state.compareBlocks.set(id, { element: block, text: value, translation: null });
      items.push({ id: id, text: value, visible: isNearViewport(block) });
      total += value.length;
    }
    state.compareTranslated = false;
    return JSON.stringify({ items: items, total: total, truncated: truncated });
  };

  window.__pureTranslateCompareApply = function (items, language) {
    ensureStyle();
    var applied = 0;
    for (var i = 0; i < items.length; i++) {
      var entry = state.compareBlocks.get(items[i].id);
      if (!entry || !entry.element) continue;
      var translation = entry.translation;
      if (!translation) {
        translation = document.createElement('div');
        translation.className = '__pure-translation-block';
        translation.setAttribute('data-pure-translation', String(items[i].id));
        var tag = entry.element.tagName;
        if (tag === 'LI' || tag === 'TD' || tag === 'TH') {
          entry.element.appendChild(translation);
        } else if (entry.element.parentNode) {
          entry.element.parentNode.insertBefore(translation, entry.element.nextSibling);
        } else {
          continue;
        }
        entry.translation = translation;
      }
      translation.setAttribute('lang', language || '');
      translation.setAttribute('dir', 'auto');
      translation.textContent = items[i].text;
      applied++;
    }
    state.compareTranslated = applied > 0 || state.compareTranslated;
    return applied;
  };

  window.__pureTranslateVisibleIds = function (mode) {
    var ids = [];
    if (mode === 'compare') {
      state.compareBlocks.forEach(function (entry, id) {
        if (entry.element && isNearViewport(entry.element)) ids.push(id);
      });
    } else {
      state.nodes.forEach(function (entry, id) {
        if (entry.element && isNearViewport(entry.element)) ids.push(id);
      });
    }
    return JSON.stringify(ids);
  };

  window.__pureTranslateCollect = function () {
    window.__pureTranslateCompareRestore();
    if (state.translated) window.__pureTranslateRestore();
    state.nodes.clear();
    state.nextId = 1;
    var root = document.body || document.documentElement;
    if (!root) return JSON.stringify({ items: [], total: 0, truncated: false });

    var items = [];
    var total = 0;
    var truncated = false;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    while (walker.nextNode()) {
      var node = walker.currentNode;
      var raw = node.nodeValue;
      if (!raw || !raw.trim()) continue;
      var parent = node.parentElement;
      if (!parent || SKIP[parent.tagName] || isEditable(parent) || !isVisible(parent)) continue;
      if (raw.length > 5000) { truncated = true; continue; }
      var core = raw.trim();
      if (!core) continue;
      var start = raw.indexOf(core);
      var leading = start > 0 ? raw.slice(0, start) : '';
      var trailing = raw.slice(start + core.length);
      if (items.length >= 1200 || total + core.length > 60000) { truncated = true; break; }
      var id = state.nextId++;
      state.nodes.set(id, { node: node, raw: raw, leading: leading, trailing: trailing, element: parent });
      items.push({ id: id, text: core, visible: isNearViewport(parent) });
      total += core.length;
    }
    state.translated = false;
    return JSON.stringify({ items: items, total: total, truncated: truncated });
  };

  window.__pureTranslateApply = function (items) {
    var applied = 0;
    for (var i = 0; i < items.length; i++) {
      var entry = state.nodes.get(items[i].id);
      if (!entry || !entry.node) continue;
      entry.node.nodeValue = entry.leading + items[i].text + entry.trailing;
      applied++;
    }
    state.translated = applied > 0;
    return applied;
  };

  window.__pureTranslateRestore = function () {
    state.nodes.forEach(function (entry) {
      if (entry.node) entry.node.nodeValue = entry.raw;
    });
    state.nodes.clear();
    state.translated = false;
    return true;
  };

  window.__pureTranslateTranslatedMode = function () {
    if (state.compareTranslated) return 'compare';
    if (state.translated) return 'replace';
    return 'none';
  };

  window.__pureTranslateSelection = function () {
    var selection = window.getSelection();
    return selection ? String(selection).trim() : '';
  };
})();
