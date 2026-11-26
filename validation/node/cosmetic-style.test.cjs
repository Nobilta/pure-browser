const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require('jsdom');
const source = fs.readFileSync(require('node:path').join(__dirname, '../../app/src/main/assets/cosmetic-style.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

test('adopted filtering replaces and removes only its own sheet', () => {
    const dom = new JSDOM('<div class="ad"></div>', { url: 'https://example.com/', runScripts: 'outside-only' });
    const w = dom.window;
    // jsdom has CSS rules but no adopted-sheet rendering; verify ownership here and CSP in Chromium.
    w.CSSStyleSheet.prototype.replaceSync = function (css) { this.insertRule(css); };
    const siteSheet = new w.CSSStyleSheet();
    w.document.adoptedStyleSheets = [siteSheet];
    const apply = w.eval(source);
    try {
        apply(w, '.ad{display:none!important}');
        const filtering = w.document.adoptedStyleSheets[1];
        assert.ok(filtering);
        assert.equal(w.document.querySelector('#__pureAdStyle'), null);
        apply(w, '.ad{display:none!important}');
        assert.equal(w.document.adoptedStyleSheets.length, 2);
        assert.equal(w.document.adoptedStyleSheets[1], filtering);
        apply(w, '.sponsor{display:none!important}');
        assert.equal(w.document.adoptedStyleSheets[0], siteSheet);
        assert.equal(w.document.adoptedStyleSheets.length, 2);
        assert.notEqual(w.document.adoptedStyleSheets[1], filtering);
        apply(w, '');
        assert.equal(w.document.adoptedStyleSheets.length, 1);
        assert.equal(w.document.adoptedStyleSheets[0], siteSheet);
    } finally { w.close(); }
});

test('early stylesheet waits for html, hides later matches and repairs a replaced head', async () => {
    const dom = new JSDOM('<html><head></head><body><div class="ad">ad</div><main>content</main></body></html>', {
        url: 'https://example.com/', runScripts: 'outside-only',
    });
    const w = dom.window;
    try {
        const root = w.document.documentElement;
        root.remove();
        w.eval(source)(w, '.ad{display:none!important}');
        w.document.appendChild(root);
        await tick();
        assert.equal(w.getComputedStyle(w.document.querySelector('.ad')).display, 'none');
        assert.notEqual(w.getComputedStyle(w.document.querySelector('main')).display, 'none');
        const dynamic = w.document.createElement('div');
        w.document.body.appendChild(dynamic);
        dynamic.className = 'ad';
        assert.equal(w.getComputedStyle(dynamic).display, 'none');
        w.document.head.replaceWith(w.document.createElement('head'));
        await tick();
        assert.equal(w.getComputedStyle(dynamic).display, 'none');
        assert.equal(w.document.querySelectorAll('#__pureAdStyle').length, 1);
    } finally { w.close(); }
});

test('refresh replaces rules and disabling restores content without an observer reinserting CSS', async () => {
    const dom = new JSDOM('<div class="ad"></div><div class="sponsor"></div>', {
        url: 'https://example.com/', runScripts: 'outside-only',
    });
    const w = dom.window, apply = w.eval(source);
    try {
        apply(w, '.ad{display:none!important}');
        apply(w, '.ad{display:none!important}');
        assert.equal(w.document.querySelectorAll('#__pureAdStyle').length, 1);
        apply(w, '.sponsor{display:none!important}');
        assert.notEqual(w.getComputedStyle(w.document.querySelector('.ad')).display, 'none');
        assert.equal(w.getComputedStyle(w.document.querySelector('.sponsor')).display, 'none');
        apply(w, '');
        w.document.body.appendChild(w.document.createElement('aside'));
        await tick();
        assert.equal(w.document.querySelector('#__pureAdStyle'), null);
        assert.notEqual(w.getComputedStyle(w.document.querySelector('.sponsor')).display, 'none');
    } finally { w.close(); }
});
