import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));

function nonLoopbackHostFlags(source) {
  return [...source.matchAll(/--host(?=$|[\s"'`,=])(?:["']\s*,\s*["']?|=\s*["']?|\s+["']?)?([^\s"'`,;\\]+)?/g)]
    .filter(match => !['127.0.0.1', 'localhost', '::1', '[::1]'].includes(match[1]))
    .map(match => ({ line: source.slice(0, match.index).split('\n').length, host: match[1] ?? '(bare)' }));
}

test('V1: all shipped Vite launchers keep Host overrides local', () => {
  // Check command strings and source argv arrays, including new launchers added later.
  for (const source of ['vite --host --port 5173', 'vite --host', 'vite --host=0.0.0.0',
    '[viteEntry, "--host", "--port", "5173"]']) {
    assert.equal(nonLoopbackHostFlags(source).length, 1, source);
  }
  for (const source of ['vite --host 127.0.0.1', 'vite --host="127.0.0.1"',
    '[viteEntry, "--host", "127.0.0.1", "--port", "5173"]']) {
    assert.deepEqual(nonLoopbackHostFlags(source), [], source);
  }
  const listed = spawnSync('git', ['grep', '-I', '-l', '-z', '-F', '--untracked', '--exclude-standard', '--', '--host'], {
    cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024,
  });
  assert.ok(listed.status === 0 || listed.status === 1, listed.stderr);
  const violations = [];
  for (const file of new Set(listed.stdout.split('\0').filter(Boolean))) {
    // Fixtures deliberately exercise wider binds. Recorded historical evidence is not a launcher.
    if (/(?:^|\/)(?:tests?|fixtures|__fixtures__|__snapshots__|evidence)(?:\/|$)|\.(?:test|spec)\.|(?:^|\/)test-[^/]+\.(?:mjs|cjs|js|ts|py)$/.test(file)) continue;
    if (/^(?:gates\/|docs\/tempdocs\/|scripts\/jseval\/\d)/.test(file)) continue;
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    if (!/\b(?:vite|viteEntry|vite_entry|ui-web|ui_web)\b|\brun\s+dev\b/i.test(source)) continue;
    for (const violation of nonLoopbackHostFlags(source)) violations.push({ file, ...violation });
  }
  assert.deepEqual(violations, [], 'shipped Vite commands must never override loopback with bare --host or a wider address');
});

test('V4: Tauri dev URL and launch host both use explicit IPv4 loopback', () => {
  const tauri = JSON.parse(fs.readFileSync(path.join(root, 'modules/shell/src-tauri/tauri.conf.json'), 'utf8'));
  assert.deepEqual({
    host: /--host(?:=|\s+)(\S+)/.exec(tauri.build.beforeDevCommand)?.[1],
    url: tauri.build.devUrl,
  }, { host: '127.0.0.1', url: 'http://127.0.0.1:5173' });
});

