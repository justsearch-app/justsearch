#!/usr/bin/env node
/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Offline pins for `check-installer-execution-level.mjs`.
 *
 * Why this file exists: the check was invoked by NOTHING — not the pre-merge table, not any
 * workflow (tempdoc 884 §F row 7; ADR-0024 stopped using it as a probe for exactly that reason).
 * Wiring it into CI without a test would have wired in an unexercised layer, so both halves are
 * pinned here and the check itself now runs in `ci.yml` (config half) and in
 * `build-installer.yml`'s `installer_verify` job (artifact half, against the real setup.exe).
 *
 * The artifact half is the one that cannot be replaced by ADR-0024's `json-path` probe: a change
 * to `modules/shell/src-tauri/nsis/installer-hooks.nsh` can leave `tauri.conf.json` correct while
 * the BUILT installer still requests elevation. Its byte-scan is an honest heuristic that fails
 * CLOSED, and that fail-closed behaviour is what the ambiguity cases below pin — a heuristic that
 * quietly guesses would be worse than no check.
 *
 * Run: node scripts/ci/check-installer-execution-level.test.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { checkInstallerExecutionLevel, findRequestedExecutionLevel, checkInstallerArtifact } from './check-installer-execution-level.mjs';
import { readTauriConfig } from './lib/tauri-config.mjs';

const failures = [];
let passed = 0;

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

const scratch = [];
/** Build a temp repo root holding just the tauri config under test. */
function repoWithConfig(conf) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jsexec-'));
  scratch.push(root);
  const dir = path.join(root, 'modules', 'shell', 'src-tauri');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'tauri.conf.json'), conf, 'utf8');
  return root;
}

// -------------------- config half --------------------

test('platform config merges objects, replaces arrays and removes null fields', () => {
  const files = {
    'modules/shell/src-tauri/tauri.conf.json': JSON.stringify({
      bundle: { icon: ['base.ico'], resources: ['base'], windows: { nsis: { installMode: 'currentUser' } } },
      plugins: { updater: { active: true } },
    }),
    'modules/shell/src-tauri/tauri.windows.conf.json': JSON.stringify({
      bundle: { resources: ['windows'], windows: { nsis: { installerHooks: 'hooks.nsh' } } },
      plugins: { updater: null },
    }),
  };
  assert.deepEqual(readTauriConfig('', 'windows', file => files[file]), {
    bundle: { icon: ['base.ico'], resources: ['windows'],
      windows: { nsis: { installMode: 'currentUser', installerHooks: 'hooks.nsh' } } },
    plugins: {},
  });
});

test('the Windows overlay supplies per-user mode on either host', () => {
  const root = repoWithConfig(JSON.stringify({ bundle: { targets: ['deb'] } }));
  fs.writeFileSync(path.join(root, 'modules/shell/src-tauri/tauri.windows.conf.json'),
    JSON.stringify({ bundle: { windows: { nsis: { installMode: 'currentUser' } } } }));
  assert.equal(checkInstallerExecutionLevel(root).ok, true);
});

for (const overlay of [
  JSON.stringify({ bundle: { windows: { nsis: { installMode: 'perMachine' } } } }),
  JSON.stringify({ bundle: { windows: { nsis: { installMode: null } } } }),
  '{ not json',
]) {
  test(`the Windows overlay cannot bypass elevation checks: ${overlay}`, () => {
    const root = repoWithConfig(JSON.stringify({ bundle: { windows: { nsis: { installMode: 'currentUser' } } } }));
    fs.writeFileSync(path.join(root, 'modules/shell/src-tauri/tauri.windows.conf.json'), overlay);
    assert.equal(checkInstallerExecutionLevel(root).ok, false);
  });
}

test('currentUser install mode passes', () => {
  const root = repoWithConfig(JSON.stringify({ bundle: { windows: { nsis: { installMode: 'currentUser' } } } }));
  assert.equal(checkInstallerExecutionLevel(root).ok, true);
});

test('perMachine install mode fails and says why', () => {
  const root = repoWithConfig(JSON.stringify({ bundle: { windows: { nsis: { installMode: 'perMachine' } } } }));
  const r = checkInstallerExecutionLevel(root);
  assert.equal(r.ok, false);
  assert.match(r.message, /perMachine/);
  assert.match(r.message, /ADR-0024|per-user/);
});

