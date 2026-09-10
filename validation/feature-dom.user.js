// ==UserScript==
// @name Pure DOM fixture
// @namespace pure.validation
// @version 1.0
// @match http://127.0.0.1/feature-fixture.html*
// @run-at document-start
// @grant none
// ==/UserScript==
window.__pureStartReady=document.readyState;
