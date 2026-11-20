// ==UserScript==
// @name Pure resource fixture
// @namespace pure.validation
// @version 1.0
// @match http://127.0.0.1/feature-fixture.html*
// @exclude *excluded=1*
// @run-at document-end
// @resource style http://127.0.0.1:8875/resource-style.css
// @resource picture http://127.0.0.1:8875/context-image.png
// @grant GM_getResourceText
// @grant GM.getResourceUrl
// @grant GM_info
// ==/UserScript==
(async function () {
  const root = document.documentElement.dataset;
  root.resourceText = GM_getResourceText('style');
  const image = new Uint8Array(await (await fetch(await GM.getResourceUrl('picture'))).arrayBuffer());
  root.resourceBytes = String(image.length);
  root.resourceHeader = Array.from(image.slice(0, 8)).join(',');
  root.browserVersion = GM_info.version;
})();
