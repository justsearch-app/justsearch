import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import actuatorLib from './lib/engine-actuator.cjs';
import supervisor from './lib/engine-supervisor.cjs';
import ledger from './lib/exit-ledger.cjs';
import census from './lib/stop-exit-census.cjs';

const policy = supervisor.loadPolicy({ env: {} });
const stamp = '2026-10-08T10:00:00Z';
const birth = census.fileTimeFromInstant(stamp);
const tick = () => new Promise(resolve => setImmediate(resolve));
const tmp = t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'accounting-order-'));
  // Fixtures only write ordinary files; no links or processes are created here.
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
};

test('shutdown delivery waits for the current intent append', async t => {
  const order = [];
  let release;
  const identity = new Promise(resolve => { release = resolve; });
  const child = Object.assign(new EventEmitter(), { pid: 4242 });
  const actuator = actuatorLib.createEngineActuator({ dataDir: tmp(t), runId: 'ordered', policy,
    spawnChild: () => child, lookupIdentities: () => identity, log() {},
    ledger: { append(type) { order.push(type); return true; }, startSession() {} },
    writeRequest() { order.push('request-delivered'); child.emit('exit', 0, null); },
  });
  const loop = supervisor.createSupervisionLoop({ actuator, policy, tickMs: 1,
    timeouts: { initialPortMs: 10000, initialReadyMs: 10000 }, publish: async () => {},
    io: { clearStaleDiscoveryFiles() {}, readManifest: () => null, readRawManifest: () => null },
    hooks: { onSpawned() { void loop.hostQuit(); } },
  });
  const running = loop.run();
  try {
    for (let i = 0; i < 10; i++) await tick();
    assert.deepEqual(order, [], 'identity-delayed intent must precede delivery');
  } finally {
    release(new Map([[4242, { present: true, creationFileTime: birth, executable: process.execPath }]]));
    await running;
  }
  assert.deepEqual(order.slice(0, 3), ['engine-start', 'engine-intent', 'request-delivered']);
});

test('forced termination waits for its intent append', async t => {
  const order = [];
  const actuator = actuatorLib.createEngineActuator({ dataDir: tmp(t), runId: 'forced', policy,
    spawnChild: () => Object.assign(new EventEmitter(), { pid: 4242 }), log() {},
    lookupIdentities: async () => new Map([[4242, { present: true, creationFileTime: birth }]]),
    ledger: { append(type) { order.push(type); return true; }, startSession() {} },
    terminate: async () => { order.push('terminate'); return { ok: true }; },
    fallbackKill() { throw new Error('unexpected fallback'); },
  });
  actuator.spawnEngine({ n: 1, cause: 'INITIAL' });
  await actuator.flush();
  await actuator.forceKill('quit');
  await actuator.flush();
  assert.deepEqual(order, ['engine-start', 'engine-intent', 'terminate']);
});

test('runner job close is accounted only with matching native receipt and stop evidence', t => {
  const dataDir = tmp(t), incarnation = { session: 'job-close', n: 1 };
  const writer = ledger.createLedgerWriter({ ledgerDir: ledger.ledgerDirectory(dataDir), family: 'engine',
    writer: { role: 'dev-runner', pid: 100, startedAt: Date.parse(stamp) } });
  writer.append('engine-start', { incarnation, pid: 4242, creationFileTime: birth, executable: process.execPath });
  const recorder = actuatorLib.createCliEngineRecorder({ dataDir, incarnation });
  recorder.intent({ mode: 'NOW', channel: 'TERMINATE' });
  recorder.exit({ endedBy: 'runner', terminateCalledAt: Date.parse(stamp) + 100,
    runnerJobClose: { pid: 100, creationFileTimeUtc: birth, osCheck: 'GONE', containment: 'LIBUV_JOB' },
    observed: { exitCode: 0, atMs: Date.parse(stamp) + 200 } });
  const records = census.readRunLedger(dataDir);
  // This process is the stop that checked the Engine's PID and birth before its recorder wrote: its
  // writer is bound to that start in memory (round 10), as the stop passes it to its census.
  const stopBindings = [{ writer: recorder.writerIdentity(), incarnation, checked: { pid: 4242, creationFileTimeUtc: birth } }];
  const view = () => census.ledgerView(records, incarnation.session, { stopBindings });
  const input = { runId: incarnation.session, targets: [{ role: 'engine', pid: 4242,
    creationFileTimeUtc: birth, incarnation }], gaps: [], accountedProcesses: [] };
  const output = { processExits: [{ pid: 4242, creationFileTimeUtc: birth, osCreationFileTimeUtc: birth,
    exitCode: 0, atMs: Date.parse(stamp) + 200 }], gaps: [] };
  const result = census.exitResult(input, output, 'fixture', records, view());
  assert.equal(result.exitAccountingGaps.some(g => g.reason === 'ledger_os_disagree'), false);
  assert.equal(result.processExits[0].expectedTermination, true);
  const exit = records.engine.records.find(r => r.type === 'engine-exit').line;
  const proof = exit.runnerJobClose;
  delete exit.runnerJobClose;
  assert.equal(census.exitResult(input, output, 'fixture', records, view()).processExits[0].expectedTermination, false,
    'the runner must be identity-proven gone');
  exit.runnerJobClose = proof;
  delete exit.terminationMechanism;
  assert.equal(census.exitResult(input, output, 'fixture', records, view()).processExits[0].expectedTermination, false,
    'an arbitrary forced zero is not evidence of job closure');
});

