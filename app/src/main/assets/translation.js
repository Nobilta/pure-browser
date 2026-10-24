(function () {
  if (window.__pureTranslation) return;

  var state = {
    nodes: new Map(),
    nodeIds: new Map(),
    replaceDirty: true,
    replaceObserver: null,
    replaceTotal: 0,
    replaceActive: false,
    compareBlocks: new Map(),
    compareReady: new Set(),
    compareTotalCharacters: 0,
    compareDoneCharacters: 0,
    compareTotalGroups: 0,
    compareDoneGroups: 0,
    compareTruncated: false,
    compareActive: false,
    compareObserver: null,
    compareMutationObserver: null,
    compareApplying: false,
    compareRestoring: false,
    nextId: 1,
    translated: false,
    compareTranslated: false
  };
  window.__pureTranslation = state;

  var SKIP = {
    SCRIPT: true, STYLE: true, NOSCRIPT: true, CODE: true, PRE: true,
    TEXTAREA: true, INPUT: true, SELECT: true, OPTION: true, SVG: true,
  };
  var BLOCK_TAGS = {
    ADDRESS: true, ARTICLE: true, ASIDE: true, BLOCKQUOTE: true, CENTER: true,
    DETAILS: true, DIALOG: true, DD: true, DIV: true, DL: true, DT: true,
    FIELDSET: true, FIGCAPTION: true, FIGURE: true, FOOTER: true, FORM: true,
    H1: true, H2: true, H3: true, H4: true, H5: true, H6: true, HEADER: true,
    HR: true, LI: true, MAIN: true, NAV: true, OL: true, P: true, PRE: true,
    SECTION: true, TABLE: true, TBODY: true, TD: true, TFOOT: true, TH: true,
    THEAD: true, TR: true, UL: true,
  };
  var INLINE_TAGS = {
    A: true, ABBR: true, B: true, BDI: true, BDO: true, CITE: true, DATA: true,
    EM: true, I: true, LABEL: true, MARK: true, Q: true, S: true, SMALL: true,
    SPAN: true, STRONG: true, SUB: true, SUP: true, TIME: true, U: true,
  };
  var OBSERVED_ATTRIBUTES = ['style', 'class', 'hidden', 'aria-hidden', 'translate', 'open',
    'contenteditable', 'role'];

  function isEditable(element) {
    try {
      if (element.isContentEditable) return true;
      var node = element;
      while (node && node.getAttribute) {
        var value = node.getAttribute('contenteditable');
        if (value !== null && value.toLowerCase() !== 'false') return true;
        node = node.parentElement;
      }
      return false;
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
      return rect.width > 0 || rect.height > 0;
    } catch (_) {
      return true;
    }
  }

  function isSkipped(element, root) {
    var node = element;
    while (node) {
      // SVG elements report lower-case tag names while the HTML table above is upper-case.
      if (SKIP[node.tagName && node.tagName.toUpperCase()] || isEditable(node)) return true;
      if (node.getAttribute && (node.getAttribute('aria-hidden') === 'true' ||
          node.getAttribute('role') === 'button' || node.getAttribute('translate') === 'no')) return true;
      try {
        if (node.matches && node.matches('nav,aside,button')) return true;
      } catch (_) {
        // Ignore selector support differences and keep walking.
      }
      if (node === root) break;
      node = node.parentElement;
    }
    return false;
  }

  function isBlockElement(element) {
    if (!element || element.nodeType !== 1) return false;
    var tag = element.tagName.toUpperCase();
    if (tag === 'PURE-TRANSLATION-BLOCK') return false;
    return !!BLOCK_TAGS[tag] || (tag.indexOf('-') >= 0);
  }

  function isInlineRoot(element) {
    var tag = element.tagName.toUpperCase();
    if (INLINE_TAGS[tag]) return true;
    try {
      return !!element.closest('a,button,[role="button"]');
    } catch (_) {
      return false;
    }
  }

  function validGroupText(text) {
    return !!text && !/^[0-9\s.\-_#:()!•+~@$%^&*\n/`]+$/.test(text);
  }

  function groupText(group) {
    var text = group.parts.join('').replace(/[ \t\r\f\v]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
    return text;
  }

  function significantChildren(element) {
    var result = [];
    var child = element.firstChild;
    while (child) {
      if (child.nodeType === 3) {
        // Keep whitespace nodes: they are the only thing separating two inline elements.
        result.push(child);
      } else if (child.nodeType === 1) {
        var tag = child.tagName.toUpperCase();
        if (tag !== 'PURE-TRANSLATION-BLOCK' && !SKIP[tag]) result.push(child);
      }
      child = child.nextSibling;
    }
    return result;
  }

  function hasBlockDescendant(element) {
    var stack = [];
    var child = element.firstElementChild;
    while (child) {
      stack.push(child);
      child = child.nextElementSibling;
    }
    while (stack.length) {
      var current = stack.pop();
      if (isBlockElement(current)) return true;
      var nested = current.firstElementChild;
      while (nested) {
        stack.push(nested);
        nested = nested.nextElementSibling;
      }
    }
    return false;
  }

  function inlineText(element, pageRoot) {
    var text = '';
    function visit(node) {
      if (node.nodeType === 3) {
        text += node.nodeValue;
        return;
      }
      if (node.nodeType !== 1) return;
      var tag = node.tagName.toUpperCase();
      if (tag === 'BR') {
        text += '\n';
        return;
      }
      if (isSkipped(node, pageRoot) || !isVisible(node)) return;
      var child = node.firstChild;
      while (child) {
        visit(child);
        child = child.nextSibling;
      }
    }
    var child = element.firstChild;
    while (child) {
      visit(child);
      child = child.nextSibling;
    }
    return text;
  }

  function collectGroups(root, pageRoot, output) {
    if (!root || root.nodeType !== 1) return;
    if (isSkipped(root, pageRoot) || !isVisible(root)) return;

    var group = null;
    function flush() {
      if (!group) return;
      var text = groupText(group);
      if (!validGroupText(text)) {
        group = null;
        return;
      }
      output.push({
        root: group.root,
        endNode: group.nodes[group.nodes.length - 1],
        text: text,
        inline: isInlineRoot(group.root),
      });
      group = null;
    }

    var children = significantChildren(root);
    for (var i = 0; i < children.length; i++) {
      var child = children[i];
      if (child.nodeType === 1 && isBlockElement(child)) {
        flush();
        collectGroups(child, pageRoot, output);
        continue;
      }
      if (child.nodeType === 1) {
        var childTag = child.tagName.toUpperCase();
        if (childTag === 'BR') {
          if (group) {
            group.nodes.push(child);
            group.parts.push('\n');
          }
          continue;
        }
        if (isSkipped(child, pageRoot) || !isVisible(child)) continue;
        if (hasBlockDescendant(child)) {
          flush();
          collectGroups(child, pageRoot, output);
          continue;
        }
      }
      if (child.nodeType === 3) {
        if (!group && !child.nodeValue.trim()) continue;
        if (!group) group = { root: root, nodes: [], parts: [] };
        group.nodes.push(child);
        group.parts.push(child.nodeValue);
        continue;
      }
      if (!group) group = { root: root, nodes: [], parts: [] };
      group.nodes.push(child);
      group.parts.push(inlineText(child, pageRoot));
    }
    flush();
  }

  var TRANSLATION_TAG = 'pure-translation-block';
  var TRANSLATION_HOST_STYLE = [
    'all: initial !important',
    'position: static !important',
    'inset: auto !important',
    'float: none !important',
    'clear: both !important',
    'width: auto !important',
    'height: auto !important',
    'min-width: 0 !important',
    'min-height: 0 !important',
    'max-width: 100% !important',
    'max-height: none !important',
    'margin: 0 !important',
    'padding: 0 !important',
    'border: 0 !important',
    'box-sizing: border-box !important',
    'overflow: visible !important',
    'transform: none !important',
    'translate: none !important',
    'scale: none !important',
    'rotate: none !important',
    'z-index: auto !important',
    'visibility: visible !important',
    'opacity: 1 !important',
    'background: none !important',
    'box-shadow: none !important',
    'outline: none !important',
    'filter: none !important',
    'clip-path: none !important',
    'text-decoration: none !important',
    'text-indent: 0 !important',
    'text-transform: none !important',
    'letter-spacing: normal !important',
    'word-spacing: normal !important',
    'color: inherit !important',
    'font: inherit !important',
    'line-height: inherit !important',
    'text-align: inherit !important',
    'direction: inherit !important',
    'unicode-bidi: normal !important',
    'vertical-align: baseline !important',
    'grid-column: 1 / -1 !important',
    'grid-row: auto !important',
    'flex: 0 0 auto !important',
    'align-self: stretch !important'
  ].join(';');

  // The result lives inside the paragraph's own container after its last source node.
  // Custom-element + shadow-root isolation keeps page CSS out; the inline `display` is
  // the only thing the page's layout needs to see.
  function makeTranslation(inline) {
    var host = document.createElement(TRANSLATION_TAG);
    host.className = '__pure-translation-block';
    host.style.cssText = TRANSLATION_HOST_STYLE +
      ';display:' + (inline ? 'inline-block' : 'block') + ' !important;';
    var shadow = host.attachShadow ? host.attachShadow({ mode: 'open' }) : null;
    var line = document.createElement('div');
    var lineClass = '__pure-translation-line' + (inline ? ' __pure-translation-line--inline' : '');
    if (shadow) {
      var style = document.createElement('style');
      style.textContent =
        ':host{display:block;}' +
        '.__pure-translation-line{display:block;margin:.45em 0 1em;' +
        'padding:.1em 0 .1em .8em;border-left:2px solid rgba(58,155,114,.72);' +
        'color:inherit;opacity:.86;font:inherit;line-height:inherit;' +
        'white-space:pre-wrap;overflow-wrap:break-word;}' +
        '.__pure-translation-line--inline{display:inline;margin:0 0 0 .4em;' +
        'padding:0;border:0;}' +
        '.__pure-translation-line[dir="rtl"]{border-left:0;' +
        'border-right:2px solid rgba(58,155,114,.72);padding:.1em .8em .1em 0;}' +
        '.__pure-translation-line--inline[dir="rtl"]{border:0;margin:0 .4em 0 0;}';
      shadow.appendChild(style);
      shadow.appendChild(line);
    } else {
      line.style.cssText = 'display:' + (inline ? 'inline' : 'block') + ';' +
        (inline ? 'margin-left:.4em;' : 'margin:.45em 0 1em;padding:.1em 0 .1em .8em;' +
          'border-left:2px solid rgba(58,155,114,.72);') +
        'color:inherit;opacity:.86;font:inherit;line-height:inherit;' +
        'white-space:pre-wrap;overflow-wrap:break-word;';
      host.appendChild(line);
    }
    line.className = lineClass;
    return { host: host, line: line };
  }

  function markCompareReady(entry) {
    if (!entry || entry.translated || entry.pending) return;
    state.compareReady.add(entry.id);
  }

  function compareMetrics() {
    var pending = 0;
    state.compareBlocks.forEach(function (entry) { if (entry.pending) pending++; });
    return {
      totalCharacters: state.compareTotalCharacters,
      doneCharacters: state.compareDoneCharacters,
      totalGroups: state.compareTotalGroups,
      doneGroups: state.compareDoneGroups,
      pending: pending,
      complete: state.compareActive &&
        state.compareDoneGroups >= state.compareTotalGroups &&
        state.compareReady.size === 0 && pending === 0
    };
  }

  function removeCompareEntry(id, entry) {
    if (!entry) return;
    if (entry.translation && entry.translation.parentNode) {
      entry.translation.parentNode.removeChild(entry.translation);
    }
    state.compareReady.delete(id);
    if (entry.root) {
      if (entry.root.__pureGroupEnds) entry.root.__pureGroupEnds.delete(entry.endNode);
      if (entry.root.__pureGroupIds) entry.root.__pureGroupIds.delete(id);
      if (entry.root.__pureGroupIds && entry.root.__pureGroupIds.size === 0) {
        if (state.compareObserver) state.compareObserver.unobserve(entry.root);
        entry.root.__pureGroupObserved = false;
      }
    }
    if (entry.translated) {
      state.compareDoneGroups = Math.max(0, state.compareDoneGroups - 1);
      state.compareDoneCharacters = Math.max(0, state.compareDoneCharacters - entry.text.length);
    }
    state.compareTotalGroups = Math.max(0, state.compareTotalGroups - 1);
    state.compareTotalCharacters = Math.max(0, state.compareTotalCharacters - entry.text.length);
    state.compareBlocks.delete(id);
  }

  function cleanupRemovedGroups() {
    var stale = [];
    state.compareBlocks.forEach(function (entry, id) {
      if (!entry.root || !entry.root.isConnected || !entry.endNode || !entry.endNode.isConnected) stale.push(id);
    });
    stale.forEach(function (id) { removeCompareEntry(id, state.compareBlocks.get(id)); });
  }

  function registerGroup(group) {
    if (!group || !group.root || !group.endNode || !group.text) return;
    var ends = group.root.__pureGroupEnds;
    if (!ends) {
      ends = new Set();
      group.root.__pureGroupEnds = ends;
    }
    if (ends.has(group.endNode)) return;
    if (group.text.length > 5000) {
      state.compareTruncated = true;
      return;
    }
    if (state.compareTotalGroups >= 1200 ||
        state.compareTotalCharacters + group.text.length > 60000) {
      state.compareTruncated = true;
      return;
    }
    var id = state.nextId++;
    var entry = {
      id: id,
      root: group.root,
      endNode: group.endNode,
      text: group.text,
      inline: group.inline,
      translated: false,
      pending: false,
      translation: null,
      translationLine: null,
    };
    ends.add(group.endNode);
    var ids = group.root.__pureGroupIds;
    if (!ids) {
      ids = new Set();
      group.root.__pureGroupIds = ids;
    }
    ids.add(id);
    state.compareBlocks.set(id, entry);
    state.compareTotalGroups++;
    state.compareTotalCharacters += group.text.length;
    if (state.compareObserver) {
      if (!group.root.__pureGroupObserved) {
        group.root.__pureGroupObserved = true;
        state.compareObserver.observe(group.root);
      }
    } else {
      markCompareReady(entry);
    }
    if (isNearViewport(group.root)) markCompareReady(entry);
  }

  function refreshCompareRoot(root, pageRoot) {
    var next = [];
    collectGroups(root, pageRoot, next);
    var existing = [];
    state.compareBlocks.forEach(function (entry, id) {
      if (entry.root === root || root.contains(entry.root)) existing.push({ id: id, entry: entry });
    });
    existing.forEach(function (saved) {
      var match = -1;
      for (var i = 0; i < next.length; i++) {
        if (next[i] && next[i].root === saved.entry.root && next[i].endNode === saved.entry.endNode &&
            next[i].text === saved.entry.text && next[i].inline === saved.entry.inline) {
          match = i;
          break;
        }
      }
      if (match >= 0 && (!saved.entry.translation || saved.entry.translation.isConnected)) {
        next[match] = null;
      } else {
        removeCompareEntry(saved.id, saved.entry);
      }
    });
    next.forEach(function (group) { if (group) registerGroup(group); });
  }

  function nearestGroupRoot(node) {
    var element = node && node.nodeType === 1 ? node : (node ? node.parentElement : null);
    while (element && element !== document.body && !isBlockElement(element)) {
      element = element.parentElement;
    }
    return element || document.body || document.documentElement;
  }

  function refreshCompareSubtree(target) {
    if (!target) return;
    var rootNode = nearestGroupRoot(target);
    if (!rootNode) return;
    state.compareApplying = true;
    try {
      // Reconcile the whole affected region once. Structural changes can merge an old
      // inline group into its parent, or split one parent group into several children.
      state.compareBlocks.forEach(function (entry) {
        if (entry.root && entry.root.isConnected &&
            entry.root.contains(rootNode)) {
          rootNode = entry.root;
        }
      });
      var pageRoot = document.body || document.documentElement;
      refreshCompareRoot(rootNode, pageRoot);
      cleanupRemovedGroups();
    } finally {
      state.compareApplying = false;
    }
  }

  function isTranslationNode(node) {
    var element = node && node.nodeType === 1 ? node : (node ? node.parentElement : null);
    return !!(element && element.closest && element.closest('pure-translation-block'));
  }

  function observeGroups(pageRoot) {
    if ('IntersectionObserver' in window) {
      state.compareObserver = new IntersectionObserver(function (entries) {
        entries.forEach(function (item) {
          if (!item.isIntersecting) return;
          var ids = item.target.__pureGroupIds;
          if (!ids) return;
          ids.forEach(function (id) { markCompareReady(state.compareBlocks.get(id)); });
        });
      }, { rootMargin: '100% 0px 100% 0px' });
    }
    if ('MutationObserver' in window) {
      state.compareMutationObserver = new MutationObserver(function (mutations) {
        if (state.compareRestoring || state.compareApplying) return;
        mutations.forEach(function (mutation) {
          if (isTranslationNode(mutation.target)) return;
          if (mutation.type === 'characterData' || mutation.type === 'attributes') {
            refreshCompareSubtree(mutation.target);
            return;
          }
          var changed = Array.prototype.some.call(mutation.addedNodes, function (node) {
            return !isTranslationNode(node);
          }) || Array.prototype.some.call(mutation.removedNodes, function (node) {
            return !isTranslationNode(node);
          });
          if (changed) {
            refreshCompareSubtree(mutation.target);
          }
        });
      });
    }
  }

  window.__pureTranslateCompareRestore = function () {
    state.compareRestoring = true;
    if (state.compareObserver) {
      state.compareObserver.disconnect();
      state.compareObserver = null;
    }
    if (state.compareMutationObserver) {
      state.compareMutationObserver.disconnect();
      state.compareMutationObserver = null;
    }
    state.compareBlocks.forEach(function (entry) {
      if (entry.translation && entry.translation.parentNode) {
        entry.translation.parentNode.removeChild(entry.translation);
      }
      if (entry.root) {
        if (entry.root.__pureGroupEnds) entry.root.__pureGroupEnds.clear();
        if (entry.root.__pureGroupIds) entry.root.__pureGroupIds.clear();
        entry.root.__pureGroupObserved = false;
      }
    });
    state.compareBlocks.clear();
    state.compareReady.clear();
    state.compareTotalCharacters = 0;
    state.compareDoneCharacters = 0;
    state.compareTotalGroups = 0;
    state.compareDoneGroups = 0;
    state.compareTruncated = false;
    state.compareTranslated = false;
    state.compareActive = false;
    var style = document.getElementById('__pureTranslationStyle');
    if (style) style.remove();
    state.compareRestoring = false;
    return true;
  };

  window.__pureTranslateCompareStart = function () {
    window.__pureTranslateCompareRestore();
    var pageRoot = document.body || document.documentElement;
    if (!pageRoot) return JSON.stringify({ totalCharacters: 0, totalGroups: 0, truncated: false });
    observeGroups(pageRoot);
    var groups = [];
    collectGroups(pageRoot, pageRoot, groups);
    groups.forEach(registerGroup);
    if (state.compareMutationObserver) {
      state.compareMutationObserver.observe(pageRoot, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true,
        attributeFilter: OBSERVED_ATTRIBUTES,
      });
    }
    state.compareTranslated = false;
    state.compareActive = state.compareTotalGroups > 0;
    return JSON.stringify({
      totalCharacters: state.compareTotalCharacters,
      totalGroups: state.compareTotalGroups,
      truncated: state.compareTruncated,
    });
  };

  window.__pureTranslateCompareCollect = function () {
    var started = JSON.parse(window.__pureTranslateCompareStart());
    return JSON.stringify({
      items: [],
      total: started.totalCharacters,
      truncated: started.truncated,
    });
  };

  window.__pureTranslateTakeReady = function (maxGroups, maxChars) {
    maxGroups = Math.max(1, Number(maxGroups) || 16);
    maxChars = Math.max(1, Number(maxChars) || 6000);
    var items = [];
    var chars = 0;
    var iterator = state.compareReady.values();
    for (var step = iterator.next(); !step.done; step = iterator.next()) {
      var id = step.value;
      var entry = state.compareBlocks.get(id);
      if (!entry || entry.translated || entry.pending) {
        state.compareReady.delete(id);
        continue;
      }
      if (items.length > 0 && chars + entry.text.length > maxChars) break;
      entry.pending = true;
      state.compareReady.delete(id);
      items.push({ id: id, text: entry.text });
      chars += entry.text.length;
      if (items.length >= maxGroups) break;
    }
    return JSON.stringify({ items: items, ...compareMetrics() });
  };

  window.__pureTranslateRequeue = function (ids) {
    if (!ids) return 0;
    var count = 0;
    for (var i = 0; i < ids.length; i++) {
      var entry = state.compareBlocks.get(ids[i]);
      if (!entry || entry.translated) continue;
      entry.pending = false;
      markCompareReady(entry);
      count++;
    }
    return count;
  };

  window.__pureTranslateCompareStatus = function () {
    return JSON.stringify(compareMetrics());
  };

  window.__pureTranslateCompareApply = function (items, language) {
    state.compareApplying = true;
    var applied = 0;
    try {
      for (var i = 0; i < items.length; i++) {
        var entry = state.compareBlocks.get(items[i].id);
        if (!entry || !entry.root || !entry.endNode) continue;
        var pageRoot = document.body || document.documentElement;
        if (!entry.root.isConnected || !entry.endNode.isConnected ||
            isSkipped(entry.root, pageRoot) || !isVisible(entry.root)) {
          removeCompareEntry(entry.id, entry);
          continue;
        }
        var translation = entry.translation;
        var line = entry.translationLine;
        if (!translation) {
          var built = makeTranslation(entry.inline);
          translation = built.host;
          line = built.line;
          translation.setAttribute('data-pure-translation', String(items[i].id));
          // Insert after the paragraph's last source node, inside the same container.
          if (entry.endNode.parentNode === entry.root) {
            entry.root.insertBefore(translation, entry.endNode.nextSibling);
          } else {
            entry.root.appendChild(translation);
          }
          entry.translation = translation;
          entry.translationLine = line;
        }
        if (!line) {
          line = translation.shadowRoot
            ? translation.shadowRoot.querySelector('.__pure-translation-line')
            : translation.firstElementChild;
          entry.translationLine = line;
        }
        if (!line) continue;
        line.setAttribute('lang', language || '');
        line.setAttribute('dir', 'auto');
        line.textContent = items[i].text;
        entry.pending = false;
        if (!entry.translated) {
          entry.translated = true;
          state.compareDoneGroups++;
          state.compareDoneCharacters += entry.text.length;
        }
        applied++;
      }
    } finally {
      state.compareApplying = false;
    }
    state.compareTranslated = applied > 0 || state.compareTranslated;
    return applied;
  };

  window.__pureTranslateVisibleIds = function (mode) {
    var ids = [];
    if (mode === 'compare') {
      state.compareReady.forEach(function (id) { ids.push(id); });
    } else {
      state.nodes.forEach(function (entry, id) {
        if (isReplaceEligible(entry) && isNearViewport(entry.node.parentElement)) ids.push(id);
      });
    }
    return JSON.stringify(ids);
  };

  function isReplaceEligible(entry) {
    var root = document.body || document.documentElement;
    var node = entry && entry.node;
    var parent = node && node.parentElement;
    return !!(root && node && node.isConnected && root.contains(node) && parent &&
      !isSkipped(parent, root) && isVisible(parent));
  }

  window.__pureTranslateCollect = function () {
    window.__pureTranslateCompareRestore();
    if (state.replaceObserver) {
      state.replaceObserver.disconnect();
      state.replaceObserver = null;
    }
    if (state.translated) window.__pureTranslateRestore();
    state.nodes.clear();
    state.nodeIds.clear();
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
      if (!parent || isSkipped(parent, root) || !isVisible(parent)) continue;
      if (raw.length > 5000) { truncated = true; continue; }
      var core = raw.trim();
      if (!core) continue;
      var start = raw.indexOf(core);
      var leading = start > 0 ? raw.slice(0, start) : '';
      var trailing = raw.slice(start + core.length);
      if (items.length >= 1200 || total + core.length > 60000) { truncated = true; break; }
      var id = state.nextId++;
      state.nodes.set(id, {
        node: node,
        raw: raw,
        leading: leading,
        trailing: trailing,
        element: parent,
        translated: false,
        applied: null,
        version: 1,
      });
      state.nodeIds.set(node, id);
      items.push({ id: id, text: core, visible: isNearViewport(parent), version: 1 });
      total += core.length;
    }
    state.translated = false;
    state.replaceTotal = total;
    state.replaceActive = state.nodes.size > 0;
    state.replaceDirty = false;
    if ('MutationObserver' in window) {
      state.replaceObserver = new MutationObserver(function (mutations) {
        var dirty = false;
        mutations.forEach(function (mutation) {
          if (mutation.type === 'characterData') {
            var id = state.nodeIds.get(mutation.target);
            var entry = id != null ? state.nodes.get(id) : null;
            if (entry && entry.translated && mutation.target.nodeValue === entry.applied) return;
          }
          dirty = true;
        });
        if (dirty) state.replaceDirty = true;
      });
      var pageRoot = document.body || document.documentElement;
      if (pageRoot) {
        state.replaceObserver.observe(pageRoot, {
          childList: true,
          subtree: true,
          characterData: true,
          attributes: true,
          attributeFilter: OBSERVED_ATTRIBUTES,
        });
      }
    }
    return JSON.stringify({ items: items, total: total, truncated: truncated });
  };

  window.__pureTranslateApply = function (items) {
    var applied = 0;
    for (var i = 0; i < items.length; i++) {
      var entry = state.nodes.get(items[i].id);
      if (!entry || !entry.node) continue;
      if (!isReplaceEligible(entry)) {
        // If eligibility returns before the next scan, the rejected batch still needs
        // to be queued again; unchanged raw text alone must not suppress that retry.
        entry.invalidated = true;
        entry.version++;
        state.replaceDirty = true;
        continue;
      }
      // Bind the result to the source revision the request was made for. The page may have
      // changed this node while the request was in flight, in which case this batch is stale.
      if ((items[i].version != null && entry.version !== items[i].version) ||
          entry.node.nodeValue !== entry.raw) {
        state.replaceDirty = true;
        continue;
      }
      entry.applied = entry.leading + items[i].text + entry.trailing;
      entry.node.nodeValue = entry.applied;
      entry.translated = true;
      applied++;
    }
    state.translated = applied > 0 || state.translated;
    return applied;
  };

  window.__pureTranslateReplaceRequeue = function (items) {
    items.forEach(function (item) {
      var entry = state.nodes.get(item.id);
      if (entry && !entry.translated && entry.version === item.version) entry.invalidated = true;
    });
    // A retry must read the live DOM. Reusing the native batch could resurrect a removed
    // node or overwrite a newer revision that the poll collected during the request.
    state.replaceDirty = true;
  };

  window.__pureTranslateReplaceCollectNew = function () {
    if (!state.replaceDirty) {
      return JSON.stringify({ items: [], total: state.replaceTotal, truncated: false });
    }
    state.replaceDirty = false;
    var root = document.body || document.documentElement;
    if (!root) return JSON.stringify({ items: [], total: state.replaceTotal, truncated: false });
    var items = [];
    var total = state.replaceTotal;
    var truncated = false;
    var removedIds = [];
    state.nodes.forEach(function (entry, id) {
      if (isReplaceEligible(entry) && entry.node.nodeValue && entry.node.nodeValue.trim()) return;
      if (entry.translated && entry.node.nodeValue === entry.applied) entry.node.nodeValue = entry.raw;
      total = Math.max(0, total - entry.raw.trim().length);
      state.nodes.delete(id);
      state.nodeIds.delete(entry.node);
      removedIds.push(id);
    });
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    while (walker.nextNode()) {
      var node = walker.currentNode;
      var raw = node.nodeValue;
      if (!raw || !raw.trim()) continue;
      var parent = node.parentElement;
      if (!parent || isSkipped(parent, root) || !isVisible(parent)) continue;
      var core = raw.trim();
      if (!core) continue;
      var start = raw.indexOf(core);
      var leading = start > 0 ? raw.slice(0, start) : '';
      var trailing = raw.slice(start + core.length);
      var existingId = state.nodeIds.get(node);
      if (existingId != null) {
        var entry = state.nodes.get(existingId);
        if (!entry) {
          state.nodeIds.delete(node);
          continue;
        }
        if (entry.translated && node.nodeValue === entry.applied) continue;
        if (entry.raw === raw && !entry.translated && !entry.invalidated) continue;
        if (core.length > 5000 || total - entry.raw.trim().length + core.length > 60000) {
          total = Math.max(0, total - entry.raw.trim().length);
          state.nodes.delete(existingId);
          state.nodeIds.delete(node);
          removedIds.push(existingId);
          truncated = true;
          continue;
        }
        total = Math.max(0, total - entry.raw.trim().length) + core.length;
        entry.raw = raw;
        entry.leading = leading;
        entry.trailing = trailing;
        entry.text = core;
        entry.translated = false;
        entry.applied = null;
        entry.version++;
        entry.invalidated = false;
      } else {
        if (core.length > 5000) { truncated = true; continue; }
        if (state.nodes.size >= 1200 || total + core.length > 60000) { truncated = true; break; }
        var id = state.nextId++;
        state.nodes.set(id, {
          node: node,
          raw: raw,
          leading: leading,
          trailing: trailing,
          element: parent,
          translated: false,
          applied: null,
          version: 1,
        });
        state.nodeIds.set(node, id);
        existingId = id;
        total += core.length;
      }
      items.push({ id: existingId, text: core, visible: isNearViewport(parent), version: state.nodes.get(existingId).version });
    }
    state.replaceTotal = total;
    return JSON.stringify({ items: items, total: total, truncated: truncated, removedIds: removedIds });
  };

  window.__pureTranslateRestore = function () {
    if (state.replaceObserver) {
      state.replaceObserver.disconnect();
      state.replaceObserver = null;
    }
    state.nodes.forEach(function (entry) {
      // Never roll back a node the site changed after we wrote its translation, and never
      // touch a node that was collected but not translated.
      if (entry.translated && entry.node && entry.node.nodeValue === entry.applied) {
        entry.node.nodeValue = entry.raw;
      }
    });
    state.nodes.clear();
    state.nodeIds.clear();
    state.replaceTotal = 0;
    state.replaceDirty = false;
    state.replaceActive = false;
    state.translated = false;
    return true;
  };

  window.__pureTranslateTranslatedMode = function () {
    if (state.compareActive || state.compareTranslated) return 'compare';
    if (state.replaceActive || state.translated) return 'replace';
    return 'none';
  };

  window.__pureTranslateSelection = function () {
    var selection = window.getSelection();
    return selection ? String(selection).trim() : '';
  };
})();
