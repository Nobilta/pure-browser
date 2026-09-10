const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { parseHTML } = require('linkedom');
const script = fs.readFileSync(new URL('../app/src/main/assets/reader-extract.js', `file://${__filename}`), 'utf8');
const paragraph = '这是一段用来验证阅读提取的中文正文，应该保留完整段落、标点和文档顺序。读者可以离线继续阅读，不受导航栏和广告文字干扰。';
function extract(html) {
  const { document } = parseHTML('<html><head><title>Fixture</title></head><body>' + html + '</body></html>');
  Object.defineProperty(document, 'baseURI', { value: 'https://example.test/docs/page' });
  return JSON.parse(JSON.stringify(vm.runInNewContext(script, { document, URL })));
}
test('Chinese news keeps body and rejects link-heavy menus', () => {
  const result = extract(`<main>${'<a href="/menu">导航栏目导航栏目导航栏目</a>'.repeat(100)}</main><article><h1>中文新闻</h1><p>${paragraph.repeat(4)}</p><aside><p>广告</p></aside></article>`);
  assert.equal(result.title, '中文新闻');
  assert.ok(result.blocks.some(b => b.text.includes(paragraph)));
  assert.ok(result.blocks.every(b => b.text !== '广告' && !b.text.includes('导航栏目')));
});
test('documentation preserves code indentation, quotes, safe links and list nesting', () => {
  const result = extract(`<article><h1>Docs</h1><p>${paragraph.repeat(4)}<a href="../guide">Guide</a><a href="javascript:alert(1)">Unsafe</a></p><pre><code>fn main() {\n    println!("你好");\n}</code></pre><blockquote><p>A quoted sentence</p></blockquote><ul><li>Parent<ul><li>Child</li></ul></li></ul></article>`);
  assert.equal(result.blocks.find(b => b.kind === 'code').text, 'fn main() {\n    println!("你好");\n}');
  assert.equal(result.blocks.filter(b => b.text === 'A quoted sentence').length, 1);
  assert.deepEqual(result.blocks.flatMap(b => b.links), [{ text: 'Guide', url: 'https://example.test/guide' }]);
  assert.deepEqual(result.blocks.filter(b => b.kind === 'list').map(b => b.text), ['• Parent', '• Child']);
});
test('forum keeps separate paragraphs, excludes hidden widgets and bounds output', () => {
  const result = extract(`<main><h1>Discussion</h1>${`<p>${paragraph.repeat(3)}</p>`.repeat(1000)}<p hidden>Hidden</p></main>`);
  assert.ok(result.blocks.length <= 600);
  assert.ok(result.blocks.reduce((n, b) => n + b.text.length, 0) <= 150000);
  assert.ok(result.blocks.every(b => !b.text.includes('Hidden')));
});