test('deny admission never calls stop for a foreign alive dead or unreadable run', async t => {
  const state = tmp(t);
  process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = state;
  const require = createRequire(import.meta.url);
  const { acquireAdmission } = require('./dev-runner.cjs').__test;
  const caller = { sessionId: 'ours', harness: 'claude', owner: { key: 'fixture-ours', pid: 900301,
    creationTime: '134000000000900301', harness: 'claude' }, source: 'fixture' };
  for (const variant of ['alive', 'dead', 'unreadable']) {
    await t.test(variant, async () => {
      fs.mkdirSync(path.join(state, 'runs', variant), { recursive: true });
      fs.writeFileSync(path.join(state, 'runs', variant, 'run.json'), variant === 'unreadable' ? '{bad'
        : JSON.stringify({ runId: variant, pids: { runnerPid: variant === 'alive' ? process.pid : 999999 } }));
      fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify({ runId: variant,
        holder: { agentSessionId: 'foreign' }, lease: { expiresAt: '2001-01-01T00:00:00Z' } }));
      let stops = 0, published = 0;
      const result = await acquireAdmission({ takeover: 'deny', callerIdentity: caller,
        stop: async () => { stops++; return { outcome: 'STOPPED' }; }, publish: async () => { published++; } });
      assert.equal(stops, 0, `${variant}: deny must refuse before any stop`);
      assert.equal(published, 0);
      assert.equal(result.action, 'conflict');
    });
  }
  // Preservation: the owner's own dead run is not a takeover; deny still reclaims it as before.
  await t.test('own dead run', async () => {
    fs.mkdirSync(path.join(state, 'runs', 'own'), { recursive: true });
    fs.writeFileSync(path.join(state, 'runs', 'own', 'run.json'), JSON.stringify({ runId: 'own',
      pids: { runnerPid: 999999 } }));
    fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify({ runId: 'own',
      holder: { agentSessionId: 'ours', owner: caller.owner }, lease: { expiresAt: '2001-01-01T00:00:00Z' } }));
    const dispositions = [];
    const result = await acquireAdmission({ takeover: 'deny', callerIdentity: caller,
      stop: async ({ disposition }) => { dispositions.push(disposition); return { outcome: 'STOPPED' }; },
      publish: async () => {} });
    assert.equal(result.action, 'proceed', JSON.stringify(result));
    assert.deepEqual(dispositions, ['stale_reclaim']);
  });
});

