import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
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

test('the fixture identity preload starts no process before the fixture runs', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conformance-preload-'));
  const require = createRequire(import.meta.url);
  const cp = require('node:child_process');
  const saved = { spawn: cp.spawn, spawnSync: cp.spawnSync, execFile: cp.execFile, execFileSync: cp.execFileSync };
  const started = [];
  const before = BigInt(Date.now()) * 10000n + 116444736000000000n;
  try {
    // Answer as an identity query would, so a preload that queries still records an identity.
    cp.spawnSync = (...args) => { started.push(args[0]); return { status: 0, stdout: `${before}\n` }; };
    cp.execFileSync = (...args) => { started.push(args[0]); return `${before}\n`; };
    cp.spawn = cp.execFile = (...args) => { started.push(args[0]); throw new Error('no process expected'); };
    process.env.JUSTSEARCH_CONFORMANCE_IDENTITIES = dir;
    const preload = require.resolve('./fixture-identity.cjs');
    delete require.cache[preload];
    require(preload);
    assert.deepEqual(started, [], 'recording a fixture identity must not start a process');
    const [identity] = fixtureIdentities(dir);
    assert.equal(identity.pid, process.pid);
    assert.ok(BigInt(identity.heldAtFileTimeUtc) >= before, 'the bound is a moment the fixture held its PID');
  } finally {
    Object.assign(cp, saved);
    delete process.env.JUSTSEARCH_CONFORMANCE_IDENTITIES;
    removeFixtureDirectory(dir);
  }
});

const heldAt = 134358000000000000n;
const heldIdentity = dir => fs.writeFileSync(path.join(dir, `42-${heldAt}.json`), JSON.stringify({ pid: 42,
  heldAtFileTimeUtc: heldAt.toString() }));

test('a held-at identity resolves its birth and records it before any stop', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conformance-held-'));
  try {
    heldIdentity(dir);
    const birth = (heldAt - 5_000_000n).toString(); // born 0.5 s before it recorded the bound
    const stops = [];
    let polls = 0;
    await awaitFixtureExit(dir, { sleep, graceMs: 0, lookup: async () => new Map([[42, ++polls < 2
      ? { present: true, creationFileTime: birth } : { present: false }]]),
    terminate: async (pid, creation) => {
      stops.push({ pid, creation, recorded: fixtureIdentities(dir)[0].creationFileTimeUtc });
      return { ok: true, outcome: 'terminated' };
    } });
    assert.deepEqual(stops, [{ pid: 42, creation: birth, recorded: birth }],
      'the stop names the resolved birth, already recorded');
  } finally { removeFixtureDirectory(dir); }
});

test('a process born after the held-at bound reuses the PID and is never stopped', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conformance-reused-'));
  try {
    heldIdentity(dir);
    let stops = 0;
    await awaitFixtureExit(dir, { sleep, graceMs: 0, lookup: async () => new Map([[42,
      { present: true, creationFileTime: (heldAt + 10n).toString() }]]),
    terminate: async () => { stops++; return { ok: true, outcome: 'terminated' }; } });
    assert.equal(stops, 0);
  } finally { removeFixtureDirectory(dir); }
});

test('a birth within the clock margin of the bound is unknown and retains artifacts', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'conformance-margin-'));
  try {
    heldIdentity(dir);
    let stops = 0;
    await assert.rejects(awaitFixtureExit(dir, { sleep, graceMs: 0, lookup: async () => new Map([[42,
      { present: true, creationFileTime: (heldAt - 10_000n).toString() }]]),
    terminate: async () => { stops++; return { ok: true, outcome: 'terminated' }; } }), /artifacts retained/);
    assert.equal(stops, 0, 'an ambiguous birth never authorizes a stop');
  } finally { removeFixtureDirectory(dir); }
});
