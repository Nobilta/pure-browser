// ==UserScript==
// @name Pure storage fixture
// @namespace pure.validation
// @version 1.0
// @description Tests reviewed installation, dependencies and acknowledged persistent storage.
// @match http://127.0.0.1/feature-fixture.html*
// @exclude *excluded=1*
// @run-at document-end
// @noframes
// @require http://127.0.0.1:8875/feature-dependency.js
// @grant GM.getValue
// @grant GM.setValue
// @grant GM.addStyle
// ==/UserScript==
(async function(){
 const count=Number(await GM.getValue('visits',0))+1;
 await GM.setValue('visits',count);
 document.documentElement.dataset.storageCount=String(count);
 document.documentElement.dataset.dependency=pureFixtureDependency;
 document.querySelector('#script-status').textContent='Storage script visit '+count;
 GM.addStyle('#script-status { color: rgb(20, 100, 60) !important; }');
})();
