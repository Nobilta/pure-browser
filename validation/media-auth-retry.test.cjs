const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/mybrowser/MainActivity.kt'), 'utf8');
const script = source.split('private fun retryMediaElement(')[1].match(/val script = """([\s\S]*?)"""/)[1];
const url = 'https://media.test/video.mp4';

function element(currentSrc, paused) {
  return {
    currentSrc, src: currentSrc, currentTime: 38, paused, loads: 0, plays: 0,
    addEventListener(name, callback, options) {
      assert.equal(name, 'loadedmetadata'); assert.equal(options.once, true);
      this.restore = callback;
    },
    load() { this.loads++; this.currentTime = 0; this.paused = true; },
    play() { this.plays++; this.paused = false; return Promise.resolve(); },
  };
}

for (const paused of [false, true]) {
  test(`media authentication restores position and ${paused ? 'paused' : 'playing'} state without reloading other elements`, () => {
    const unrelated = element('https://other.test/video.mp4', false);
    const target = element(url + '#t=38', paused);
    const result = vm.runInNewContext(script.replace('$encoded', JSON.stringify([url])), {
      document: { querySelectorAll: () => [unrelated, target] },
    });
    assert.equal(result, true);
    assert.equal(unrelated.loads, 0);
    assert.equal(target.loads, 1);
    target.restore();
    assert.equal(target.currentTime, 38);
    assert.equal(target.paused, paused);
    assert.equal(target.plays, paused ? 0 : 1);
  });
}

test('an unmatched resource leaves media untouched for the native retry', () => {
  const unrelated = element('https://other.test/video.mp4', false);
  assert.equal(vm.runInNewContext(script.replace('$encoded', JSON.stringify([url])), {
    document: { querySelectorAll: () => [unrelated] },
  }), false);
  assert.equal(unrelated.loads, 0);
});