test('a missing installMode fails rather than defaulting', () => {
  const root = repoWithConfig(JSON.stringify({ bundle: { windows: { nsis: {} } } }));
  assert.equal(checkInstallerExecutionLevel(root).ok, false);
});

test('unparseable config fails', () => {
  const root = repoWithConfig('{ not json');
  const r = checkInstallerExecutionLevel(root);
  assert.equal(r.ok, false);
  assert.match(r.message, /not valid JSON/);
});

test('absent config fails rather than being skipped', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jsexec-'));
  scratch.push(root);
  assert.equal(checkInstallerExecutionLevel(root).ok, false);
});

// -------------------- artifact half (byte-scan) --------------------

const pad = (n) => Buffer.alloc(n, 0x41);
const utf8Manifest = (level) =>
  Buffer.concat([
    pad(64),
    Buffer.from(`<requestedExecutionLevel level="${level}" uiAccess="false" />`, 'utf8'),
    pad(64),
  ]);

test('asInvoker in a UTF-8 manifest reads as asInvoker', () => {
  const found = findRequestedExecutionLevel(utf8Manifest('asInvoker'));
  assert.equal(found.ambiguous, false);
  assert.equal(found.level, 'asInvoker');
  assert.equal(found.encoding, 'utf8');
});

test('asInvoker in a UTF-16LE manifest reads as asInvoker', () => {
  const buf = Buffer.concat([
    pad(64),
    Buffer.from('<requestedExecutionLevel level="asInvoker" uiAccess="false" />', 'utf16le'),
    pad(64),
  ]);
  const found = findRequestedExecutionLevel(buf);
  assert.equal(found.ambiguous, false, 'UTF-16LE resource strings must be readable');
  assert.equal(found.level, 'asInvoker');
  assert.equal(found.encoding, 'utf16le');
});

test('requireAdministrator is REJECTED — this is the whole point of the check', () => {
  const found = findRequestedExecutionLevel(utf8Manifest('requireAdministrator'));
  assert.equal(found.ambiguous, false);
  assert.equal(found.level, 'requireAdministrator');
});

test('highestAvailable is REJECTED too', () => {
  const found = findRequestedExecutionLevel(utf8Manifest('highestAvailable'));
  assert.equal(found.level, 'highestAvailable');
});

test('zero markers fails closed (ambiguous), never a pass', () => {
  const found = findRequestedExecutionLevel(pad(4096));
  assert.equal(found.ambiguous, true);
  assert.equal(found.matchCount, 0);
});

test('two markers fails closed (ambiguous), never "take the first"', () => {
  const found = findRequestedExecutionLevel(Buffer.concat([utf8Manifest('asInvoker'), utf8Manifest('requireAdministrator')]));
  assert.equal(found.ambiguous, true);
  assert.equal(found.matchCount, 2);
});

test('a marker with no level="..." after it fails closed', () => {
  const buf = Buffer.concat([pad(32), Buffer.from('requestedExecutionLevel', 'utf8'), pad(1024)]);
  const found = findRequestedExecutionLevel(buf);
  assert.equal(found.ambiguous, true);
});

// -------------------- artifact half, end to end --------------------

function artifactAt(level) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'jsexec-'));
  scratch.push(dir);
  const file = path.join(dir, 'JustSearch_0.0.0_x64-setup.exe');
  fs.writeFileSync(file, utf8Manifest(level));
  return file;
}

test('checkInstallerArtifact passes an asInvoker installer', () => {
  const r = checkInstallerArtifact(artifactAt('asInvoker'));
  assert.equal(r.ok, true);
  assert.match(r.message, /asInvoker/);
});

test('checkInstallerArtifact fails an elevating installer and calls it a release blocker', () => {
  const r = checkInstallerArtifact(artifactAt('requireAdministrator'));
  assert.equal(r.ok, false);
  assert.match(r.message, /requireAdministrator/);
  assert.match(r.message, /release blocker/);
});

test('checkInstallerArtifact fails on a missing file rather than skipping', () => {
  const r = checkInstallerArtifact(path.join(os.tmpdir(), 'definitely-not-here-setup.exe'));
  assert.equal(r.ok, false);
});

for (const dir of scratch) {
  try {
    fs.rmSync(dir, { recursive: true, force: true });
  } catch {
    /* a leftover temp dir is not a test failure */
  }
}

if (failures.length > 0) {
  console.error(`check-installer-execution-level.test FAILED (${failures.length}):`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`check-installer-execution-level.test OK - ${passed} assertions passed.`);
