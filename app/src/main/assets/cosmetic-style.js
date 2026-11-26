(function (w, css) {
    'use strict';
    if (w !== w.top || !/^https?:$/.test(w.location.protocol)) return;
    const key = '__pureCosmeticStyleV1';
    const previous = w[key];
    if (previous && previous.css === css) return;
    if (previous) previous.stop();
    if (!css) { delete w[key]; return; }

    const document = w.document;
    const adopted = 'adoptedStyleSheets' in document &&
        w.CSSStyleSheet && typeof w.CSSStyleSheet.prototype.replaceSync === 'function';
    const sheet = adopted ? new w.CSSStyleSheet() : null;
    const style = adopted ? null : document.createElement('style');
    if (sheet) sheet.replaceSync(css);
    else { style.id = '__pureAdStyle'; style.textContent = css; }
    // document-start can run before <html> exists. Mutation observers run before
    // the next paint, so the stylesheet is ready as the parser adds page content.
    function attach() {
        if (sheet) {
            if (!document.adoptedStyleSheets.includes(sheet)) {
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
            }
        } else if (!style.isConnected && document.documentElement) {
            (document.head || document.documentElement).appendChild(style);
        }
    }
    const observer = new w.MutationObserver(attach);
    observer.observe(document, { childList: true, subtree: true });
    w[key] = { css, stop() {
        observer.disconnect();
        if (sheet) document.adoptedStyleSheets = document.adoptedStyleSheets.filter(item => item !== sheet);
        else style.remove();
    } };
    attach();
    // CSS also covers elements/classes inserted later; no full-page DOM scan or
    // timer is needed. The observer only repairs a removed/replaced style parent.
})
