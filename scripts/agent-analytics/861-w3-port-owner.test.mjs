/**
 * Tempdoc 861 W3 [A3] — `scripts/dev/lib/port-owner.cjs`'s `resolveListenerPidWindows`.
 *
 * This is the ONE shared implementation of "what pid is listening on this port", used by
 * `serve-worktree-fe.cjs` (whose `child.pid` is a `cmd.exe` shim, not the surviving Vite). What
 * is asserted here is the injected-`exec` contract: valid
 * output, no listener, malformed output, and non-zero exits all resolve to a tri-state-safe
 * `{ ok, ... }` — never a thrown exception a caller must remember to catch.
 *
 * A REAL (non-mocked) port -> pid resolution, proven against a disposable process this test owns,
 * is exercised in `861-w3-serve-worktree-fe.test.mjs`'s [A3] integration test — this file is the
 * injected-exec unit layer underneath it.
 *
 * Run with: `node scripts/agent-analytics/861-w3-port-owner.test.mjs`
 */

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { resolveListenerPidWindows } = require('../dev/lib/port-owner.cjs');

let passed = 0;
const failures = [];
function check(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

function fakeExec(stdout, status = 0) {
  return () => ({ status, stdout, stderr: '' });
}

// Every check below exercises the win32 CODE PATH via an injected `exec` — it must never
// actually shell out to PowerShell, so it is safe (and REQUIRED, since this CI job runs on
// ubuntu-latest) to force `platform: 'win32'` explicitly rather than trust `process.platform`.
const WIN32 = { platform: 'win32' };

check('resolves the OwningProcess pid from well-formed JSON', () => {
  const result = resolveListenerPidWindows(5191, { ...WIN32, exec: fakeExec('{"OwningProcess":4321}') });
  assert.deepEqual(result, { ok: true, pid: 4321 });
});

check('non-win32 platform refuses rather than guessing', () => {
  const result = resolveListenerPidWindows(5191, { platform: 'linux', exec: fakeExec('{"OwningProcess":1}') });
  assert.equal(result.ok, false);
  assert.match(result.reason, /win32 only/);
});

for (const bad of [0, -1, 65536, 1.5, 'x', null, undefined]) {
  check(`invalid port ${JSON.stringify(bad)} is refused before any exec`, () => {
    let execCalled = false;
    const result = resolveListenerPidWindows(bad, { ...WIN32, exec: () => { execCalled = true; return { status: 0, stdout: '' }; } });
    assert.equal(result.ok, false);
    assert.equal(execCalled, false, 'must refuse before shelling out to PowerShell');
  });
}

check('nothing listening (empty stdout) is reported, not thrown', () => {
  const result = resolveListenerPidWindows(5191, { ...WIN32, exec: fakeExec('') });
  assert.equal(result.ok, false);
  assert.match(result.reason, /nothing listening/);
});

check('a non-zero PowerShell exit is reported, not thrown', () => {
  const result = resolveListenerPidWindows(5191, { ...WIN32, exec: fakeExec('', 1) });
  assert.equal(result.ok, false);
  assert.match(result.reason, /exited 1/);
});

check('malformed JSON is reported, not thrown', () => {
  const result = resolveListenerPidWindows(5191, { ...WIN32, exec: fakeExec('{not json') });
  assert.equal(result.ok, false);
  assert.match(result.reason, /not JSON/);
});

check('an OwningProcess that is not a usable pid is refused', () => {
  const result = resolveListenerPidWindows(5191, { ...WIN32, exec: fakeExec('{"OwningProcess":"nope"}') });
  assert.equal(result.ok, false);
  assert.match(result.reason, /no usable OwningProcess/);
});

check('a throwing exec is caught and reported, never propagates', () => {
  const result = resolveListenerPidWindows(5191, {
    ...WIN32,
    exec: () => { throw new Error('spawn ENOENT'); },
  });
  assert.equal(result.ok, false);
  assert.match(result.reason, /threw/);
});

check('a null exec result (no process object) is reported, not thrown', () => {
  const result = resolveListenerPidWindows(5191, { ...WIN32, exec: () => null });
  assert.equal(result.ok, false);
  assert.match(result.reason, /with no result/);
});

if (failures.length > 0) {
  console.error(`861-w3-port-owner: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`861-w3-port-owner: all ${passed} checks passed`);
