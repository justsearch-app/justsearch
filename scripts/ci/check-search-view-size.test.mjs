#!/usr/bin/env node
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { SEARCH_VIEW, MAX_LINES, checkSearchViewSize } from './check-search-view-size.mjs';
import { parseUiWebGateCommands } from './run-ui-web-gates.mjs';

assert.equal(checkSearchViewSize('line\n'.repeat(MAX_LINES)).failure, null);
assert.equal(checkSearchViewSize('line\n'.repeat(MAX_LINES - 1)).failure, null);
assert.match(checkSearchViewSize('line\n'.repeat(MAX_LINES + 1)).failure, /Move code out rather than raise the number/);
assert.equal(checkSearchViewSize('line\nlast').lines, 2);

const root = mkdtempSync(join(tmpdir(), 'search-view-size-'));
try {
  const script = fileURLToPath(new URL('./check-search-view-size.mjs', import.meta.url));
  const run = () => spawnSync(process.execPath, [script], { cwd: root, encoding: 'utf8' });
  assert.equal(run().status, 1, 'missing source fails closed');
  mkdirSync(dirname(join(root, SEARCH_VIEW)), { recursive: true });
  writeFileSync(join(root, SEARCH_VIEW), 'line\n'.repeat(MAX_LINES));
  assert.equal(run().status, 0, 'the ceiling passes');
  writeFileSync(join(root, SEARCH_VIEW), 'line\n'.repeat(MAX_LINES + 1));
  const result = run();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Move code out rather than raise the number/);
  const register = JSON.parse(readFileSync(new URL('../../governance/consult-register.v1.json', import.meta.url), 'utf8'));
  const commands = parseUiWebGateCommands(register);
  for (const name of ['check-search-view-size.mjs', 'check-search-view-size.test.mjs']) {
    assert.ok(commands.some(command => command.includes(`scripts/ci/${name}`)), `${name} stays in the ui-web gate recipe`);
  }
} finally {
  rmSync(root, { recursive: true, force: true });
}
console.log('search-view-size self-test OK (boundaries, CLI failure/success and recipe bindings).');
