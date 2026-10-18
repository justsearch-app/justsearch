import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { test } from 'node:test';
const require = createRequire(import.meta.url);
const census = require('./lib/stop-exit-census.cjs');
const ledger = require('./lib/exit-ledger.cjs');
const linux = process.platform === 'linux';
const bootId = '11111111-2222-4333-8444-555555555555';
const native = (pid, ticks = String(pid)) => ({ kind: 'linux-proc-v1', pid, bootId, startTicks: ticks });
const owner = { pid: 4001, processIdentity: native(4001), instanceId: 'engine-fixture' };
const incarnation = { session: 'native-helper-census', n: 1 };
const at = '2026-10-18T12:00:00Z';
const engineWriter = { role: 'engine', pid: owner.pid, startedAt: at, processIdentity: native(owner.pid) };
function fixture(change = () => {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'linux-helper-census-'));
  const runner = { role: 'dev-runner', pid: 3001, startedAt: at };
  const record = (type, writer, fields, seq) => ({ schemaVersion: 1, type, writer, seq, at, ...fields });
  const engine = [record('engine-start', runner, { incarnation, ...owner, executable: '/java' }, 1),
    record('engine-exit', runner, { incarnation, code: 0, exitClass: 'REQUESTED', reason: 'QUIT', forced: false }, 2)];
  const common = { helperId: 'h-native', pid: 5001, processIdentity: native(5001), creationFileTime: null,
    creatorPid: owner.pid, creatorProcessIdentity: native(owner.pid), owner, incarnation,
    executable: '/usr/bin/tesseract', argvHash: 'a'.repeat(64) };
  const children = [record('helper-start', engineWriter, { ...common, purpose: 'native availability' }, 1),
    record('helper-exit', engineWriter, { ...common, code: 0, exitClass: 'REQUESTED', reason: 'HELPER_COMPLETION',
      forced: false, awaited: true, exitedAt: null }, 2)];
  change({ engine, children });
  const ledgerDir = ledger.ledgerDirectory(dir); fs.mkdirSync(ledgerDir, { recursive: true });
  for (const [family, records] of Object.entries({ engine, children })) fs.writeFileSync(path.join(ledgerDir, family + '.v1.jsonl'), records.map(r => JSON.stringify(r)).join('\n') + '\n');
  const session = engine[0].incarnation.session;
  const read = census.readRunLedger(dir), view = census.ledgerView(read, session);
  return { dir, read, view, input: census.exitTargets({ runId: session, pids: { backendRootPid: owner.pid } }, null, [], read, view) };
}
test('native writer birth survives the shared ledger reader', { skip: !linux }, () => {
  const f = fixture(); try { assert.deepEqual(f.read.children.records[0].writer.processIdentity, native(owner.pid)); }
  finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});
