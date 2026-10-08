import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';
import ledgerLib from './lib/exit-ledger.cjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { crashEvidence } from '../jseval/lane-f/e-crash-evidence.mjs';

const at = n => `2026-10-08T10:00:${String(n).padStart(2, '0')}Z`;
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'job-witness-test', n: 1 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'test-instance' };
const run = { runId: incarnation.session, pids: { backendRootPid: 100 } };
const rec = (type, line) => ({ type, line, writer: { role: type.startsWith('engine-') ? 'dev-runner' : 'engine', pid: 100 } });
const row = (pid, parent, n, name, seen = n) => ({ pid, parentPid: parent, creationFileTimeUtc: birth(n),
  name, executable: `C:\\test\\${name}`, lastSeenAtMs: Date.parse(at(seen)) });
const helper = row(102, 100, 2, 'powershell.exe', 10);
const host = row(103, 102, 3, 'conhost.exe', 11);
const witness = (p, end = 10) => [rec('job-process-start', { witnessId: `j-${p.pid}`, owner, incarnation,
  pid: p.pid, creationFileTime: p.creationFileTimeUtc, executable: p.executable, contained: true,
  observedAt: at(4), awaited: true, expectedCode: 0, reason: 'HELPER_COMPLETION' }),
rec('job-process-exit', { witnessId: `j-${p.pid}`, owner, incarnation, pid: p.pid,
  creationFileTime: p.creationFileTimeUtc, code: 0, exitClass: 'REQUESTED', reason: 'HELPER_COMPLETION',
  exitedAt: at(end), observedAt: at(end), forced: false })];
const ledger = (records) => ({ engine: { present: true, records: [rec('engine-start', {
  incarnation, pid: 100, creationFileTime: birth(0), executable: 'C:\\test\\java.exe' }),
rec('engine-exit', { incarnation, exitClass: 'REQUESTED', reason: 'QUIT', code: 0, exitedAt: at(20) })] },
children: { present: true, records } });
const result = (records, rows = [helper, host]) => census.exitTargets(run, null, rows, ledger(records));

test('S15 job witnesses account helper and host identities through the real ledger reader', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'job-witness-'));
  try {
    const writer = ledgerLib.createLedgerWriter({ ledgerDir: dir, family: 'children',
      writer: { role: 'engine', pid: 100, startedAt: Date.parse(at(0)) } });
    for (const r of [...witness(helper), ...witness(host, 11)]) {
      assert.doesNotThrow(() => assert.equal(writer.append(r.type, r.line), true),
        'owner job witness record types must be supported');
    }
    const read = ledgerLib.readLedger(dir, 'children');
    assert.equal(read.records.length, 4, 'both new record types survive the production reader');
    const r = result(read.records);
    assert.deepEqual(r.gaps, []);
    assert.deepEqual(r.accountedProcesses.map(p => p.pid), [102, 103]);
    const verdict = census.exitResult({ ...r, runId: run.runId }, { processExits: [] }, 'test', ledger(read.records));
    assert.equal(verdict.ledgerSummary.unexpected, 0);
    assert.equal(verdict.ledgerSummary.jobProcesses, 2);
  } finally { fs.rmSync(dir, { recursive: true }); }
});

test('S18 N4, O2, O3 and unknown/corrupt/foreign witness history remain gaps', () => {
  const cases = [
    [], // N4: image/ancestry alone
    [...witness({ ...helper, creationFileTimeUtc: birth(1) }), ...witness(host, 11)], // O2 reuse
    [...witness(helper), ...witness(host, 30)], // O3 host seen past its client
    [...witness({ ...helper, creationFileTimeUtc: null }), ...witness(host, 11)],
    [...witness(helper).map(r => ({ ...r, line: { ...r.line, incarnation: { ...incarnation, session: 'foreign' } } })), ...witness(host, 11)],
    [...witness(helper).map(r => ({ ...r, writer: { role: 'engine', pid: 999 } })), ...witness(host, 11)],
    [...witness(helper).map(r => ({ ...r, writer: { role: 'engine', pid: 100, startedAt: Date.parse(at(1)) } })), ...witness(host, 11)],
    [...witness(helper), witness(helper)[1], ...witness(host, 11)], // duplicate/corrupt exit
  ];
  for (const records of cases) {
    const rows = records[1]?.line.exitedAt === at(30) ? [helper, { ...host, lastSeenAtMs: Date.parse(at(30)) }] : [helper, host];
    const r = result(records, rows);
    assert.ok(r.gaps.length > 0, JSON.stringify(records));
  }
  const afterClose = result([...witness(helper, 30), ...witness(host, 31)],
    [{ ...helper, lastSeenAtMs: Date.parse(at(30)) }, { ...host, lastSeenAtMs: Date.parse(at(31)) }]);
  assert.ok(afterClose.gaps.some(g => g.pid === 102));
  const noHostWitness = result(witness(helper));
  assert.deepEqual(noHostWitness.accountedProcesses.map(p => p.pid), [102], 'positive owner-witness control');
  assert.deepEqual(noHostWitness.gaps.map(g => [g.pid, g.reason]), [[103, 'orphan_console_host']],
    'a witnessed helper does not manufacture a witness for its host');
});

