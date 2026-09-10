(function () {
  'use strict';
  var roots = Array.prototype.slice.call(document.querySelectorAll('article,main,[role="main"]'), 0, 20);
  var root = roots.sort(function (a, b) { return b.textContent.length - a.textContent.length; })[0] || document.body;
  if (!root) return null;
  var heading = root.querySelector('h1');
  var title = (heading && !heading.closest('nav,header,footer,aside') ? heading.textContent : document.title) || '';
  title = title.replace(/\s+/g, ' ').trim().slice(0, 512);
  var nodes = root.querySelectorAll('h1,h2,h3,h4,p,li,pre,blockquote');
  var blocks = [], total = 0, previous = null;
  for (var i = 0; i < nodes.length && blocks.length < 600 && total < 150000; i++) {
    var node = nodes[i];
    if (node.closest('nav,header,footer,aside,form,button,script,style,[hidden],[aria-hidden="true"]') ||
        (previous && previous.contains(node))) continue;
    var text = (node.textContent || '').replace(/\s+/g, ' ').trim();
    if (!text) continue;
    text = text.slice(0, Math.min(10000, 150000 - total));
    blocks.push({ text: text, heading: /^H[1-4]$/.test(node.tagName) });
    previous = node;
    total += text.length;
  }
  if (total < 200 || blocks.length < 2) return null;
  return { title: title, blocks: blocks };
})()