test('a native held-process helper receipt is inventoried and accounted without a Windows job witness', { skip: !linux }, () => {
  const f = fixture(); try {
    assert.equal(f.view.helpers?.length, 1, 'a completed rapid helper must not disappear from the census');
    assert.equal(f.view.witnesses.length, 0, 'Linux does not manufacture a Windows job witness');
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    const row = result.processExits.find(r => r.pid === 5001);
    assert.ok(row, 'the stopped run reports the helper identity');
    assert.equal(row.ledgerClass, 'REQUESTED'); assert.equal(row.exitCode, 0);
    assert.equal(row.accounted, true);
    assert.equal(result.ledgerSummary.nativeHelpers, 1);
    assert.equal(result.ledgerSummary.jobProcesses, undefined);
    assert.deepEqual(row.processIdentity, native(5001));
    assert.equal(result.exitAccountingGaps.some(g => g.pid === 5001), false, JSON.stringify(result.exitAccountingGaps));
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});
for (const [name, mutate] of [
  ['another writer birth', x => { for (const r of x.children) r.writer = { ...r.writer, processIdentity: native(owner.pid, '999') }; }],
  ['another creator birth', x => { for (const r of x.children) r.creatorProcessIdentity = native(owner.pid, '999'); }],
  ['an exit for another helper birth', x => { x.children[1].processIdentity = native(5001, '999'); }],
  ['a missing writer birth', x => { for (const r of x.children) { r.writer = { ...r.writer }; delete r.writer.processIdentity; } }],
]) test('native helper proof refuses ' + name, { skip: !linux }, () => {
  const f = fixture(mutate); try {
    assert.equal(f.view.helpers?.length, 1, 'unproven helper remains in the inventory');
    const start = f.view.allStarts.find(x => x.family === 'helper');
    assert.equal(census.isProven(start, f.read.children.records, f.view.allStarts), null,
      'the shared proof function also rejects unfiltered untrusted receipts');
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    assert.equal(result.processExits.find(r => r.pid === 5001)?.ledgerClass, 'UNKNOWN');
    assert.ok(result.exitAccountingGaps.some(g => g.pid === 5001), JSON.stringify(result.exitAccountingGaps));
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

test('conflicting native helper codes remain a disagreement', { skip: !linux }, () => {
  const f = fixture(x => x.children.push({ ...x.children[1], seq: 3, code: 1 })); try {
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    assert.ok(result.exitAccountingGaps.some(g => g.pid === 5001 && g.reason === 'ledger_codes_disagree'));
    assert.equal(result.processExits.find(r => r.pid === 5001).accounted, false);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

for (const mismatch of [false, true]) test('a parser helper receipt ' + (mismatch ? 'refuses another incarnation' : 'is tied to its owned parser'), { skip: !linux }, () => {
  const f = fixture(x => {
    const parser = { pid: 6001, processIdentity: native(6001), instanceId: 'parser-fixture' };
    const start = { schemaVersion: 1, writer: engineWriter, at, incarnation, owner, childId: 'parser-child', kind: 'PARSER' };
    x.children.unshift({ ...start, type: 'child-spawn', seq: 1 },
      { ...start, type: 'child-start', seq: 2, pid: parser.pid, processIdentity: parser.processIdentity, executable: '/java' });
    for (const r of x.children.filter(r => r.helperId)) {
      r.writer = { ...engineWriter, pid: parser.pid, processIdentity: parser.processIdentity };
      r.owner = parser; r.creatorPid = parser.pid; r.creatorProcessIdentity = parser.processIdentity;
      if (mismatch) r.incarnation = { ...incarnation, n: 2 };
    }
  }); try {
    assert.equal(f.view.helpers.length, 1);
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    assert.equal(result.processExits.find(r => r.pid === 5001)?.ledgerClass, mismatch ? 'UNKNOWN' : 'REQUESTED');
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

for (const [name, invalid] of [['mismatched PID', { ...native(owner.pid), pid: 9999 }],
  ['malformed native birth', { ...native(owner.pid), startTicks: 'not-a-number' }]])
  test('a writer with ' + name + ' is damaged evidence, never legacy authority', { skip: !linux }, () => {
    const f = fixture(x => { for (const r of x.children) r.writer = { ...r.writer, processIdentity: invalid }; });
    try {
      assert.equal(f.read.children.records.length, 0);
      assert.ok(f.read.children.damage.length > 0);
      const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
      assert.equal(result.exitAccountingComplete, false);
      assert.ok(result.exitAccountingGaps.some(g => g.reason === 'ledger-damaged'));
    } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
  });

for (const [name, seen, ended] of [
  ['absent', { present: false }, true],
  ['reused', { present: true, processIdentity: native(5001, '999') }, true],
  ['unreadable', null, false],
]) test('a helper without its final receipt stays truthful when its identity is ' + name, { skip: !linux }, () => {
  const f = fixture(x => { x.children.pop(); }); try {
    const target = f.input.targets.find(t => t.pid === 5001);
    assert.ok(target, 'a helper whose owner died is still an explicit census target');
    const observation = census.lookupObservation(target, seen, Date.now());
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view,
      { lookupEnded: observation ? [observation] : [] });
    const row = result.processExits.find(r => r.pid === 5001);
    assert.ok(row, 'an unreadable helper never disappears from the report');
    assert.equal(row.ledgerClass, 'UNKNOWN'); assert.equal(row.exitCode ?? null, null);
    assert.equal(row.accounted === true, ended);
    assert.equal(result.exitAccountingGaps.some(g => g.pid === 5001), !ended);
    assert.deepEqual(row.processIdentity, native(5001));
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

for (const [name, seen, ended] of [
  ['absent', { present: false }, true],
  ['reused', { present: true, processIdentity: native(5001, '999') }, true],
  ['unreadable', null, false],
]) test('an unknown-code receipt never overrides an independent ' + name + ' identity observation', { skip: !linux }, () => {
  const f = fixture(x => { x.children[1].code = null; x.children[1].exitClass = 'UNKNOWN'; x.children[1].reason = 'QUIT'; });
  try {
    const item = f.view.helpers[0];
    const observation = census.lookupObservation(item, seen, Date.now());
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view,
      { lookupEnded: observation ? [observation] : [] });
    const row = result.processExits.find(r => r.pid === 5001);
    assert.equal(row.ledgerClass, 'UNKNOWN'); assert.equal(row.exitCode, null);
    assert.equal(row.expectedTermination, false, 'end proof never invents the stop cause');
    assert.equal(result.exitAccountingGaps.some(g => g.pid === 5001), !ended);
    if (ended) { assert.equal(row.accounted, true); assert.equal(row.endedObservedBy, 'identity-checked lookup'); }
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

for (const [name, mutate] of [
  ['missing birth', x => { for (const r of x.children) delete r.processIdentity; }],
  ['null birth', x => { for (const r of x.children) r.processIdentity = null; }],
  ['malformed birth', x => { for (const r of x.children) r.processIdentity = { ...native(5001), startTicks: 'unknown' }; }],
  ['malformed helper key', x => { for (const r of x.children) r.helperId = '../invalid'; }],
]) test('a native helper with ' + name + ' is explicit unknown inventory', { skip: !linux }, () => {
  const f = fixture(x => { mutate(x); if (name === 'missing birth' || name === 'null birth') x.children[1].code = null; }); try {
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    const row = result.processExits.find(r => r.pid === 5001);
    assert.ok(row, 'the ledger names a helper; missing authority must never erase it');
    assert.equal(row.ledgerClass, 'UNKNOWN'); assert.equal(row.accounted, false);
    assert.equal(result.exitAccountingComplete, false);
    assert.ok(result.exitAccountingGaps.some(g => g.pid === 5001));
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

test('a stop with an unidentified native helper stays incomplete, retains state and sends no signal', { skip: !linux }, async () => {
  const state = fs.mkdtempSync(path.join(os.tmpdir(), 'unknown-helper-stop-'));
  const previous = process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
  process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = state;
  const api = require('./dev-runner.cjs').__test;
  try {
    for (const birth of [undefined, null, { ...native(5001), startTicks: 'unknown' }]) {
      const f = fixture(x => { for (const r of x.children) r.processIdentity = birth; if (birth == null) x.children[1].code = null; });
      try {
        const run = { runId: incarnation.session, dataDir: f.dir, pids: {}, identities: {},
          holder: api.holderBlockFor(api.resolveCallerIdentity('unknown-helper-stop')) };
        const runPath = path.join(f.dir, 'run.json'); fs.writeFileSync(runPath, JSON.stringify(run));
        fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify({ runId: run.runId }));
        const sentinel = path.join(f.dir, 'keep-indexed-content'); fs.writeFileSync(sentinel, 'retained');
        const signals = [];
        const result = await api.stopRun({}, { resolveTarget: async () => ({ run, runPath }),
          lookupIdentities: async pids => new Map(pids.map(pid => [pid, { present: false }])),
          portOwner: async () => ({ closed: true }), portWaitMs: 0,
          terminateProcess: (...args) => { signals.push(args); throw new Error('no helper signal is authorized'); } });
        assert.equal(result.outcome, 'STOP_INCOMPLETE');
        assert.ok(result.unresolvedProcesses.some(p => p.pid === 5001 && /ownership is unproven/.test(p.reason)));
        assert.equal(fs.existsSync(path.join(state, 'active.json')), true);
        assert.equal(fs.readFileSync(sentinel, 'utf8'), 'retained');
        assert.deepEqual(signals, []);
      } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
    }
    const f = rapidFixture();
    try {
      const helper = f.view.helpers[0];
      const run = { runId: helper.incarnation.session, dataDir: f.dir, pids: {}, identities: {},
        holder: api.holderBlockFor(api.resolveCallerIdentity('unknown-helper-stop')),
        stopTrackedProcesses: [{ pid: helper.pid, role: 'native-helper', creationFileTimeUtc: null }] };
      const runPath = path.join(f.dir, 'run.json'); fs.writeFileSync(runPath, JSON.stringify(run));
      fs.writeFileSync(path.join(state, 'active.json'), JSON.stringify({ runId: run.runId }));
      const signals = [], lookups = [];
      const result = await api.stopRun({}, { resolveTarget: async () => ({ run, runPath }),
        lookupIdentities: async pids => { lookups.push(...pids); return new Map(pids.map(pid => [pid, { present: false }])); },
        portOwner: async () => ({ closed: true }), portWaitMs: 0,
        terminateProcess: (...args) => { signals.push(args); throw new Error('receipt is never signal authority'); } });
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.outcome, 'STOPPED');
      assert.deepEqual(result.unresolvedProcesses, []);
      assert.equal(fs.existsSync(path.join(state, 'active.json')), false);
      assert.deepEqual(signals, []);
      assert.equal(lookups.includes(helper.pid), false, 'creator receipt needs no PID observation');
    } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
  } finally {
    if (previous === undefined) delete process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT;
    else process.env.JUSTSEARCH_DEV_RUNNER_STATE_ROOT = previous;
    fs.rmSync(state, { recursive: true, force: true });
  }
});

test('a Windows helper birth stays on its existing backend even when the writer host is Linux', () => {
  const f = fixture(x => { for (const r of x.children) {
    delete r.processIdentity; r.creationFileTime = '134000000000000000';
  } }); try {
    assert.equal(f.view.helpers.length, 0);
    assert.equal(f.view.reportIdentities.some(p => p.role === 'native-helper'), false);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

test('a malformed legacy birth never hides a native helper with an unreadable tuple', { skip: !linux }, () => {
  const f = fixture(x => { for (const r of x.children) {
    delete r.processIdentity; r.creationFileTime = 'not-a-birth';
  } }); try {
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    assert.equal(result.processExits.find(p => p.pid === 5001)?.ledgerClass, 'UNKNOWN');
    assert.equal(result.exitAccountingComplete, false);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

const rapidProbe = () => fs.readFileSync(new URL('./fixtures/rapid-tesseract-helper.jsonl', import.meta.url), 'utf8')
  .trim().split('\n').map(JSON.parse);
function rapidFixture(mutate = () => {}) {
  return fixture(x => {
    x.children.splice(0, x.children.length, ...rapidProbe());
    const start = x.children[0];
    x.engine[0] = { ...x.engine[0], ...start.owner, incarnation: start.incarnation };
    x.engine[1].incarnation = start.incarnation;
    mutate(x);
  });
}
test('creator-observed rapid Tesseract exit accounts its logical helper without a birth or PID authority', { skip: !linux }, () => {
  const f = rapidFixture(); try {
    const helper = f.view.helpers[0];
    assert.equal(helper.proof?.by, 'direct');
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    const row = result.processExits.find(r => r.helperId === helper.helperId);
    assert.equal(row?.accounted, true);
    assert.equal(row.exitCode, 0);
    assert.equal(result.exitAccountingGaps.some(g => g.pid === helper.pid), false);
    const resolution = census.ledgerResolution(f.view);
    assert.equal(resolution.unproven.some(p => p.pid === helper.pid), false);
    assert.equal(resolution.isEnded({ ...helper, role: 'native-helper' }), true);
    assert.equal(resolution.isEnded({ pid: helper.pid }), false, 'a bare PID gains no authority');
    assert.equal(census.lookupObservation(helper, { present: false }, Date.now()), null);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});
for (const [name, mutate] of [
  ['missing exit', x => x.children.pop()],
  ['missing awaited exit code', x => { x.children[1].code = null; }],
  ['another helper id', x => { x.children[1].helperId = 'h-other'; }],
  ['another writer', x => { x.children[1].writer = { ...x.children[1].writer, processIdentity: native(x.children[1].writer.pid) }; }],
  ['another writer start', x => { x.children[1].writer = { ...x.children[1].writer, startedAt: '2026-10-18T21:36:33.331Z' }; }],
  ['reconciled exit', x => { x.children[1].reconciled = true; }],
  ['malformed birth', x => { for (const r of x.children) r.processIdentity = { kind: 'unknown' }; }],
]) test('an unidentified helper remains unproven with ' + name, { skip: !linux }, () => {
  const f = rapidFixture(mutate); try {
    const helper = f.view.helpers[0];
    assert.equal(helper.proof, null);
    const resolution = census.ledgerResolution(f.view);
    assert.equal(resolution.isEnded({ ...helper, role: 'native-helper' }), false);
    assert.ok(resolution.unproven.some(p => p.pid === helper.pid));
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    assert.equal(result.exitAccountingComplete, false);
    assert.ok(result.exitAccountingGaps.some(p => p.pid === helper.pid));
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

test('a rapid parser helper is bound through the parser owner when child-start carries no incarnation', { skip: !linux }, () => {
  const f = rapidFixture(x => {
    const parser = x.children[0].owner, inc = x.children[0].incarnation;
    x.engine[0] = { ...x.engine[0], ...owner, incarnation: inc };
    x.children.unshift({ schemaVersion: 1, type: 'child-start', seq: 1, at,
      writer: engineWriter, owner, childId: 'parser-child', kind: 'PARSER',
      pid: parser.pid, processIdentity: parser.processIdentity, executable: '/java' });
  }); try {
    assert.equal(f.view.helpers[0].proof?.by, 'direct');
    const resolution = census.ledgerResolution(f.view);
    assert.equal(resolution.isEnded({ pid: 2364530, role: 'native-helper' }), true,
      'legacy unresolved helper reports reconcile only against all creator-proven logical starts');
    assert.equal(resolution.isEnded({ pid: 2364530, role: 'native-helper', helperId: 'h-stranger' }), false);
    assert.equal(resolution.isEnded({ pid: 2364530, role: 'engine' }), false);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

test('an unidentified helper with conflicting creator codes stays incomplete on retry', { skip: !linux }, () => {
  const f = rapidFixture(x => x.children.push({ ...x.children[1], seq: 8, code: 1 })); try {
    const helper = f.view.helpers[0];
    const result = census.exitResult(f.input, { processExits: [] }, 'fixture', f.read, f.view);
    assert.ok(result.exitAccountingGaps.some(g => g.pid === helper.pid && g.reason === 'ledger_codes_disagree'));
    assert.equal(result.processExits.find(r => r.helperId === helper.helperId).accounted, false);
    assert.equal(census.ledgerResolution(f.view).isEnded({ pid: helper.pid, role: 'native-helper' }), false);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});
test('an unobserved helper sharing a recycled PID is not discharged by another helper receipt', { skip: !linux }, () => {
  const f = rapidFixture(x => x.children.push({ ...x.children[0], seq: 8, helperId: 'h-unobserved' })); try {
    const helper = f.view.helpers[0];
    const resolution = census.ledgerResolution(f.view);
    assert.equal(resolution.isEnded({ pid: helper.pid, role: 'native-helper' }), false);
    assert.ok(resolution.unproven.some(p => p.helperId === 'h-unobserved'));
    assert.equal(resolution.isEnded({ pid: helper.pid, role: 'native-helper', helperId: 'h-unobserved' }), false);
  } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
});

test('a creator receipt cannot prove a helper start with an invalid PID', { skip: !linux }, () => {
  for (const pid of [0, null, -1]) {
    const f = rapidFixture(x => { for (const r of x.children) r.pid = pid; }); try {
      const start = f.view.allStarts.find(s => s.family === 'helper');
      assert.equal(start.identity.pid, null);
      assert.equal(census.isProven(start, f.read.children.records, f.view.allStarts), null);
    } finally { fs.rmSync(f.dir, { recursive: true, force: true }); }
  }
});
