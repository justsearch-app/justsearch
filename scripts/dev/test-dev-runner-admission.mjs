#!/usr/bin/env node
import './lib/test-fixture-processes.cjs';
//
// Tempdoc 542 §B Layer 4: exercises readActiveOpLeases and the criticality dispatch's
// classification logic. acquireAdmission's full execution path involves spawning processes
// and writing state files; this test covers the pure logic on the op-leases.json read path.
//

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const require = createRequire(import.meta.url);
const devRunnerModule = require(path.join(__dirname, 'dev-runner.cjs'));
const { readActiveOpLeases } = devRunnerModule.__test;

function makeLease(opId, criticality, expiresOffsetMs, opClass = 'test.op') {
  return {
    opId,
    opClass,
    criticality,
    startedAt: new Date(Date.now() - 60_000).toISOString(),
    expectedDurationSec: 600,
    expiresAt: new Date(Date.now() + expiresOffsetMs).toISOString(),
    heartbeatAt: null,
    originProcess: 'head',
    holder: { source: 'head', agentSessionId: null },
    metadata: {},
  };
}

async function withTempDoc(content, fn) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-runner-admission-'));
  const file = path.join(tmp, 'op-leases.json');
  try {
    if (content !== null) {
      fs.writeFileSync(file, JSON.stringify(content));
    }
    await fn(file);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

async function missingFileReturnsEmpty() {
  await withTempDoc(null, async (file) => {
    const r = await readActiveOpLeases(file);
    assert.equal(r.entries.length, 0);
    assert.equal(r.byCriticality.mustComplete.length, 0);
    assert.equal(r.byCriticality.unsafeToInterrupt.length, 0);
    assert.equal(r.byCriticality.interruptibleWithLoss.length, 0);
  });
}

async function expiredEntriesAreFilteredOut() {
  const doc = {
    schema: 'op-leases.v1',
    opLeases: [
      makeLease('expired-id', 'MUST_COMPLETE', -10_000),
      makeLease('fresh-id', 'MUST_COMPLETE', 600_000),
    ],
  };
  await withTempDoc(doc, async (file) => {
    const r = await readActiveOpLeases(file);
    assert.equal(r.entries.length, 1);
    assert.equal(r.entries[0].opId, 'fresh-id');
    assert.equal(r.byCriticality.mustComplete.length, 1);
  });
}

async function classifiesAllFourCriticalitiesCorrectly() {
  const doc = {
    schema: 'op-leases.v1',
    opLeases: [
      makeLease('a', 'MUST_COMPLETE', 600_000, 'indexing.migration'),
      makeLease('b', 'UNSAFE_TO_INTERRUPT', 600_000, 'corruption.recovery'),
      makeLease('c', 'INTERRUPTIBLE_WITH_LOSS', 600_000, 'indexing.ingest'),
      makeLease('d', 'INTERRUPTIBLE', 600_000, 'status.poll'),
    ],
  };
  await withTempDoc(doc, async (file) => {
    const r = await readActiveOpLeases(file);
    assert.equal(r.entries.length, 4);
    assert.equal(r.byCriticality.mustComplete.length, 1);
    assert.equal(r.byCriticality.mustComplete[0].opId, 'a');
    assert.equal(r.byCriticality.unsafeToInterrupt.length, 1);
    assert.equal(r.byCriticality.unsafeToInterrupt[0].opId, 'b');
    assert.equal(r.byCriticality.interruptibleWithLoss.length, 1);
    assert.equal(r.byCriticality.interruptibleWithLoss[0].opId, 'c');
    // INTERRUPTIBLE is not separately classified — it has no admission impact, just
    // contributes to entries[].
  });
}

async function malformedDocReturnsEmpty() {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'dev-runner-admission-malformed-'));
  const file = path.join(tmp, 'op-leases.json');
  try {
    fs.writeFileSync(file, 'this is not json');
    // readJsonIfExists swallows parse errors and returns null → readActiveOpLeases yields empty.
    const r = await readActiveOpLeases(file);
    assert.equal(r.entries.length, 0);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

async function emptyOpLeasesArrayReturnsEmpty() {
  const doc = { schema: 'op-leases.v1', opLeases: [] };
  await withTempDoc(doc, async (file) => {
    const r = await readActiveOpLeases(file);
    assert.equal(r.entries.length, 0);
    assert.equal(r.byCriticality.mustComplete.length, 0);
  });
}

async function entryMissingExpiresAtIsFiltered() {
  const doc = {
    schema: 'op-leases.v1',
    opLeases: [
      { ...makeLease('no-expiry', 'MUST_COMPLETE', 600_000), expiresAt: null },
      makeLease('valid', 'MUST_COMPLETE', 600_000),
    ],
  };
  await withTempDoc(doc, async (file) => {
    const r = await readActiveOpLeases(file);
    assert.equal(r.entries.length, 1);
    assert.equal(r.entries[0].opId, 'valid');
  });
}

const tests = [
  ['missingFileReturnsEmpty', missingFileReturnsEmpty],
  ['expiredEntriesAreFilteredOut', expiredEntriesAreFilteredOut],
  ['classifiesAllFourCriticalitiesCorrectly', classifiesAllFourCriticalitiesCorrectly],
  ['malformedDocReturnsEmpty', malformedDocReturnsEmpty],
  ['emptyOpLeasesArrayReturnsEmpty', emptyOpLeasesArrayReturnsEmpty],
  ['entryMissingExpiresAtIsFiltered', entryMissingExpiresAtIsFiltered],
];

async function main() {
  let pass = 0;
  let fail = 0;
  for (const [name, fn] of tests) {
    try {
      await fn();
      console.log(`  PASS  ${name}`);
      pass++;
    } catch (e) {
      console.error(`  FAIL  ${name}: ${e.message}`);
      console.error(e.stack);
      fail++;
    }
  }
  console.log(`test-dev-runner-admission: ${pass} passed, ${fail} failed`);
  process.exit(fail === 0 ? 0 : 1);
}

await main();
