import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { driveCase, watchSupervisor } from './adapters/dev-runner.mjs';
import { awaitFixtureExit, fixtureIdentities, removeFixtureDirectory } from './fixture-cleanup.mjs';
import actuator from '../dev/lib/engine-actuator.cjs';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
test('adapter retains artifacts when owned fixture exits cannot be verified',
  { skip: process.platform !== 'win32' && 'Windows fixture identity boundary' }, async () => {
  const base = path.join(repoRoot, 'tmp');
  fs.mkdirSync(base, { recursive: true });
  const workDir = fs.mkdtempSync(path.join(base, 'conformance-cleanup-'));
  fs.writeFileSync(path.join(workDir, 'fake-engine.plan.json'), JSON.stringify({ incarnations: [{ mode: 'honour' }] }));
  const io = { repoRoot, fakeEngine: path.join(repoRoot, 'scripts/supervisor-conformance/fake-engine.mjs'),
    sleep, harnessOverrides: { JUSTSEARCH_SUPERVISOR_HARNESS: '1' },
    readJsonIfPresent(file) { try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; } },
  };
  const run = await driveCase({ testCase: { id: 'verified-fixture-cleanup', startTimeoutMs: 30000 }, policy: {}, io, workDir });
  const lookup = actuator.lookupProcessIdentities;
  try {
    const ready = await watchSupervisor({ statePath: run.statePath, predicate: s => s.state === 'running',
      timeoutMs: 45000, io, seen: run.seen });
    assert.ok(ready, `fixture did not start: ${run.output.stdout}\n${run.output.stderr}`);
    actuator.lookupProcessIdentities = async () => null;
    await assert.rejects(run.cleanup(), /artifacts retained/, 'unverified children forbid successful teardown');
    assert.ok(fs.existsSync(workDir), 'diagnostic evidence survives an inconclusive cleanup');
  } finally {
    actuator.lookupProcessIdentities = lookup;
    // Production cleanup never traverses parent PIDs. The retry uses the same captured births.
    const dir = run.identities ?? path.join(workDir, 'fixture-identities');
    if (fixtureIdentities(dir).length) await awaitFixtureExit(dir, { sleep });
    assert.equal(await Promise.race([run.exited.then(() => true), sleep(5000).then(() => false)]), true,
      'runner exit must be observed before removing its fixture');
    removeFixtureDirectory(workDir);
  }
});

test('identity mismatch is already gone and never authorizes a fixture stop', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conformance-birth-'));
  try {
    fs.writeFileSync(path.join(dir, '42-134358000000000000.json'), JSON.stringify({ pid: 42,
      creationFileTimeUtc: '134358000000000000' }));
    let stops = 0;
    await awaitFixtureExit(dir, { sleep, lookup: async () => new Map([[42,
      { present: true, creationFileTime: '134358000000010000' }]]),
    terminate: async () => { stops++; return { ok: true }; } });
    assert.equal(stops, 0);
  } finally { removeFixtureDirectory(dir); }
});

test('an owned fixture that exits within its grace is never stopped', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conformance-grace-'));
  try {
    fs.writeFileSync(path.join(dir, '42-134358000000000000.json'), JSON.stringify({ pid: 42,
      creationFileTimeUtc: '134358000000000000' }));
    let polls = 0, stops = 0;
    await awaitFixtureExit(dir, { sleep, lookup: async () => new Map([[42, ++polls < 3
      ? { present: true, creationFileTime: '134358000000000000' } : { present: false }]]),
    terminate: async () => { stops++; return { ok: true }; } });
    assert.equal(polls, 3);
    assert.equal(stops, 0, 'an orderly exit inside the grace needs no identity-bound stop');
  } finally { removeFixtureDirectory(dir); }
});
