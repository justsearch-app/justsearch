#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
/**
 * Soft-clean must not delete an AUTHORED durable store.
 *
 * The keep set used to be a hand-maintained list that named config/index/models/policy and deleted
 * everything else — including `audit/`, `conversations/`, `memories/`, `feedback/`, every store
 * governance/store-recoverability.v1.json declares AUTHORED (user-authored, unregenerable). The
 * keep set now DERIVES those from that register, with the hand list kept as a floor.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { __test } = require(path.join(__dirname, 'dev-runner.cjs'));
const { cleanDataDir, authoredStoreTopLevelNames } = __test;

const repoRoot = path.resolve(__dirname, '..', '..');
const register = JSON.parse(
  fs.readFileSync(path.join(repoRoot, 'governance', 'store-recoverability.v1.json'), 'utf8'),
);

function seed(dir, entries) {
  for (const entry of entries) {
    if (entry.endsWith('/')) {
      fs.mkdirSync(path.join(dir, entry), { recursive: true });
      fs.writeFileSync(path.join(dir, entry, 'payload.json'), '{}', 'utf8');
    } else {
      fs.writeFileSync(path.join(dir, entry), '{}', 'utf8');
    }
  }
}

async function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'justsearch-soft-clean-'));
  try {
    // --- 1. The derived set matches the register, and is derived rather than restated ---------
    const derived = authoredStoreTopLevelNames();
    const expected = new Set();
    for (const store of register.durableStores) {
      if (store.recoverability !== 'AUTHORED') continue;
      for (const owned of store.ownedPaths) {
        const head = owned.replace(/\\/g, '/').split('/')[0];
        if (head && !head.includes('*')) expected.add(head);
      }
    }
    assert.deepEqual([...derived].sort(), [...expected].sort(),
      'the keep set must be the register\'s AUTHORED rows, not a second hand list');
    assert.ok(derived.has('audit'), 'the audit journal is AUTHORED — the store this bug destroyed');
    assert.ok(derived.has('conversations') && derived.has('memories') && derived.has('feedback'));

    // --- 2. A soft clean preserves every AUTHORED store and still cleans DERIVED ones ---------
    const dataDir = path.join(tempRoot, 'soft');
    fs.mkdirSync(dataDir, { recursive: true });
    const authoredDirs = [...derived].filter((n) => !n.includes('.')).map((n) => `${n}/`);
    seed(dataDir, [
      ...authoredDirs,
      'config/', 'index/', 'ui/',            // hand-list floor: still kept
      'telemetry/',                          // DERIVED: regenerated, still deleted
      'jobs.db',                             // DERIVED
      'runtime/',                            // EPHEMERAL
      'some-scratch-dir/',                   // unregistered: still deleted
    ]);

    await cleanDataDir(dataDir, 'soft');

    const remaining = new Set(fs.readdirSync(dataDir));
    for (const name of derived) {
      if (name.includes('.')) continue;
      assert.ok(remaining.has(name), `soft clean deleted the AUTHORED store "${name}"`);
    }
    for (const name of ['config', 'index', 'ui']) {
      assert.ok(remaining.has(name), `soft clean must still honour the hand-list floor: ${name}`);
    }
    // Negative control: the change ADDS to the keep set, it does not stop cleaning.
    for (const name of ['telemetry', 'jobs.db', 'runtime', 'some-scratch-dir']) {
      assert.ok(!remaining.has(name), `soft clean must still remove "${name}"`);
    }

    // --- 3. `--clean full` (hard) semantics are unchanged ------------------------------------
    const hardDir = path.join(tempRoot, 'hard');
    fs.mkdirSync(hardDir, { recursive: true });
    seed(hardDir, ['ui/', 'audit/', 'index/', 'conversations/']);
    await cleanDataDir(hardDir, 'hard');
    assert.deepEqual(fs.readdirSync(hardDir), ['ui'],
      'hard clean still keeps only ui/ — this change touches the soft path only');

    // --- 4. Fails soft: an unreadable register leaves the floor intact ------------------------
    const missing = authoredStoreTopLevelNames(path.join(tempRoot, 'no-such-register.json'));
    assert.equal(missing.size, 0, 'an unreadable register must degrade to the empty addition');

    console.log('test-dev-runner-soft-clean-keep-set: PASS');
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
