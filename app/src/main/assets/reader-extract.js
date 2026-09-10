(function () {
  'use strict';
  var ignored = 'nav,header,footer,aside,form,button,script,style,[hidden],[aria-hidden="true"],.ad,.advertisement,.sidebar';
  function visible(node) {
    return !node.closest(ignored) && !/display\s*:\s*none|visibility\s*:\s*hidden/i.test(node.getAttribute('style') || '');
  }
  function plain(node) { return (node.textContent || '').replace(/\s+/g, ' ').trim(); }
  function score(node) {
    var text = plain(node).slice(0, 200000), linkText = 0;
    var links = node.querySelectorAll('a');
    for (var i = 0; i < links.length && i < 1000; i++) linkText += plain(links[i]).length;
    var paragraphs = node.querySelectorAll('p,pre,blockquote');
    return Math.max(0, text.length - linkText * 2) + Math.min(paragraphs.length, 100) * 40 +
      ((text.match(/[。！？；，,.!?;]/g) || []).length * 3);
  }
  var roots = Array.prototype.slice.call(document.querySelectorAll('article,main,[role="main"],.post-content,.entry-content,.article-content,.markdown-body,.message-body'), 0, 50)
    .filter(visible);
  var root = roots.sort(function (a, b) { return score(b) - score(a); })[0] || document.body;
  if (!root) return null;
  var heading = root.querySelector('h1');
  var title = (heading && visible(heading) ? plain(heading) : document.title) || '';
  title = title.trim().slice(0, 512);
  var nodes = root.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,pre,blockquote');
  var blocks = [], total = 0, emitted = new Set();
  for (var i = 0; i < nodes.length && i < 20000 && blocks.length < 600 && total < 150000; i++) {
    var node = nodes[i];
    if (!visible(node)) continue;
    var parent = node.parentElement, included = false;
    while (parent && parent !== root) { if (emitted.has(parent)) { included = true; break; } parent = parent.parentElement; }
    if (included) continue;
    var tag = node.tagName.toUpperCase(), code = tag === 'PRE';
    var content = node;
    if (tag === 'LI' && node.querySelector('ul,ol')) {
      content = node.cloneNode(true);
      Array.prototype.forEach.call(content.querySelectorAll('ul,ol'), function (list) { list.remove(); });
    }
    var text = code ? (content.textContent || '').replace(/\r\n?/g, '\n').replace(/\u00a0/g, ' ').replace(/^\n|\n$/g, '') : plain(content);
    if (!text.trim()) continue;
    if (tag === 'LI') text = '• ' + text;
    text = text.slice(0, Math.min(10000, 150000 - total));
    var links = [], seen = new Set(), anchors = content.querySelectorAll('a[href]');
    for (var j = 0; j < anchors.length && links.length < 12; j++) {
      try {
        var url = new URL(anchors[j].getAttribute('href'), document.baseURI);
        if (!/^https?:$/.test(url.protocol) || url.href.length > 8192 || seen.has(url.href)) continue;
        seen.add(url.href); links.push({ text: plain(anchors[j]).slice(0, 256) || url.hostname, url: url.href });
      } catch (_) { /* Invalid and executable links remain non-interactive text. */ }
    }
    blocks.push({ text: text, heading: /^H[1-6]$/.test(tag), kind: code ? 'code' : tag === 'BLOCKQUOTE' ? 'quote' : tag === 'LI' ? 'list' : 'paragraph', links: links });
    // Nested list entries are independent paragraphs, unlike a paragraph inside a quote.
    if (tag !== 'LI') emitted.add(node);
    total += text.length;
  }
  if (total < 200 || blocks.length < 2) return null;
  return { title: title, blocks: blocks };
})()
