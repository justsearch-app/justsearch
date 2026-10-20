import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
const require = createRequire(import.meta.url);
const { createSupervisionLoop, loadPolicy } = require('./lib/engine-supervisor.cjs');

function fixture({ failFirst = false, crashFirstFour = false, failAt = 0, neverReady = false, stopOnInitialExhaustion = false } = {}) {
  let now = 0, n = 0, exited = false, pendingExit = null, spawnedAt = 0;
  const records = [], ports = [];
  const actor = {
    reconcile: async () => {},
    spawnEngine({ apiPort }) {
      ports.push(apiPort);
      spawnedAt = now;
      n++;
      if ((failFirst && n === 1) || n === failAt) throw new Error('Java executable missing: C:/Name With Spaces/java.exe');
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
    stopOnInitialExhaustion,
    policy: { ...loadPolicy(), exhaustedRetryDelayMs: 200, cooldownIncrementMs: 0, maxCooldownMs: 0, hangPollIntervalMs: 100, stabilityWindowMs: 500 },
    io: { readManifest: () => ({ apiPort: 8000 + n, instanceId: `instance-${n}` }), readRawManifest: () => null,
      probeHealth: async () => !neverReady, probeAlive: async () => true, fetchStatus: async () => ({}),
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

test('dev-runner admission policy ends initial discovery instead of scheduling a delayed generation', async () => {
  const f = fixture({ crashFirstFour: true, neverReady: true, stopOnInitialExhaustion: true });
  const done = f.loop.run();
  try {
    await until(() => f.records.at(-1)?.state === 'exhausted');
    const exhausted = f.records.at(-1);
    assert.equal(exhausted.retryAt ?? null, null, 'an Engine that never became healthy must not wait for a later retry');
    const outcome = await done;
    assert.equal(outcome.state, 'exhausted');
    assert.equal(outcome.firstReadyDone, false);
    assert.equal(f.spawnCount(), 4);
    await assert.rejects(f.loop.firstReady, error => error.code === 'ENGINE_EXITED_DURING_DISCOVERY');
  } finally {
    await f.loop.hostQuit();
    await done;
  }
});

test('E4/E17: the default supervisor retries after exhaustion without any healthy incarnation', async () => {
  const f = fixture({ crashFirstFour: true, neverReady: true });
  const done = f.loop.run();
  try {
    await until(() => f.records.at(-1)?.state === 'exhausted');
    const exhausted = f.records.at(-1);
    assert.ok(exhausted.retryAt, 'default supervision must publish a retry deadline even before first readiness');
    assert.equal(exhausted.incarnation, 4);
    assert.equal(exhausted.restartCount, 3);
    await until(() => f.records.at(-1)?.state === 'starting' && f.records.at(-1)?.incarnation === 5);
    assert.equal(f.records.at(-1).restartCount, 0);
    assert(f.records.every(state => state.state !== 'running'));
  } finally {
    await f.loop.hostQuit();
    const outcome = await done;
    assert.equal(outcome.firstReadyDone, false);
  }
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
  assert.match(docs, /JAVA_OPTS=-Djustsearch.prod=true/);
  assert.doesNotMatch(docs, /justsearch\.repo\.root/);
  assert.match(docs, /-XX:ErrorFile=.*crashes\/hs_err_pid%%p\.log/);
  assert.doesNotMatch(docs, /-XX:\+HeapDumpOnOutOfMemoryError|-XX:HeapDumpPath=/);
  assert.match(docs, /Headless does not keep heap dumps/);
  assert.match(docs, /-XX:\+ExitOnOutOfMemoryError/);
  assert.match(docs, /WorkingDirectory=\/absolute\/path\/to\/justsearch/);
  assert.match(docs, /ExecStartPre="\/usr\/bin\/mkdir" -p ".*crashes"/);
  assert.match(docs, /ExecStart="\/absolute\/path\/to\/justsearch\/modules\/ui\/build\/install\/ui\/bin\/ui"/);
  const environment = docs.split('\n').filter(line => line.startsWith('Environment='));
  assert.equal(environment.length, 4);
  assert(environment.every(line => line.startsWith('Environment="') && line.endsWith('"')));
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

test('E1: JS spawn preparation retains the oldest and newest heap dumps and all fatal logs', () => {
  const { createEngineActuator } = require('./lib/engine-actuator.cjs');
  const profile = fs.mkdtempSync(`${os.tmpdir()}/heap-retention-`);
  const directory = `${profile}/crashes`;
  fs.mkdirSync(directory);
  try {
    for (let n = 1; n <= 5; n++) {
      const file = `${directory}/heap-${n}.hprof`;
      fs.writeFileSync(file, `dump ${n}`); fs.utimesSync(file, n, n);
    }
    fs.writeFileSync(`${directory}/hs_err_pid1.log`, 'first native crash');
    fs.mkdirSync(`${directory}/not-a-dump.hprof`);
    const actor = createEngineActuator({ dataDir: profile, heapDumpDir: directory, runId: 'retention', policy: loadPolicy(),
      spawnChild() { throw new Error('spawn boundary reached'); } });
    assert.throws(() => actor.spawnEngine({ n: 1, cause: 'INITIAL', apiPort: 0 }), /spawn boundary reached/);
    const dumps = fs.readdirSync(directory, { withFileTypes: true })
      .filter(entry => entry.isFile() && entry.name.endsWith('.hprof')).map(entry => entry.name).sort();
    assert.deepEqual(dumps, ['heap-1.hprof', 'heap-5.hprof']);
    assert.equal(fs.readFileSync(`${directory}/hs_err_pid1.log`, 'utf8'), 'first native crash');
    assert(fs.statSync(`${directory}/not-a-dump.hprof`).isDirectory());
    assert.throws(() => actor.spawnEngine({ n: 2, cause: 'RESTART', apiPort: 0 }), /spawn boundary reached/);
    assert(fs.existsSync(`${directory}/heap-1.hprof`));
    assert(fs.existsSync(`${directory}/heap-5.hprof`));
  } finally { fs.rmSync(profile, { recursive: true, force: true }); }
});

test('E3: manual restart after exhaustion and a refused delayed spawn starts one fresh generation', async () => {
  const f = fixture({ crashFirstFour: true, failAt: 5 });
  const stopped = await f.loop.run();
  assert.equal(stopped.state, 'exhausted');
  assert(f.records.some(state => state.state === 'exhausted' && state.restartCount === 3));
  const finished = f.records.at(-1);
  const previousSpawns = f.spawnCount();
  f.loop.restartEngine();
  assert.throws(() => f.loop.restartEngine(), /already/);
  await until(() => f.records.at(-1)?.state === 'running' && f.records.at(-1)?.incarnation === 6);
  const restarted = f.records.at(-1);
  await f.loop.hostQuit();
  assert.equal(restarted.restartCount, 0);
  assert.match(finished.reason, /restart_failed/);
  assert.equal(previousSpawns, 5);
  assert.equal(restarted.apiPort, 8006);
  assert.equal(restarted.instanceId, 'instance-6');
  assert.equal(f.spawnCount(), 6);
});
