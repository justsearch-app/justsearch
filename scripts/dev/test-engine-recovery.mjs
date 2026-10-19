import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
const require = createRequire(import.meta.url);
const { createSupervisionLoop, loadPolicy } = require('./lib/engine-supervisor.cjs');

function fixture({ failFirst = false, crashFirstFour = false } = {}) {
  let now = 0, n = 0, exited = false, pendingExit = null, spawnedAt = 0;
  const records = [], ports = [];
  const actor = {
    reconcile: async () => {},
    spawnEngine({ apiPort }) {
      ports.push(apiPort);
      spawnedAt = now;
      n++;
      if (failFirst && n === 1) throw new Error('Java executable missing: C:/Name With Spaces/java.exe');
      exited = false;
      return { pid: 100 + n };
    },
    pollExit() { if (!pendingExit && !exited && crashFirstFour && n <= 4 && now - spawnedAt >= 100) pendingExit = { code: 1, pid: 100 + n }; const value = pendingExit; pendingExit = null; if (value) exited = true; return value; },
    exitObserved: () => exited,
    recordExit: async () => null,
    recordIntent: async () => {},
    writeShutdownRequest() { pendingExit = { code: 0, pid: 100 + n }; },
    postShutdown: async () => { pendingExit = { code: 0, pid: 100 + n }; return { ok: true }; },
    forceKill: async () => { pendingExit = { code: 0, pid: 100 + n }; },
    wake() {},
    waitOrWake: async ms => { now += ms; await new Promise(resolve => setImmediate(resolve)); },
  };
  const loop = createSupervisionLoop({ actuator: actor,
    policy: { ...loadPolicy(), exhaustedRetryDelayMs: 200, cooldownIncrementMs: 0, maxCooldownMs: 0, hangPollIntervalMs: 100, stabilityWindowMs: 500 },
    io: { readManifest: () => ({ apiPort: 8000 + n, instanceId: `instance-${n}` }), readRawManifest: () => null,
      probeHealth: async () => true, probeAlive: async () => true, fetchStatus: async () => ({}),
      essentialReadyEpoch: () => 'epoch', clearStaleDiscoveryFiles() {}, waitForRelease: async () => ({ released: true }) },
    timeouts: { initialPortMs: 1000, initialReadyMs: 1000 }, monotonic: () => now,
    publish: async state => { records.push(state); },
  });
  return { loop, records, ports, spawnCount: () => n, exitClean: () => { pendingExit = { code: 0, pid: 101 }; } };
}
async function until(predicate) {
  for (let i = 0; i < 10000; i++) { if (predicate()) return; await new Promise(resolve => setImmediate(resolve)); }
  throw new Error('Fixture did not reach expected state');
}

test('E6: a first spawn failure retains the actual missing-Java obstacle', async () => {
  const f = fixture({ failFirst: true });
  const result = await f.loop.run();
  assert.equal(result.state, 'exhausted');
  assert.match(f.records.at(-1).reason, /Java executable missing: C:\/Name With Spaces\/java.exe/);
});

test('E3/E5: terminal manual restart has one owner and quit wins before admission', async () => {
  const f = fixture();
  f.exitClean();
  await f.loop.run();
  assert.equal(typeof f.loop.restartEngine, 'function');
  f.loop.restartEngine();
  assert.throws(() => f.loop.restartEngine(), /already/);
  await f.loop.hostQuit();
  assert.equal(f.spawnCount(), 1, 'quit before replacement admission must not spawn');
  assert.throws(() => f.loop.restartEngine(), /Quit/);
});

test('E5: an update hold refuses terminal restart and initial quit admits no process', async () => {
  const f = fixture();
  f.exitClean();
  await f.loop.run();
  f.loop.setReplacementHold(true);
  assert.throws(() => f.loop.restartEngine(), /update/i);
  assert.equal(f.spawnCount(), 1);

});

test('E5: quit before the first admission starts no Engine', async () => {
  const fresh = fixture();
  const stopped = fresh.loop.hostQuit();
  await fresh.loop.run();
  await stopped;
  assert.equal(fresh.spawnCount(), 0);
});

test('E3: restarting a terminal actor reaches a different binding without another supervisor', async () => {
  const f = fixture();
  f.exitClean();
  await f.loop.run();
  f.loop.restartEngine();
  await until(() => f.records.at(-1)?.state === 'running');
  assert.equal(f.records.at(-1).incarnation, 2);
  assert.equal(f.records.at(-1).apiPort, 8002);
  assert.equal(f.records.at(-1).restartCount, 0);
  await f.loop.hostQuit();
  assert.equal(f.spawnCount(), 2);
});


test('E18: headless restart instructions exclude locked profiles and describe shell-independent quarantine', () => {
  const docs = fs.readFileSync(new URL('../../docs/how-to/headless-runtime.md', import.meta.url), 'utf8');
  assert.match(docs, /Restart=on-failure/);
  assert.match(docs, /RestartPreventExitStatus=2/);
  assert.match(docs, /\$engineExit -eq 2/);
  assert.match(docs, /no desktop supervisor/);
  assert.match(docs, /quarantine.*inside the Engine/);
});

test('E4: the 30-minute retry delay is overridable only by a flagged harness', () => {
  assert.equal(loadPolicy({ env: {} }).exhaustedRetryDelayMs, 1800000);
  const override = { JUSTSEARCH_SUPERVISOR_EXHAUSTED_RETRY_DELAY_MS: '1' };
  assert.equal(loadPolicy({ env: override }).exhaustedRetryDelayMs, 1800000);
  assert.equal(loadPolicy({ env: { ...override, JUSTSEARCH_SUPERVISOR_HARNESS: '1' } }).exhaustedRetryDelayMs, 1);
});

test('E19: the exhausting hang remains visible even when its ordered close is not charged again', () => {
  const { recoveryData } = require('./lib/engine-recovery.cjs');
  const { createLedgerWriter } = require('./lib/exit-ledger.cjs');
  const profile = fs.mkdtempSync(`${os.tmpdir()}/hang-count-`);
  try {
    const writer = createLedgerWriter({ ledgerDir: `${profile}/runtime/exit-ledger`, family: 'engine',
      writer: { role: 'dev-runner', pid: process.pid, startedAt: Date.now() } });
    writer.append('engine-exit', { incarnation: { session: 'test', n: 4 }, code: 0,
      exitClass: 'REQUESTED', contract: { exitClass: 'TRANSIENT', counted: false } });
    writer.append('engine-exit', { incarnation: { session: 'test', n: 5 }, code: 0,
      exitClass: 'REQUESTED', contract: { exitClass: 'REQUESTED', counted: false } });
    assert.equal(recoveryData(profile).recentExits.count, 1);
  } finally { fs.rmSync(profile, { recursive: true, force: true }); }
});


test('E4: a delayed generation passes a numeric requested port to the real spawn boundary', async () => {
  const f = fixture({ crashFirstFour: true });
  const done = f.loop.run();
  await until(() => f.records.at(-1)?.state === 'running' && f.records.at(-1)?.incarnation === 5);
  const ports = [...f.ports];
  await f.loop.hostQuit();
  await done;
  assert.equal(ports[4], 0, 'an exhausted generation must not pass null as JUSTSEARCH_API_PORT');
  assert(ports.every(Number.isInteger));
});