for (const compacted of [false, true]) {
  test(`disjoint historical loss does not contaminate a new run (compacted=${compacted})`, t => {
    const dir = tmp(t);
    const old = Date.parse('2026-10-01T10:00:00Z');
    const writer = ledger.createLedgerWriter({ ledgerDir: dir, family: 'engine', rotates: true,
      writer: { role: 'dev-runner', pid: 101, startedAt: old }, clock: () => old,
      sessionRotateBytes: 400, hardCapBytes: 900, retentionBytes: 1800 });
    // Preload valid old loss receipts to exercise the production compactor at its bound.
    if (compacted) {
      const loss = { schemaVersion: 1, type: 'ledger-gap', seq: 1, at: '2026-10-02T10:00:00Z',
        writer: { role: 'engine', pid: 100, startedAt: '2026-10-01T10:00:00Z' }, reason: 'EVIDENCE_LOST',
        incarnation: { session: 'old', n: 1 }, count: 1, since: '2026-10-01T10:00:00Z', evidenceFrom: '2026-10-01T10:00:00Z',
        evidenceThrough: '2026-10-01T10:00:00Z', scopeComplete: true, affectedSessions: ['old'] };
      fs.writeFileSync(path.join(dir, 'engine.v1.lost.jsonl'), (JSON.stringify(loss) + '\n').repeat(2000));
    }
    // A summary written by the base revision carries no scope fields; its `at` is the eviction time.
    const unscoped = { schemaVersion: 1, type: 'ledger-gap', seq: 1, at: '2026-10-02T10:00:00Z',
      since: '2026-10-02T10:00:00Z', writer: { role: 'engine', pid: 100, startedAt: '2026-10-01T10:00:00Z' },
      reason: 'EVIDENCE_LOST', count: 1, droppedFile: 'engine.v1.9.jsonl', droppedBytes: 512,
      detail: 'archive dropped at retention bound' };
    fs.appendFileSync(path.join(dir, 'engine.v1.lost.jsonl'), JSON.stringify(unscoped) + '\n');
    for (let i = 0; i < 30; i++) {
      writer.append('engine-start', { incarnation: { session: 'old', n: i + 1 },
        pid: 200 + i, creationFileTime: census.fileTimeFromInstant('2026-10-01T10:00:00Z'), executable: process.execPath });
      // A torn line in the oldest archive leaves the evicted range unknown.
      if (i === 0) fs.appendFileSync(path.join(dir, 'engine.v1.jsonl'), '{torn\n');
    }
    const records = ledger.readLedger(dir, 'engine').records;
    const losses = records.filter(r => r.type === 'ledger-gap' && r.line?.reason === 'EVIDENCE_LOST').map(r => r.line);
    assert.ok(losses.some(l => l.evidenceRangeComplete !== true), 'fixture holds a loss with an unknown range');
    const view = { engine: { present: true, records }, children: { present: true, records: [] } };
    // Later evictions can be fully scoped to `old`. A slow disk may put them more than a
    // second after the torn archive was evicted; they do not bound its unknown evidence.
    const unknownLosses = losses.filter(l => !l.incarnation?.session && l.scopeComplete !== true
      && l.evidenceRangeComplete !== true);
    assert.ok(unknownLosses.length > 0, 'fixture holds an unscoped loss with an unknown range');
    const evicted = Math.max(...unknownLosses.map(l => ledger.knownTimestamp(l.at)));
    const current = census.exitTargets({ runId: 'new', startedAt: ledger.formatTimestamp(evicted + 1000), pids: {} },
      null, [], view);
    assert.equal(current.gaps.some(g => g.reason === 'evidence_lost'), false,
      'loss of disjoint old evidence must not create a current-run accounting gap');
    const affected = census.exitTargets({ runId: 'old', startedAt: '2026-10-01T10:00:00Z', pids: {} }, null, [], view);
    assert.ok(affected.gaps.some(g => g.reason === 'evidence_lost'), 'affected run remains incomplete');
    // An unknown range is still relevant to a run that started before its eviction time.
    const during = census.exitTargets({ runId: 'during', startedAt: ledger.formatTimestamp(evicted - 1000), pids: {} },
      null, [], view);
    assert.ok(during.gaps.some(g => g.reason === 'evidence_lost'), 'a run older than the eviction stays incomplete');
  });
}

test('this stop binds its writer to the checked Linux tuple; malformed, foreign and reused identities prove nothing', t => {
  const dataDir = tmp(t), incarnation = { session: 'linux-stop-binding', n: 1 };
  const processIdentity = { kind: 'linux-proc-v1', pid: 4242,
    bootId: '333fd9cf-f547-4117-a537-fc8be3c44466', startTicks: '42' };
  ledger.createLedgerWriter({ ledgerDir: ledger.ledgerDirectory(dataDir), family: 'engine',
    writer: { role: 'dev-runner', pid: 100, startedAt: Date.parse(stamp) } })
    .append('engine-start', { incarnation, pid: 4242, processIdentity, executable: process.execPath });
  const recorder = actuatorLib.createCliEngineRecorder({ dataDir, incarnation });
  recorder.intent({ mode: 'NOW', channel: 'TERMINATE' });
  recorder.exit({ endedBy: 'terminate', terminateCalledAt: Date.now() });
  const writer = recorder.writerIdentity();
  const records = census.readRunLedger(dataDir);
  const binding = { writer, incarnation, checked: { pid: 4242, processIdentity } };
  const view = b => census.ledgerView(records, incarnation.session, { stopBindings: [b] });
  assert.equal(view(binding).engines[0].exit?.exitClass, 'UNKNOWN', 'the checked stop owns its truthful unknown-code exit');
  assert.equal(view({ ...binding, checked: { pid: 4242, processIdentity: { ...processIdentity, startTicks: '43' } } }).engines[0].exit, null);
  assert.equal(census.stopWriterBinding({ ...binding, checked: { pid: 4242, processIdentity: { ...processIdentity, startTicks: 'unknown' } } }), null);
  assert.equal(census.stopWriterBinding({ ...binding, writer: { ...writer, pid: process.pid + 1 } }), null);
});
