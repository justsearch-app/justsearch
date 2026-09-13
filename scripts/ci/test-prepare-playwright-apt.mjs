/* SPDX-License-Identifier: Apache-2.0 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { preparePlaywrightAptSources } from './prepare-playwright-apt.mjs';

const chrome = 'https://dl.google.com/linux/chrome-stable/deb';
const ubuntu = 'http://archive.ubuntu.com/ubuntu';
function fixture(t, files) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'playwright-apt-'));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(os.tmpdir()));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  for (const [name, content] of Object.entries(files)) fs.writeFileSync(path.join(directory, name), content);
  return directory;
}

test('disables dedicated list sources and preserves Ubuntu bytes', t => {
  const other = `deb ${ubuntu} noble main\n`;
  const source = `# Chrome\ndeb [arch=amd64 signed-by=/key.gpg] ${chrome} stable main\n`;
  const directory = fixture(t, { 'chrome.list': source, 'ubuntu.list': other });
  assert.deepEqual(preparePlaywrightAptSources(directory), ['chrome.list']);
  assert.equal(fs.readFileSync(path.join(directory, 'chrome.list.disabled'), 'utf8'), source);
  assert.equal(fs.readFileSync(path.join(directory, 'ubuntu.list'), 'utf8'), other);
  assert.deepEqual(preparePlaywrightAptSources(directory), []);
});

test('accepts dedicated deb822 sources with folded URI values', t => {
  const directory = fixture(t, { 'chrome.sources':
    `Types: deb\nURIs: ${chrome}/\n https://dl.google.com/linux/chrome/deb\nSuites: stable\nComponents: main\nSigned-By: /key.gpg\n` });
  assert.deepEqual(preparePlaywrightAptSources(directory), ['chrome.sources']);
});

for (const [name, mixed] of Object.entries({
  'mixed.list': `deb ${chrome} stable main\ndeb ${ubuntu} noble main\n`,
  'mixed.sources': `Types: deb\nURIs: ${chrome} ${ubuntu}\nSuites: stable\n`,
  'multiple.sources': `Types: deb\nURIs: ${chrome}\nSuites: stable\n\nTypes: deb\nURIs: ${ubuntu}\nSuites: noble\n`,
  'duplicate.sources': `Types: deb\nURIs: ${chrome}\nURIs: ${ubuntu}\nSuites: stable\n`,
  'unknown.list': `deb ${chrome} stable main\ninvalid source declaration\n`,
})) {
  test(`refuses ${name} before changing any file`, t => {
    const directory = fixture(t, { 'a-chrome.list': `deb ${chrome} stable main\n`, [name]: mixed });
    assert.throws(() => preparePlaywrightAptSources(directory), /mixed or unrecognized/u);
    assert.equal(fs.readFileSync(path.join(directory, name), 'utf8'), mixed);
    assert.ok(fs.existsSync(path.join(directory, 'a-chrome.list')));
  });
}

test('ignores comments, other distributions, and hostname lookalikes', t => {
  const directory = fixture(t, { 'other.list':
    `# deb ${chrome} stable main\ndeb https://dl.google.com.evil/linux/chrome-stable/deb stable main\ndeb https://dl.google.com/linux/other/deb stable main\n` });
  assert.deepEqual(preparePlaywrightAptSources(directory), []);
});

test('never overwrites an existing disabled source', t => {
  const directory = fixture(t, { 'chrome.list': `deb ${chrome} stable main\n`, 'chrome.list.disabled': 'saved' });
  assert.throws(() => preparePlaywrightAptSources(directory), /backup already exists/u);
  assert.equal(fs.readFileSync(path.join(directory, 'chrome.list.disabled'), 'utf8'), 'saved');
  assert.ok(fs.existsSync(path.join(directory, 'chrome.list')));
});