test('S15 helper crash stays unexpected and unknown exit time stays unknown', () => {
  const records = witness(helper);
  records[1].line = { ...records[1].line, code: 17, exitClass: 'UNEXPECTED', reason: null, exitedAt: null };
  const r = result(records, [helper]);
  assert.deepEqual(r.gaps, []);
  assert.equal(r.accountedProcesses[0].ledgerClass, 'UNEXPECTED');
  const view = census.ledgerView(ledger(records), run.runId);
  assert.equal(view.witnesses[0].exit.exitedAtMs, null);
  const verdict = census.exitResult({ ...r, runId: run.runId }, {}, 'test', ledger(records));
  assert.equal(verdict.ledgerSummary.unexpected, 1);
  assert.equal(verdict.processExits[0].ledgerClass, 'UNEXPECTED');
  assert.equal(verdict.processExits[0].expectedTermination, false);
  assert.equal(verdict.processExits[0].atMs, null);
  const crashes = crashEvidence([], {}, {}, verdict.processExits);
  assert.equal(crashes.events[0].type, 'abnormal-owned-exit');
  assert.equal(crashes.events[0].timing, 'unknown');
});

test('S15 live witnessed helpers and hosts are compared with their held OS exits', () => {
  const history = ledger([witness(helper)[0], witness(host)[0]]);
  history.engine.records.pop();
  const input = census.exitTargets(run, null, [helper, host], history);
  assert.deepEqual(input.gaps, []);
  assert.ok(input.targets.some(t => t.witnessId === 'j-102'));
  assert.ok(input.targets.some(t => t.witnessId === 'j-103' && t.role === 'console-host'));
  history.children.records.push(witness(helper)[1], witness(host, 11)[1]);
  history.engine.records.push(ledger([]).engine.records[1]);
  const processExits = input.targets.map(t => ({ pid: t.pid, creationFileTimeUtc: t.creationFileTimeUtc,
    executable: t.executable, exitCode: 0, atMs: Date.parse(at(t.pid === 103 ? 11 : t.pid === 102 ? 10 : 20)) }));
  const r = census.exitResult(input, { processExits }, 'test', history);
  assert.deepEqual(r.exitAccountingGaps, []);
  assert.equal(r.exitAccountingComplete, true);
  assert.ok(r.processExits.every(e => e.expectedTermination));
});

test('S15 census finish credits owner witnesses flushed after arming', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'job-witness-late-'));
  let history = ledger([]);
  history.engine.records.pop();
  const scope = path.join(dir, 'scope.json');
  fs.writeFileSync(scope, JSON.stringify({ atMs: Date.parse(at(11)), processes: [helper, host] }));
  let files;
  try {
    const observer = await census.startStopExitCensus(run, dir, dir, scope, {
      readLedger: () => history,
      launch(args) {
        files = Object.fromEntries(['InputFile', 'OutputFile', 'ReadyFile', 'FinishFile']
          .map(name => [name, args[args.indexOf('-' + name) + 1]]));
        fs.writeFileSync(files.ReadyFile, '{}');
        return Object.assign(new EventEmitter(), { exitCode: null });
      },
      async pause() {
        if (!fs.existsSync(files.FinishFile)) return;
        history = ledger([...witness(helper), ...witness(host, 11)]);
        const armed = JSON.parse(fs.readFileSync(files.InputFile, 'utf8'));
        fs.writeFileSync(files.OutputFile, JSON.stringify({ processExits: armed.targets.map(t => ({
          pid: t.pid, creationFileTimeUtc: t.creationFileTimeUtc, executable: t.executable,
          exitCode: 0, atMs: Date.parse(at(20)) })) }));
      },
    });
    const r = await observer.finish();
    assert.deepEqual(r.exitAccountingGaps, [], 'an initial membership gap must resolve from final owner evidence');
    assert.equal(r.exitAccountingComplete, true);
    assert.deepEqual(r.accountedProcesses.map(p => p.pid), [102, 103]);
  } finally { fs.rmSync(dir, { recursive: true }); }
});
