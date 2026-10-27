/*
 * Reader mode controller, paired with readability.js (Apache-2.0).
 *
 * Runs as a single evaluateJavascript payload after Readability is defined. It replaces the
 * document body with the extracted article and restores the exact previous markup on exit;
 * the saved string lives on the page, so navigating away discards it with the document.
 */
(function () {
  var state = window.__pureReaderState;
  if (state && state.active) {
    document.documentElement.innerHTML = state.html;
    if (state.title) document.title = state.title;
    window.__pureReaderState = null;
    window.scrollTo(0, state.scroll || 0);
    return "off";
  }
  if (!document.body || typeof Readability === "undefined") return "empty";

  var article;
  try {
    article = new Readability(document.cloneNode(true), {
      charThreshold: 200,
      keepClasses: false,
      classesToPreserve: ["caption", "emoji", "hidden", "invisible", "sr-only", "visually-hidden"]
    }).parse();
  } catch (e) {
    return "empty";
  }
  if (!article || !article.content || !article.textContent || article.textContent.length < 200) {
    return "empty";
  }

  var savedHtml = document.documentElement.innerHTML;
  var savedTitle = document.title;
  var savedScroll = window.scrollY || 0;
  var escape = function (value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
    });
  };
  var meta = [article.byline, article.siteName].filter(function (part) {
    return part && String(part).trim();
  }).map(function (part) {
    return escape(part);
  }).join(" · ");

  var css = document.createElement("style");
  css.id = "__pure_reader_css";
  css.textContent =
    "html{background:#f6f6f4!important}body{margin:0!important;background:#f6f6f4!important}" +
    "#__pure_reader{max-width:42em;margin:0 auto;padding:24px 18px 64px;color:#1b1b1b;" +
    "font:18px/1.75 -apple-system,'Noto Serif SC','Songti SC',serif;overflow-wrap:break-word}" +
    "#__pure_reader h1{font-size:1.6em;line-height:1.35;margin:0 0 8px}" +
    "#__pure_reader .reader-meta{font-size:.78em;color:#777;margin:0 0 28px}" +
    "#__pure_reader .reader-body img,#__pure_reader .reader-body video{max-width:100%;height:auto}" +
    "#__pure_reader .reader-body figure{margin:0 0 24px}" +
    "#__pure_reader .reader-body a{color:#0b6bcb}" +
    "#__pure_reader .reader-body pre{white-space:pre-wrap;overflow-x:auto;background:#ececea;padding:10px;border-radius:6px}" +
    "#__pure_reader .reader-body code{font-family:ui-monospace,monospace}" +
    "#__pure_reader .reader-body blockquote{margin:0 0 20px;padding-left:14px;border-left:3px solid #ccc;color:#555}" +
    "#__pure_reader .reader-body table{border-collapse:collapse;max-width:100%}" +
    "#__pure_reader .reader-body td,#__pure_reader .reader-body th{border:1px solid #ccc;padding:4px 8px}";
  document.head.appendChild(css);

  document.body.innerHTML =
    '<main id="__pure_reader"><h1>' + escape(article.title || document.title) + "</h1>" +
    (meta ? '<p class="reader-meta">' + meta + "</p>" : "") +
    '<div class="reader-body">' + article.content + "</div></main>";
  if (article.title) document.title = article.title;
  window.__pureReaderState = { active: true, html: savedHtml, title: savedTitle, scroll: savedScroll };
  window.scrollTo(0, 0);
  return "on";
})();
