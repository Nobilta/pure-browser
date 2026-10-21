(function () {
  if (window.__pureTranslation) return;

  var state = { nodes: new Map(), nextId: 1, translated: false };
  window.__pureTranslation = state;

  var SKIP = {
    SCRIPT: true, STYLE: true, NOSCRIPT: true, CODE: true, PRE: true,
    TEXTAREA: true, INPUT: true, SELECT: true, OPTION: true, SVG: true,
  };

  function isEditable(element) {
    try {
      return !!element.closest('[contenteditable="true"],[contenteditable=""]');
    } catch (_) {
      return false;
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

  window.__pureTranslateCollect = function () {
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
      state.nodes.set(id, { node: node, raw: raw, leading: leading, trailing: trailing });
      items.push({ id: id, text: core });
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

  window.__pureTranslateSelection = function () {
    var selection = window.getSelection();
    return selection ? String(selection).trim() : '';
  };
})();
