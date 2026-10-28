/*
 * Reader mode controller, paired with readability.js (Apache-2.0).
 *
 * Runs as a single evaluateJavascript payload after Readability is defined. It hides the
 * original body children and appends the article to the same body, so the page tree (including
 * iframes and their documents) stays alive; only the presentation changes.
 *
 * window.__pureReaderDark is set by ReaderMode before this payload, so the article follows the
 * app's light/dark theme (including when the page CSS would otherwise force a white page).
 */
(function () {
  var state = window.__pureReaderState;
  if (state && state.active) {
    if (state.cssNode && state.cssNode.parentNode) {
      state.cssNode.parentNode.removeChild(state.cssNode);
    }
    if (state.savedStyles) {
      state.savedStyles.forEach(function (saved) {
        if (saved.el && saved.el.sheet) saved.el.sheet.disabled = saved.disabled;
        else if (saved.el) saved.el.disabled = saved.disabled;
      });
    }
    if (state.sheets && document.adoptedStyleSheets) {
      document.adoptedStyleSheets = state.sheets;
    }
    if (state.reader && state.reader.parentNode) state.reader.parentNode.removeChild(state.reader);
    if (document.body) document.body.classList.remove("__pure_reader_active");
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

  var savedBody = document.body;
  var savedTitle = document.title;
  var savedScroll = window.scrollY || 0;
  var savedSheets = document.adoptedStyleSheets ? document.adoptedStyleSheets.slice() : null;
  var dark = !!window.__pureReaderDark;
  var bg = dark ? "#121212" : "#f6f6f4";
  var fg = dark ? "#e6e3de" : "#1b1b1b";
  var muted = dark ? "#a8a29a" : "#777777";
  var link = dark ? "#8ab4f8" : "#0b6bcb";
  var codeBg = dark ? "#1e1e1e" : "#ececea";
  var quote = dark ? "#b9b3aa" : "#555555";
  var border = dark ? "#444444" : "#cccccc";
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

  // Build the article before touching any document-level state. A Trusted Types policy can
  // reject this innerHTML assignment; if it does, returning "empty" must leave the page exactly
  // as it was, not half-switched with reader CSS applied.
  var reader = document.createElement("main");
  reader.id = "__pure_reader";
  try {
    reader.innerHTML =
      "<h1>" + escape(article.title || document.title) + "</h1>" +
      (meta ? '<p class="reader-meta">' + meta + "</p>" : "") +
      '<div class="reader-body">' + article.content + "</div>";
  } catch (e) {
    return "empty";
  }

  var cssText =
    "html{background:" + bg + "!important;color-scheme:" + (dark ? "dark" : "light") + "!important}" +
    "body{margin:0!important;background:" + bg + "!important}" +
    // Hide, do not detach: an iframe keeps its nested document only while it stays in the
    // document. display:none removes the old layout; visibility:hidden also covers text nodes.
    "body.__pure_reader_active{display:block!important;visibility:hidden!important}" +
    "body.__pure_reader_active > :not(#__pure_reader){display:none!important}" +
    "body.__pure_reader_active #__pure_reader{visibility:visible!important}" +
    "#__pure_reader{max-width:42em!important;margin:0 auto!important;padding:24px 18px 64px!important;" +
    "color:" + fg + "!important;font:18px/1.75 -apple-system,'Noto Serif SC','Songti SC',serif!important;" +
    "overflow-wrap:break-word!important}" +
    "#__pure_reader h1{font-size:1.6em!important;line-height:1.35!important;margin:0 0 8px!important;color:" + fg + "!important}" +
    "#__pure_reader .reader-meta{font-size:.78em!important;color:" + muted + "!important;margin:0 0 28px!important}" +
    "#__pure_reader .reader-body p{margin:0 0 1em!important}" +
    "#__pure_reader .reader-body img,#__pure_reader .reader-body video{max-width:100%!important;height:auto!important}" +
    "#__pure_reader .reader-body figure{margin:0 0 24px!important}" +
    "#__pure_reader .reader-body a{color:" + link + "!important}" +
    "#__pure_reader .reader-body pre{white-space:pre-wrap!important;overflow-x:auto!important;" +
    "background:" + codeBg + "!important;color:" + fg + "!important;padding:10px!important;border-radius:6px!important}" +
    "#__pure_reader .reader-body code{font-family:ui-monospace,monospace!important}" +
    "#__pure_reader .reader-body blockquote{margin:0 0 20px!important;padding-left:14px!important;" +
    "border-left:3px solid " + border + "!important;color:" + quote + "!important}" +
    "#__pure_reader .reader-body table{border-collapse:collapse!important;max-width:100%!important}" +
    "#__pure_reader .reader-body td,#__pure_reader .reader-body th{border:1px solid " + border + "!important;" +
    "padding:4px 8px!important}";

  // Site styles are disabled, not removed: disabling keeps the nodes (and their load state) for
  // the exit path, while removing their ability to override the article. Our own style is added
  // after this snapshot.
  var savedStyles = [];
  Array.prototype.forEach.call(
    document.querySelectorAll('head link[rel~="stylesheet"], head style'),
    function (el) {
      if (el.sheet) {
        savedStyles.push({ el: el, disabled: el.sheet.disabled });
        el.sheet.disabled = true;
      } else if (typeof el.disabled === "boolean") {
        savedStyles.push({ el: el, disabled: el.disabled });
        el.disabled = true;
      }
    },
  );
  var restoreStyles = function () {
    savedStyles.forEach(function (saved) {
      if (saved.el && saved.el.sheet) saved.el.sheet.disabled = saved.disabled;
      else if (saved.el) saved.el.disabled = saved.disabled;
    });
  };

  // A <style> element is an inline style and strict style-src policies block it. A
  // constructed stylesheet is not inline, so it keeps the reader's typography on those
  // sites; the <style> path stays for WebViews without constructable stylesheets.
  var cssNode = null;
  try {
    if (savedSheets && typeof CSSStyleSheet === "function") {
      var sheet = new CSSStyleSheet();
      sheet.replaceSync(cssText);
      // Replace, not append: page adopted sheets are site CSS and would style the article too.
      document.adoptedStyleSheets = [sheet];
    } else {
      cssNode = document.createElement("style");
      cssNode.id = "__pure_reader_css";
      cssNode.textContent = cssText;
      (document.head || document.documentElement).appendChild(cssNode);
    }
    var stale = document.getElementById("__pure_reader");
    if (stale && stale.parentNode) stale.parentNode.removeChild(stale);
    savedBody.appendChild(reader);
    savedBody.classList.add("__pure_reader_active");
  } catch (e) {
    if (reader.parentNode) reader.parentNode.removeChild(reader);
    if (document.body) document.body.classList.remove("__pure_reader_active");
    if (cssNode && cssNode.parentNode) cssNode.parentNode.removeChild(cssNode);
    if (savedSheets && document.adoptedStyleSheets) document.adoptedStyleSheets = savedSheets;
    restoreStyles();
    return "empty";
  }
  if (article.title) document.title = article.title;
  window.__pureReaderState = {
    active: true, title: savedTitle, scroll: savedScroll, reader: reader,
    sheets: savedSheets, cssNode: cssNode, savedStyles: savedStyles
  };
  window.scrollTo(0, 0);
  return "on";
})();
