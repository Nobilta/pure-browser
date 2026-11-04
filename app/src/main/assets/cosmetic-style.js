(function (w, css) {
    'use strict';
    if (w !== w.top || !/^https?:$/.test(w.location.protocol)) return;
    const key = '__pureCosmeticStyleV1';
    const previous = w[key];
    if (previous && previous.css === css) return;
    if (previous) previous.stop();
    if (!css) { delete w[key]; return; }

    const document = w.document;
    const style = document.createElement('style');
    style.id = '__pureAdStyle';
    style.textContent = css;
    // document-start can run before <html> exists. Mutation observers run before
    // the next paint, so the stylesheet is ready as the parser adds page content.
    function attach() {
        if (!style.isConnected && document.documentElement) {
            (document.head || document.documentElement).appendChild(style);
        }
    }
    const observer = new w.MutationObserver(attach);
    observer.observe(document, { childList: true, subtree: true });
    w[key] = { css, stop() { observer.disconnect(); style.remove(); } };
    attach();
    // CSS also covers elements/classes inserted later; no full-page DOM scan or
    // timer is needed. The observer only repairs a removed/replaced style parent.
})
