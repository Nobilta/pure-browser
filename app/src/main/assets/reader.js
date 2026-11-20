/*
 * Reader mode controller, paired with readability.js (Apache-2.0).
 *
 * Defines one persistent function on the window; the first injection also carries Readability.
 * A later toggle only calls that function. It hides the original body children and appends the
 * article to the same body, so the page tree (including iframes and their documents) stays
 * alive; only the presentation changes.
 *
 * window.__pureReaderStyle (paper, size, line height) is set by ReaderMode before this payload. The app has
 * already resolved paper "system" to "light" or "dark", so this file never reads the app theme.
 * __pureReaderApply changes the style in place.
 */
window.__pureReaderToggle = function () {
  var state = window.__pureReaderState;
  if (state && state.active) {
    window.visualViewport.removeEventListener("resize", state.updateInset);
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
  // Exiting needs no stylesheet support; only entering does. The capability has been available
  // since Chrome 73, which is below the oldest WebView this app supports.
  if (typeof CSSStyleSheet !== "function" || !document.adoptedStyleSheets) return "empty";
  if (!document.body || typeof Readability === "undefined") return "empty";

  // Paper and size come from the app's reader preferences. Paper is already the final
  // palette name; an unknown value falls back to the light page.
  var paletteFor = function (paper) {
    if (paper === "warm") {
      return { bg: "#f4ecd8", fg: "#2b241d", muted: "#7a6f5f", link: "#8a5a1e",
               codeBg: "#e8dcc4", quote: "#5b5143", border: "#d6c9b2" };
    }
    if (paper === "dark") {
      return { bg: "#121212", fg: "#e6e3de", muted: "#a8a29a", link: "#8ab4f8",
               codeBg: "#1e1e1e", quote: "#b9b3aa", border: "#444444" };
    }
    return { bg: "#f6f6f4", fg: "#1b1b1b", muted: "#777777", link: "#0b6bcb",
             codeBg: "#ececea", quote: "#555555", border: "#cccccc" };
  };
  var normalizeStyle = function (input) {
    var source = input || {};
    var size = typeof source.size === "number" ? source.size : 18;
    var lineHeight = typeof source.lineHeight === "number" ? source.lineHeight : 1.75;
    var paper = source.paper === "warm" || source.paper === "dark" ? source.paper : "light";
    return {
      paper: paper,
      size: Math.max(14, Math.min(26, size)),
      lineHeight: Math.max(1.4, Math.min(2.2, lineHeight))
    };
  };
  var buildCss = function (style) {
    var palette = paletteFor(style.paper);
    var scheme = style.paper === "dark" ? "dark" : "light";
    var bottom = 24 + bottomInset / (window.devicePixelRatio * window.visualViewport.scale);
    return "html{background:" + palette.bg + "!important;color-scheme:" + scheme + "!important}" +
      "body{margin:0!important;background:" + palette.bg + "!important}" +
      // Hide, do not detach: an iframe keeps its nested document only while it stays in the
      // document. display:none removes the old layout; visibility:hidden also covers text nodes.
      "body.__pure_reader_active{display:block!important;visibility:hidden!important}" +
      "body.__pure_reader_active > :not(#__pure_reader){display:none!important}" +
      "body.__pure_reader_active #__pure_reader{visibility:visible!important}" +
      "#__pure_reader{max-width:42em!important;margin:0 auto!important;padding:24px 18px " + bottom + "px!important;" +
      "color:" + palette.fg + "!important;font:" + style.size + "px/" + style.lineHeight +
      " -apple-system,'Noto Serif SC','Songti SC',serif!important;overflow-wrap:break-word!important}" +
      "#__pure_reader h1{font-size:1.6em!important;line-height:1.35!important;margin:0 0 8px!important;color:" + palette.fg + "!important}" +
      "#__pure_reader .reader-meta{font-size:.78em!important;color:" + palette.muted + "!important;margin:0 0 28px!important}" +
      "#__pure_reader .reader-body p{margin:0 0 1em!important}" +
      "#__pure_reader .reader-body img,#__pure_reader .reader-body video{max-width:100%!important;height:auto!important}" +
      "#__pure_reader .reader-body figure{margin:0 0 24px!important}" +
      "#__pure_reader .reader-body a{color:" + palette.link + "!important}" +
      "#__pure_reader .reader-body pre{white-space:pre-wrap!important;overflow-x:auto!important;" +
      "background:" + palette.codeBg + "!important;color:" + palette.fg + "!important;padding:10px!important;border-radius:6px!important}" +
      "#__pure_reader .reader-body code{font-family:ui-monospace,monospace!important}" +
      "#__pure_reader .reader-body blockquote{margin:0 0 20px!important;padding-left:14px!important;" +
      "border-left:3px solid " + palette.border + "!important;color:" + palette.quote + "!important}" +
      "#__pure_reader .reader-body table{border-collapse:collapse!important;max-width:100%!important}" +
      "#__pure_reader .reader-body td,#__pure_reader .reader-body th{border:1px solid " + palette.border + "!important;" +
      "padding:4px 8px!important}";
  };

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
  var savedSheets = document.adoptedStyleSheets.slice();
  var bottomInset = 0;
  var style = normalizeStyle(window.__pureReaderStyle);
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

  var cssText = buildCss(style);

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

  // A constructed stylesheet is not an inline style, so a strict style-src policy cannot block it.
  var adoptedSheet;
  try {
    adoptedSheet = new CSSStyleSheet();
    adoptedSheet.replaceSync(cssText);
    // Replace, not append: page adopted sheets are site CSS and would style the article too.
    document.adoptedStyleSheets = [adoptedSheet];
    var stale = document.getElementById("__pure_reader");
    if (stale && stale.parentNode) stale.parentNode.removeChild(stale);
    savedBody.appendChild(reader);
    savedBody.classList.add("__pure_reader_active");
  } catch (e) {
    if (reader.parentNode) reader.parentNode.removeChild(reader);
    if (document.body) document.body.classList.remove("__pure_reader_active");
    document.adoptedStyleSheets = savedSheets;
    restoreStyles();
    return "empty";
  }
  if (article.title) document.title = article.title;
  window.__pureReaderState = {
    active: true, title: savedTitle, scroll: savedScroll, reader: reader,
    sheets: savedSheets, sheet: adoptedSheet, style: style,
    savedStyles: savedStyles
  };
  var updateInset = function () {
    var current = window.__pureReaderState;
    current.sheet.replaceSync(buildCss(current.style));
  };
  window.__pureReaderState.updateInset = updateInset;
  window.visualViewport.addEventListener("resize", updateInset);
  window.__pureReaderSetBottomInset = function (pixels) {
    if (!window.__pureReaderState || !window.__pureReaderState.active) return;
    bottomInset = pixels;
    updateInset();
  };
  // The app's style bar changes paper, size and line height without re-running Readability.
  window.__pureReaderApply = function (options) {
    var current = window.__pureReaderState;
    if (!current || !current.active) return false;
    current.style = normalizeStyle(Object.assign({
      paper: current.style.paper,
      size: current.style.size,
      lineHeight: current.style.lineHeight
    }, options || {}));
    current.sheet.replaceSync(buildCss(current.style));
    return true;
  };
  window.scrollTo(0, 0);
  return "on";
};
// The payload may land twice if two taps raced the asset read. Entering is idempotent, toggling
// is not, so a second injection must not tear down the article the first one just built.
(window.__pureReaderState && window.__pureReaderState.active) ? "on" : window.__pureReaderToggle();
