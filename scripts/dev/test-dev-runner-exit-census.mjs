import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { EventEmitter } from 'node:events';
import census from './lib/stop-exit-census.cjs';
import ledgerLib from './lib/exit-ledger.cjs';
import actuator from './lib/engine-actuator.cjs';
import runner from './dev-runner.cjs';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
fs.mkdirSync(path.join(root, 'tmp'), { recursive: true }); // a fresh checkout has no tmp/
const start = '2026-10-01T00:00:00.1234567Z', birth = census.fileTimeFromInstant(start);
const exe = path.join(root, 'tmp/native-child.exe');
const engine = { pid: 10, creationFileTimeUtc: birth, executable: exe, role: 'engine' };
const run = { runId: 'run-census', pids: { backendRootPid: 10 }, exitCensusEngine: engine };
const child = { id: 'child', pid: 11, kind: 'LLAMA_SERVER', startedAt: start, executable: exe };
const manifest = { pid: 10, children: [child, { ...child, pid: 12, kind: 'EXTRACTION' }] };
const input = () => census.exitTargets(run, manifest);
const STOP = ledgerLib.JS_CHILD_STOP;
const llamaBirth = census.fileTimeFromInstant('2026-10-01T00:00:01.0000000Z');
const extractionBirth = census.fileTimeFromInstant('2026-10-01T00:00:02.0000000Z');
const reasons = gaps => gaps.map(g => g.reason);

/** Writes the run's ledger the way its writers do: the dev runner (engine family), the Engine (children). */
function ledger(dataDir) {
  const ledgerDir = ledgerLib.ledgerDirectory(dataDir);
  const devRunner = ledgerLib.createLedgerWriter({ ledgerDir, family: 'engine',
    writer: { role: 'dev-runner', pid: 4000, startedAt: Date.UTC(2026, 9, 1) } });
  const engineWriter = ledgerLib.createLedgerWriter({ ledgerDir, family: 'children',
    writer: { role: 'engine', pid: 10, startedAt: Date.UTC(2026, 9, 1) } });
  const owner = { pid: 10, creationFileTime: birth, instanceId: 'inst-1' };
  return {
    engineStart(session = run.runId, n = 1, pid = 10) {
      devRunner.append('engine-start', { incarnation: { session, n }, pid, creationFileTime: birth, executable: exe,
        processStartedAt: new Date(Date.parse(start)), cause: 'START', supervisorKind: 'dev-runner' });
    },
    engineExit(fields, session = run.runId, n = 1) {
      devRunner.append('engine-exit', { incarnation: { session, n }, exitClass: 'REQUESTED', reason: 'QUIT', detail: 'interrupted',
        code: 0, codeName: 'OK', exitedAt: null, observedAt: new Date(), terminateCalledAt: null, forced: false,
        phase: 'STOPPING', codeDistinctive: false, ...fields });
    },
    childStart(childId, kind, pid, creationFileTime, childOwner = owner, executable = exe) {
      engineWriter.append('child-spawn', { childId, kind, label: kind, owner: childOwner, gpu: false });
      engineWriter.append('child-start', { childId, pid, processStartedAt: null, creationFileTime, executable,
        argvHash: 'a'.repeat(64), contained: true, gpuDevice: null });
    },
    childExit(childId, fields) {
      engineWriter.append('child-exit', { childId, intentId: `s-${childId}`, exitClass: 'REQUESTED', reason: 'QUIT', detail: 'interrupted',
        mode: 'COOPERATIVE', code: STOP, codeName: 'JS_CHILD_STOP', codeDistinctive: true, exitedAt: null, observedAt: new Date(),
        terminateCalledAt: new Date(), terminateResult: 'OK', cooperativeAt: null, forced: true, phase: 'STOPPING', ...fields });
    },
  };
}

/**
 * Runs the census entry point against a real data directory (manifest, ledger, scope file) with a
 * stand-in for the PowerShell observer: `exitsFor(targets)` is what the held handles report;
 * `duringStop(dir)` runs between arming and finish (the stop itself).
 */
async function observe(t, { manifestJson = { pid: 10, children: [] }, observed = [], exitsFor, duringStop, write }) {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify(manifestJson));
  write?.(ledger(dir), dir);
  const scope = path.join(dir, 'scope.json'); fs.writeFileSync(scope, JSON.stringify({ processes: observed }));
  let clock = 0, args, stopped = false;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = f => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(run, dir, dir, scope, {
    launch: a => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async ms => {
      clock += ms;
      if (fs.existsSync(flag('-FinishFile')) && !stopped) {
        stopped = true;
        const targets = JSON.parse(fs.readFileSync(flag('-InputFile'))).targets;
        duringStop?.(ledger(dir), dir);
        fs.writeFileSync(flag('-OutputFile'), '\uFEFF' + JSON.stringify({ processExits: exitsFor(targets), gaps: [], killedPids: [] }));
      }
    },
  });
  const targets = JSON.parse(fs.readFileSync(flag('-InputFile'))).targets;
  return { targets, result: await observer.finish([]) };
}
const at = Date.UTC(2026, 9, 1, 1);
const exitAll = (codeOf, extra = {}) => targets => targets.map(t => ({ pid: t.pid, creationFileTimeUtc: t.creationFileTimeUtc,
  executable: t.executable, exitCode: codeOf(t), atMs: at, ...extra }));
const llamaRow = { pid: 11, creationFileTimeUtc: llamaBirth, executable: exe, role: 'llama-server' };
const conhostExe = 'C:\\Windows\\System32\\conhost.exe';

test('Spec F §6.1: targets are the run\'s Engine incarnations and the children they own, read from the exit ledger', async t => {
  const { targets, result } = await observe(t, {
    observed: [llamaRow],
    write: l => {
      l.engineStart(); l.engineStart('another-run', 1, 77);
      l.childStart('c-llama', 'LLAMA_SERVER', 11, llamaBirth);
      l.childStart('c-foreign', 'EXTRACTION', 55, extractionBirth, { pid: 77, creationFileTime: birth, instanceId: 'other' });
    },
    exitsFor: exitAll(t => (t.role === 'engine' ? 0 : STOP)),
    duringStop: l => { l.childExit('c-llama'); l.engineExit({}); },
  });
  assert.deepEqual(targets.map(x => [x.role, x.pid, x.targetSource]), [['engine', 10, 'ledger'], ['llama-server', 11, 'ledger']]);
  assert.deepEqual(targets[0].incarnation, { session: run.runId, n: 1 });
  // S15-U1: a clean quit is zero unexpected and zero gaps.
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true);
  assert.deepEqual(result.processExits.map(e => [e.role, e.ledgerClass, e.expectedTermination]),
    [['engine', 'REQUESTED', true], ['llama-server', 'REQUESTED', true]]);
  assert.deepEqual(result.ledgerSummary, { requested: 2, unexpected: 0, ownerDied: 0, unknown: 0, gaps: 0 });
  const report = runner.__test.buildStopReport({ ...result, runId: run.runId });
  assert.deepEqual(report.ledgerSummary, result.ledgerSummary);
  assert.deepEqual(report.processExits, result.processExits);
});

test('Spec B step 2: the children ledger the Engine writes accounts for every extraction child', async t => {
  // DefaultChildControl's own records (EngineWrittenChildrenFixtureTest keeps this file current):
  // a timeout kill, a cooperative close and a crash, rebound to this test's Engine and OS rows.
  const fixture = fs.readFileSync(path.join(root, 'governance/fixtures/exit-ledger/engine-written/children.v1.jsonl'), 'utf8')
    .split('\n').filter(Boolean).map(line => JSON.parse(line));
  const pids = new Map();
  const rebound = fixture.map(r => {
    const out = { ...r, writer: { ...r.writer, pid: 10 } };
    if (r.owner) out.owner = { ...r.owner, pid: 10, creationFileTime: birth };
    if (r.type === 'child-start') {
      pids.set(r.childId, 20 + pids.size);
      Object.assign(out, { pid: pids.get(r.childId), creationFileTime: extractionBirth, executable: exe });
    }
    return out;
  });
  const atStop = new Set(['stop-intent', 'child-exit', 'child-note']);
  const append = (dir, records) => fs.appendFileSync(ledgerLib.currentPath(ledgerLib.ledgerDirectory(dir), 'children'),
    records.map(r => `${JSON.stringify(r)}\n`).join(''));
  const codes = new Map(rebound.filter(r => r.type === 'child-exit').map(r => [pids.get(r.childId), r.code]));
  const { targets, result } = await observe(t, {
    observed: [...pids.values()].map(pid => ({ pid, creationFileTimeUtc: extractionBirth, executable: exe, role: 'extraction-child' })),
    write: (l, dir) => { l.engineStart(); append(dir, rebound.filter(r => !atStop.has(r.type))); },
    exitsFor: exitAll(x => (x.role === 'engine' ? 0 : codes.get(x.pid))),
    duringStop: (l, dir) => { append(dir, rebound.filter(r => atStop.has(r.type))); l.engineExit({}); },
  });
  assert.deepEqual(targets.map(x => [x.role, x.targetSource]), [['engine', 'ledger'],
    ['extraction-child', 'ledger'], ['extraction-child', 'ledger'], ['extraction-child', 'ledger']]);
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true);
  assert.deepEqual(result.processExits.filter(e => e.role === 'extraction-child')
    .map(e => [e.ledgerClass, e.ledgerReason, e.expectedTermination]),
  [['REQUESTED', 'TIMEOUT', true], ['REQUESTED', 'QUIT', true], ['UNEXPECTED', null, false]]);
  assert.deepEqual(result.ledgerSummary, { requested: 3, unexpected: 1, ownerDied: 0, unknown: 0, gaps: 0 });
});

test('Spec B T16: an exit code is classified by its ledger record, never on its own', async t => {
  const { result } = await observe(t, {
    observed: [llamaRow, { pid: 12, creationFileTimeUtc: extractionBirth, executable: exe, role: 'extraction-child' },
      { pid: 14, creationFileTimeUtc: extractionBirth, executable: exe, role: 'extraction-child' },
      { pid: 15, creationFileTimeUtc: extractionBirth, executable: exe, role: 'extraction-child' }],
    write: l => {
      l.engineStart();
      l.childStart('c-llama', 'LLAMA_SERVER', 11, llamaBirth);
      l.childStart('c-crash', 'EXTRACTION', 12, extractionBirth);
      l.childStart('c-silent', 'EXTRACTION', 14, extractionBirth);
      l.childStart('c-disagree', 'EXTRACTION', 15, extractionBirth);
    },
    exitsFor: exitAll(t => ({ 10: 1, 11: STOP, 12: -1073741819, 14: 1, 15: 1 })[t.pid]),
    duringStop: l => {
      l.childExit('c-llama');
      l.childExit('c-crash', { exitClass: 'UNEXPECTED', reason: null, detail: null, code: 3221225477, forced: false });
      l.childExit('c-disagree', { code: STOP });
      l.engineExit({ code: 1, forced: true, reason: 'QUIT', detail: 'interrupted' });
    },
  });
  const byPid = Object.fromEntries(result.processExits.map(e => [e.pid, e]));
  assert.equal(byPid[11]?.ledgerClass, 'REQUESTED'); assert.equal(byPid[11].expectedTermination, true);
  assert.equal(byPid[10].expectedTermination, true, 'dev Engine forced stop: REQUESTED with code 1');
  assert.equal(byPid[12].ledgerClass, 'UNEXPECTED', 'a signed OS code and an unsigned ledger code are one DWORD');
  assert.equal(byPid[12].expectedTermination, false);
  assert.equal(byPid[14].expectedTermination, false);
  assert.deepEqual(result.exitAccountingGaps.map(g => [g.pid, g.reason]), [[14, 'unrecorded_exit'], [15, 'ledger_os_disagree']]);
  assert.equal(result.exitAccountingComplete, false);
  assert.deepEqual(result.ledgerSummary, { requested: 3, unexpected: 1, ownerDied: 0, unknown: 0, gaps: 2 });
});

test('Spec B steps 5-6: the llama children stopped through the control are accounted, the self-test candidate in its own role', async t => {
  const selfTestRow = { pid: 17, creationFileTimeUtc: extractionBirth, executable: exe, role: 'llama-self-test' };
  const { targets, result } = await observe(t, {
    observed: [llamaRow, selfTestRow],
    write: l => {
      l.engineStart();
      l.childStart('c-llama', 'LLAMA_SERVER', 11, llamaBirth);
      l.childStart('c-self-test', 'LLAMA_SELF_TEST', 17, extractionBirth);
    },
    exitsFor: exitAll(x => (x.role === 'engine' ? 0 : STOP)),
    duringStop: l => {
      // No cooperative step for llama (B4): both are terminated with the child stop code (B1).
      l.childExit('c-self-test', { reason: 'SELF_TEST', detail: null, mode: 'NOW', cooperativeAt: null });
      l.childExit('c-llama', { reason: 'QUIT', detail: null, mode: 'NOW', cooperativeAt: null });
      l.engineExit({});
    },
  });
  assert.deepEqual(targets.map(x => [x.role, x.pid]), [['engine', 10], ['llama-server', 11], ['llama-self-test', 17]]);
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true);
  assert.deepEqual(result.processExits.map(e => [e.role, e.ledgerClass, e.ledgerReason, e.expectedTermination]),
    [['engine', 'REQUESTED', 'QUIT', true], ['llama-server', 'REQUESTED', 'QUIT', true],
      ['llama-self-test', 'REQUESTED', 'SELF_TEST', true]]);
  assert.deepEqual(result.ledgerSummary, { requested: 3, unexpected: 0, ownerDied: 0, unknown: 0, gaps: 0 });
});

test('Spec B T16: without a ledger record, the stop code and code 1 are gaps; the stop\'s own kills grant nothing', () => {
  const i = input();
  const exits = i.targets.map(t => ({ ...t, exitCode: t.pid === 11 ? STOP : t.pid === 12 ? 1 : 0, atMs: at }));
  const result = census.exitResult(i, { processExits: exits, forcedPids: [10, 11, 12], killedPids: [10, 11, 12] }, 'receipt');
  assert.ok(result.processExits.every(e => e.expectedTermination === false));
  assert.deepEqual(result.exitAccountingGaps.map(g => [g.pid, g.reason]),
    [[10, 'ledger-unavailable'], [undefined, 'ledger-unavailable'], [10, 'unrecorded_exit'], [11, 'ledger_record_missing'], [12, 'unrecorded_exit']]);
  assert.equal(result.exitAccountingComplete, false);
});

test('Spec F §6.1: the manifest list is only the fallback, named as gap ledger-unavailable', () => {
  assert.equal(BigInt(birth) % 10000n, 4567n);
  assert.equal(census.fileTimeFromInstant('2026-10-01T00:00:00.123456789Z'), null);
  assert.deepEqual(input().targets.map(t => t.role), ['engine', 'llama-server', 'extraction-child']);
  assert.deepEqual(input().gaps.map(g => [g.reason, g.family]), [['ledger-unavailable', 'engine'], ['ledger-unavailable', 'children']]);
  assert.equal(census.exitTargets(run, { ...manifest, pid: 99 }).gaps[0].role, 'engine');
  assert.ok(census.exitTargets(run, { pid: 10, children: [{ ...child, executable: '' }] }).gaps
    .some(g => /lacks exact PID/.test(g.reason)));
  const missing = census.exitTargets(run, manifest, [{ pid: 99, creationFileTimeUtc: birth, role: 'extraction-child' }]);
  assert.equal(missing.gaps.find(g => g.pid === 99).reason, 'unaccounted_process');
});

test('Spec F §3.2: a manifest naming another run\'s incarnation is not this run\'s to observe', () => {
  const own = census.exitTargets(run, { ...manifest, incarnation: { session: run.runId, n: 1 } });
  assert.deepEqual(own.targets.map(t => t.role), ['engine', 'llama-server', 'extraction-child']);
  const foreign = census.exitTargets(run, { ...manifest, incarnation: { session: 'run-earlier', n: 3 } });
  assert.deepEqual(foreign.targets, []);
  assert.deepEqual(foreign.gaps.map(g => [g.reason, g.family ?? g.role, g.manifestSession]),
    [['manifest from another run', 'engine', 'run-earlier'], ['ledger-unavailable', 'children', undefined],
      ['manifest from another run', 'children', 'run-earlier']]);
});

test('exit census rejects PID reuse and missing native exits', () => {
  const i = input(), exits = i.targets.map(t => ({ ...t, exitCode: 0, atMs: at }));
  const pidGaps = r => r.exitAccountingGaps.filter(g => g.pid === 11).map(g => g.reason);
  assert.deepEqual(pidGaps(census.exitResult(i, { processExits: exits }, 'receipt')), ['unrecorded_exit']);
  exits[1].creationFileTimeUtc = (BigInt(birth) + 1n).toString();
  assert.deepEqual(pidGaps(census.exitResult(i, { processExits: exits }, 'receipt')),
    ['identity-bound OS exit code/time unavailable through teardown']);
});

test('B11 millisecond starts bind to exact owned CIM births without tolerance or ambiguous reuse', () => {
  const startedAt = '2026-10-01T00:00:00.123Z';
  const current = { pid: 11, executable: exe, creationFileTimeUtc: birth };
  const bound = census.exitTargets(run, { pid: 10, children: [{ ...child, startedAt }] }, [current]);
  assert.equal(bound.targets[1].creationFileTimeUtc, birth);
  assert.equal(bound.targets[1].creationTimeSource, 'CIM');
  assert.deepEqual(reasons(bound.gaps), ['ledger-unavailable', 'ledger-unavailable']);
  const ambiguous = census.exitTargets(run, { pid: 10, children: [{ ...child, startedAt }] },
    [current, { ...current, creationFileTimeUtc: (BigInt(birth) + 1n).toString() }]);
  assert.ok(ambiguous.gaps.some(g => /multiple owned births/.test(g.reason)));
});

test('Spec F §6.1: a child the Engine starts during the stop is a late target, accounted from the ledger when REQUESTED', async t => {
  const { targets, result } = await observe(t, {
    write: l => { l.engineStart(); l.childStart('c-llama', 'LLAMA_SERVER', 11, llamaBirth); },
    observed: [llamaRow],
    exitsFor: exitAll(t => (t.role === 'engine' ? 0 : STOP)),
    duringStop: l => {
      l.childExit('c-llama');
      l.childStart('c-late', 'EXTRACTION', 21, extractionBirth); l.childExit('c-late');
      l.childStart('c-late-crash', 'EXTRACTION', 22, extractionBirth);
      l.childExit('c-late-crash', { exitClass: 'UNEXPECTED', code: 3, forced: false });
      l.engineExit({});
    },
  });
  assert.ok(!targets.some(x => x.pid === 21));
  assert.deepEqual(result.lateTargets?.map(x => [x.childId, x.ledgerClass, x.code]),
    [['c-late', 'REQUESTED', STOP], ['c-late-crash', 'UNEXPECTED', 3]]);
  assert.deepEqual(result.exitAccountingGaps.map(g => [g.pid, g.reason]), [[22, 'late_target_not_requested']]);
  assert.equal(runner.__test.buildStopReport({ ...result, runId: run.runId }).lateTargets.length, 2);
});

test('CH9: a console host is a target of its console parent, accounted only inside the exit grace', async t => {
  const hostBirth = (BigInt(llamaBirth) + 10000n).toString();
  const host = { pid: 13, Name: 'conhost.exe', ParentProcessId: 11, creationFileTimeUtc: hostBirth, executable: conhostExe };
  const stray = { pid: 16, Name: 'conhost.exe', ParentProcessId: 999, creationFileTimeUtc: hostBirth, executable: conhostExe };
  const run1 = hostExitMs => observe(t, {
    observed: [llamaRow, host, stray],
    write: l => { l.engineStart(); l.childStart('c-llama', 'LLAMA_SERVER', 11, llamaBirth); },
    exitsFor: targets => exitAll(x => (x.role === 'engine' ? 0 : x.role === 'console-host' ? 0 : STOP))(targets)
      .map(e => (e.pid === 13 ? { ...e, atMs: hostExitMs } : e)),
    duringStop: l => { l.childExit('c-llama'); l.engineExit({}); },
  });
  const inside = await run1(at + census.CONHOST_EXIT_GRACE_MS);
  const hostTarget = inside.targets.find(x => x.pid === 13);
  assert.equal(hostTarget?.role, 'console-host');
  assert.equal(hostTarget.consoleParent, `11/${llamaBirth}`);
  assert.equal(inside.result.processExits.find(e => e.pid === 13).accounted, true);
  assert.deepEqual(inside.result.exitAccountingGaps.map(g => [g.pid, g.reason]), [[16, 'orphan_console_host']]);
  const late = await run1(at + census.CONHOST_EXIT_GRACE_MS + 1);
  assert.deepEqual(late.result.exitAccountingGaps.map(g => [g.pid, g.reason]), [[16, 'orphan_console_host'], [13, 'orphan_console_host']]);
});

test('Spec F §6.1: an exit time before 2001 is unknown; the code still counts', async t => {
  // The FILETIME epoch (1601) and 2000-12-31 as the held handle can report them: never a time.
  const i = input();
  for (const atMs of [-11644473600000, Date.UTC(2000, 11, 31)]) {
    const fallback = census.exitResult(i, { processExits: i.targets.map(x => ({ ...x, exitCode: 0, atMs })) }, 'receipt');
    assert.deepEqual(fallback.processExits.map(e => [e.atMs, e.exitTimeSource]), i.targets.map(() => [null, 'unknown']));
  }
  const { result } = await observe(t, {
    observed: [llamaRow],
    write: l => { l.engineStart(); l.childStart('c-llama', 'LLAMA_SERVER', 11, llamaBirth); },
    exitsFor: targets => exitAll(x => (x.role === 'engine' ? 0 : STOP))(targets)
      .map(e => (e.pid === 11 ? { ...e, atMs: -11644473600000 } : e)),
    duringStop: l => { l.childExit('c-llama'); l.engineExit({}); },
  });
  const llama = result.processExits.find(e => e.pid === 11);
  assert.equal(llama?.atMs, null); assert.equal(llama.exitTimeSource, 'unknown');
  assert.equal(llama.expectedTermination, true); assert.equal(result.exitAccountingComplete, true);
});

test('Engine-owned stderr fallback (until the extraction sandbox writes the ledger) accepts only code 1 with matching identity and prior intent', t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/engine-intent-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const startedAt = '2026-10-01T00:00:00.123Z';
  const extraction = { ...manifest.children[1], id: 'extraction', startedAt };
  const i = census.exitTargets(run, { ...manifest, children: [extraction] },
    [{ pid: extraction.pid, executable: exe, creationFileTimeUtc: birth }]);
  const at = Date.parse(startedAt) + 2000;
  i.observedAfterMs = at - 100;
  const intent = { enginePid: 10, engineStartedAt: startedAt, child: extraction,
    requestedAtMs: at, reason: 'graceful-close-fallback' };
  const exits = i.targets.map(t => ({ ...t, exitCode: t.kind === 'EXTRACTION' ? 1 : 0, atMs: at + 1 }));
  const log = path.join(dir, 'backend.stderr.log');
  fs.writeFileSync(log, census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent) + '\n');
  const receipts = [{ ...intent, source: log }];
  const classify = intents => census.exitResult(i, { processExits: exits, engineTerminationIntents: intents }, 'os-exits');
  assert.equal(classify(receipts).processExits[1].expectedTermination, true);
  assert.equal(classify(receipts).processExits[1].terminationIntent.source, log);
  assert.deepEqual(census.readEngineTerminationIntents(log), receipts);
  assert.equal(classify([]).processExits[1].expectedTermination, false);
  for (const bad of [
    { ...intent, enginePid: 99 }, { ...intent, engineStartedAt: '2026-10-01T00:00:00.124Z' },
    { ...intent, reason: 'crash' }, { ...intent, requestedAtMs: at + 2 }, { ...intent, requestedAtMs: at - 101 },
    ...[{ pid: 99 }, { id: 'reused' }, { kind: 'LLAMA_SERVER' }, { executable: exe + '.other' },
      { startedAt: '2026-10-01T00:00:00.1230001Z' }].map(change => ({ ...intent, child: { ...extraction, ...change } })),
  ]) assert.equal(classify([bad]).processExits[1].expectedTermination, false, JSON.stringify(bad));
  exits[1].exitCode = -1073741819;
  assert.equal(classify(receipts).processExits[1].expectedTermination, false, 'native exception remains a crash');
  assert.equal(census.exitResult(i, { engineTerminationIntents: receipts }, 'os-exits').exitAccountingComplete, false);
  fs.writeFileSync(log, census.TERMINATION_INTENT_PREFIX + '{bad}\n' + census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent));
  assert.deepEqual(census.readEngineTerminationIntents(log), [], 'malformed/partial markers grant no exemption');
});

test('observer reads Engine fallback evidence from existing stderr after its pre-stop cursor', async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/engine-intent-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime')); fs.mkdirSync(path.join(dir, 'logs'));
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify(manifest));
  const l = ledger(dir); l.engineStart();
  const scope = path.join(dir, 'scope.json'); fs.writeFileSync(scope, JSON.stringify({ processes: input().targets }));
  const log = path.join(dir, 'logs/backend.stderr.log');
  const at = Date.parse(start) + 2000;
  const intent = { enginePid: 10, engineStartedAt: start, child: manifest.children[1],
    requestedAtMs: at, reason: 'graceful-close-fallback' };
  fs.writeFileSync(log, census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent) + '\n');
  let clock = at - 100, args;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = f => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(run, dir, dir, scope, {
    launch: a => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async ms => {
      clock += ms;
      if (fs.existsSync(flag('-FinishFile')) && !fs.existsSync(flag('-OutputFile'))) {
        fs.appendFileSync(log, census.TERMINATION_INTENT_PREFIX + JSON.stringify(intent) + '\n');
        l.engineExit({});
        fs.writeFileSync(flag('-OutputFile'), JSON.stringify({ processExits: JSON.parse(fs.readFileSync(flag('-InputFile'))).targets
          .map(t => ({ ...t, exitCode: t.kind === 'EXTRACTION' ? 1 : 0, atMs: at + 1 })), killedPids: [] }));
      }
    },
  });
  const result = await observer.finish([]);
  assert.equal(result.processExits.find(e => e.pid === 12).expectedTermination, true);
  assert.equal(result.processExits.find(e => e.pid === 12).terminationIntent.source, log);
  assert.equal(result.processExits.find(e => e.pid === 10).ledgerClass, 'REQUESTED');
  // The llama child has neither a ledger record nor a marker: an honest gap, not a clean exit.
  assert.deepEqual(result.exitAccountingGaps.map(g => [g.pid, g.reason]), [[undefined, 'ledger-unavailable'], [11, 'unrecorded_exit']]);
});

test('missing observer readiness is bounded and cannot prove clean native exits', async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-test-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  let clock = 0;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => { proc.exitCode = 1; };
  const observer = await census.startStopExitCensus(run, dir, dir, null,
    { launch: () => proc, now: () => clock, pause: async ms => { clock += ms; } });
  const result = await observer.finish([]);
  assert.equal(clock, 15000); assert.equal(result.exitAccountingComplete, false);
  assert.ok(result.exitAccountingGaps.some(g => /failed before shutdown/.test(g.reason)));
});

test('the PowerShell observer holds a real process handle and reports its code, OS exit time and the stop\'s kills', { skip: process.platform !== 'win32' && 'Windows process handles' }, async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-ps1-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const go = path.join(dir, 'go');
  const target = spawn(process.execPath, ['-e',
    'const fs=require("fs");const f=process.argv[1];setInterval(()=>{if(fs.existsSync(f))process.exit(3)},50);setTimeout(()=>process.exit(9),120000)', go],
  { stdio: 'ignore', windowsHide: true });
  t.after(() => { try { target.kill(); } catch { /* exited */ } });
  const exited = new Promise(resolve => target.once('exit', resolve));
  const seen = (await actuator.lookupProcessIdentities([target.pid])).get(target.pid);
  assert.ok(seen?.present && seen.creationFileTime && seen.executable, JSON.stringify(seen));
  const files = Object.fromEntries(['input', 'output', 'ready', 'finish'].map(n => [n, path.join(dir, `${n}.json`)]));
  fs.writeFileSync(files.input, JSON.stringify({ targets: [{ pid: target.pid, creationFileTimeUtc: seen.creationFileTime,
    executable: seen.executable, role: 'llama-server', creationTimeSource: 'CIM' }], gaps: [] }));
  const observer = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
    path.join(path.dirname(fileURLToPath(import.meta.url)), 'lib/stop-exit-census.ps1'), '-InputFile', files.input,
    '-OutputFile', files.output, '-ReadyFile', files.ready, '-FinishFile', files.finish], { stdio: 'ignore', windowsHide: true });
  const until = async (cond, ms) => { const end = Date.now() + ms; while (!cond() && Date.now() < end) await new Promise(r => setTimeout(r, 50)); };
  await until(() => fs.existsSync(files.ready), 30000);
  assert.ok(fs.existsSync(files.ready), 'observer ready');
  fs.writeFileSync(go, '');
  assert.equal(await exited, 3);
  fs.writeFileSync(files.finish, JSON.stringify({ killedPids: [target.pid] }));
  await until(() => fs.existsSync(files.output), 30000);
  observer.kill();
  const out = JSON.parse(fs.readFileSync(files.output, 'utf8').replace(/^\uFEFF/, ''));
  assert.deepEqual(out.gaps, []);
  assert.equal(out.processExits.length, 1);
  const [exit] = out.processExits;
  assert.equal(exit.pid, target.pid); assert.equal(exit.exitCode, 3); assert.equal(exit.exitTimeSource, 'os');
  assert.ok(exit.atMs >= ledgerLib.EARLIEST_KNOWN_MS && exit.atMs <= Date.now(), String(exit.atMs));
  assert.deepEqual(out.killedPids, [target.pid]);
});

// Recorded live on a normal quit (verify-f3 runs b2 and oc): the birth the Engine's ledger holds
// (GetProcessTimes, 100 ns) beside the CIM read of the same process (truncated to the microsecond).
const RECORDED_BIRTHS = [['134357749947015062', '134357749947015060'], ['134357756865482994', '134357756865482990'],
  ['134357756865430732', '134357756865430730']];

test('Spec F §6.1: one birth rule: an exact ledger birth is the CIM birth of the same process; a pid reused in the next microsecond is not', () => {
  for (const [exact, cim] of RECORDED_BIRTHS) {
    assert.equal(actuator.sameBirth(exact, cim), true, `${exact} vs ${cim}`);
    // PID reuse: a process born in the next microsecond, inside the same millisecond, is another one.
    const next = (BigInt(cim) + 10n).toString();
    assert.equal(BigInt(next) / 10000n, BigInt(exact) / 10000n, 'the same millisecond');
    assert.equal(actuator.sameBirth(exact, next), false, `${exact} vs ${next}`);
  }
});

test('Spec F §6.1: a ledger child is observed under the shared rule: its CIM row binds to it and the held handle\'s exact birth must be its own', () => {
  const [exact, cim] = RECORDED_BIRTHS[0];
  const records = (family, list) => ({ present: true, records: list.map(([type, line]) => ({ type, line, writer: { role: family } })) });
  const ledgerOf = exits => ({
    engine: records('dev-runner', [['engine-start', { incarnation: { session: run.runId, n: 1 }, pid: 10, creationFileTime: birth, executable: exe }]]),
    children: records('engine', [['child-spawn', { childId: 'c-x', kind: 'EXTRACTION', owner: { pid: 10, creationFileTime: birth } }],
      ['child-start', { childId: 'c-x', pid: 24284, creationFileTime: exact, executable: exe }], ...exits]),
  });
  const hostBirth = '134357749947062890';
  const observedRows = [{ pid: 24284, creationFileTimeUtc: cim, executable: exe, role: 'extraction-child' },
    { pid: 26524, Name: 'conhost.exe', ParentProcessId: 24284, creationFileTimeUtc: hostBirth, executable: conhostExe }];
  const i = census.exitTargets(run, { pid: 10, children: [] }, observedRows, ledgerOf([]));
  const child = i.targets.find(x => x.pid === 24284);
  assert.deepEqual([child?.creationFileTimeUtc, child?.creationTimeSource, child?.birthTick], [exact, 'ledger', '10']);
  assert.equal(i.targets.find(x => x.pid === 26524)?.consoleParent, `24284/${exact}`);
  assert.deepEqual(i.gaps, []);
  const exitsAs = osBirth => i.targets.map(x => ({ pid: x.pid, creationFileTimeUtc: x.creationFileTimeUtc, executable: x.executable,
    exitCode: x.role === 'engine' ? 0 : x.role === 'console-host' ? 0 : STOP, atMs: at,
    osCreationFileTimeUtc: x.pid === 24284 ? osBirth : x.pid === 26524 ? '134357749947062898' : x.creationFileTimeUtc }));
  const closed = ledgerOf([['child-exit', { childId: 'c-x', exitClass: 'REQUESTED', reason: 'QUIT', code: STOP, forced: true }]]);
  closed.engine.records.push({ type: 'engine-exit', line: { incarnation: { session: run.runId, n: 1 }, exitClass: 'REQUESTED',
    reason: 'QUIT', code: 0 }, writer: { role: 'dev-runner' } });
  const clean = census.exitResult(i, { processExits: exitsAs(exact) }, 'receipt', closed);
  assert.deepEqual(clean.exitAccountingGaps, []);
  assert.deepEqual(clean.processExits.map(e => [e.pid, e.role, e.role === 'console-host' ? e.accounted : e.expectedTermination]),
    [[10, 'engine', true], [24284, 'extraction-child', true], [26524, 'console-host', true]]);
  // The held handle names a process born in the next microsecond: the pid was reused, nothing is observed.
  const reused = census.exitResult(i, { processExits: exitsAs((BigInt(cim) + 10n).toString()) }, 'receipt', closed);
  assert.deepEqual(reused.exitAccountingGaps.map(g => [g.pid, g.reason]), [
    [24284, 'identity-bound OS exit code/time unavailable through teardown'], [26524, 'orphan_console_host']]);
});

/** The exact birth of a live process, from its handle (GetProcessTimes), as the Engine records a child's. */
function exactBirthOf(pid) {
  const r = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
    `[Diagnostics.Process]::GetProcessById(${pid}).StartTime.ToUniversalTime().ToFileTimeUtc()`], { encoding: 'utf8', windowsHide: true });
  const value = String(r.stdout).trim();
  assert.match(value, /^[1-9]\d+$/, `exact birth of ${pid}: ${r.stdout}${r.stderr}`);
  return value;
}

test('Spec F §6.1: the PowerShell observer holds a real ledger child whose exact birth is not CIM\'s, and refuses a reused pid', { skip: process.platform !== 'win32' && 'Windows process handles' }, async t => {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-birth-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  const go = path.join(dir, 'go');
  // About 9 in 10 processes have an exact birth CIM cannot represent (last digit not 0); take one.
  let target, exact;
  for (let tries = 0; tries < 20 && !target; tries += 1) {
    const p = spawn(process.execPath, ['-e',
      'const fs=require("fs");const f=process.argv[1];setInterval(()=>{if(fs.existsSync(f))process.exit(3)},50);setTimeout(()=>process.exit(9),120000)', go],
    { stdio: 'ignore', windowsHide: true });
    t.after(() => { try { p.kill(); } catch { /* exited */ } });
    const b = exactBirthOf(p.pid);
    if (BigInt(b) % 10n !== 0n) { target = p; exact = b; } else p.kill();
  }
  assert.ok(target, 'a process whose exact birth does not end in 0');
  const exited = new Promise(resolve => target.once('exit', resolve));
  const seen = (await actuator.lookupProcessIdentities([target.pid])).get(target.pid);
  assert.ok(seen?.present && seen.creationFileTime && seen.executable, JSON.stringify(seen));
  assert.notEqual(seen.creationFileTime, exact, 'CIM truncates the exact birth');
  assert.equal(actuator.sameBirth(exact, seen.creationFileTime), true, `${exact} vs CIM ${seen.creationFileTime}`);
  const l = ledger(dir);
  l.engineStart(); l.childStart('c-real', 'EXTRACTION', target.pid, exact, undefined, seen.executable);
  const i = census.exitTargets(run, { pid: 10, children: [] }, [], census.readRunLedger(dir));
  const own = i.targets.find(x => x.pid === target.pid);
  assert.ok(own, JSON.stringify(i.targets));
  // Another process at this pid, born in the next microsecond of the same millisecond.
  const r = BigInt(exact) - (BigInt(exact) % 10n);
  const reusedBirth = (BigInt(exact) % 10000n < 9990n ? r + 10n : r - 10n).toString();
  const files = Object.fromEntries(['input', 'output', 'ready', 'finish'].map(n => [n, path.join(dir, `${n}.json`)]));
  fs.writeFileSync(files.input, JSON.stringify({ targets: [own, { ...own, creationFileTimeUtc: reusedBirth }], gaps: [] }));
  const observer = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
    path.join(path.dirname(fileURLToPath(import.meta.url)), 'lib/stop-exit-census.ps1'), '-InputFile', files.input,
    '-OutputFile', files.output, '-ReadyFile', files.ready, '-FinishFile', files.finish], { stdio: 'ignore', windowsHide: true });
  t.after(() => { try { observer.kill(); } catch { /* exited */ } });
  const until = async (cond, ms) => { const end = Date.now() + ms; while (!cond() && Date.now() < end) await new Promise(res => setTimeout(res, 50)); };
  await until(() => fs.existsSync(files.ready), 30000);
  assert.ok(fs.existsSync(files.ready), 'observer ready');
  fs.writeFileSync(go, '');
  assert.equal(await exited, 3);
  fs.writeFileSync(files.finish, JSON.stringify({ killedPids: [] }));
  await until(() => fs.existsSync(files.output), 30000);
  const out = JSON.parse(fs.readFileSync(files.output, 'utf8').replace(/^﻿/, ''));
  assert.deepEqual(out.gaps.map(g => [g.pid, g.reason, g.recordedStart, g.observedStart]),
    [[target.pid, 'PID/start/executable identity mismatch; no target observation', reusedBirth, exact]]);
  assert.deepEqual(out.processExits.map(e => [e.pid, e.creationFileTimeUtc, e.osCreationFileTimeUtc, e.exitCode, e.exitTimeSource]),
    [[target.pid, exact, exact, 3, 'os']]);
  const verdict = census.exitResult({ ...i, targets: [own], gaps: [] }, out, files.output, census.readRunLedger(dir));
  assert.deepEqual(verdict.processExits.map(e => [e.pid, e.exitCode]), [[target.pid, 3]]);
  assert.ok(!verdict.exitAccountingGaps.some(g => /identity-bound/.test(g.reason)), JSON.stringify(verdict.exitAccountingGaps));
});

/** An in-memory run ledger: the run's Engine (pid 10) and the given children records. */
function memoryLedger(childRecords, engineRecords = []) {
  const records = (role, list) => ({ present: true, records: list.map(([type, line]) => ({ type, line, writer: { role } })) });
  return {
    engine: records('dev-runner', [['engine-start', { incarnation: { session: run.runId, n: 1 }, pid: 10, creationFileTime: birth,
      executable: exe }], ...engineRecords]),
    children: records('engine', childRecords),
  };
}
const ownerRef = { pid: 10, creationFileTime: birth };
const startOf = (childId, kind, pid, childBirth, contained = true) => [
  ['child-spawn', { childId, kind, owner: ownerRef }],
  ['child-start', { childId, pid, creationFileTime: childBirth, executable: exe, contained }]];
const exitOf = (childId, fields = {}) => ['child-exit', { childId, exitClass: 'REQUESTED', reason: 'SELF_TEST', code: STOP,
  forced: true, exitedAt: new Date(at).toISOString(), observedAt: new Date(at + 5).toISOString(), ...fields }];
const plus = (ft, ticks) => (BigInt(ft) + BigInt(ticks)).toString();
/** A scope of snapshots taken at `at + ms` for each ms, each holding the rows alive then. */
const snapshots = (...series) => ({ snapshots: series.map(([ms, rows]) => ({ atMs: at + ms, processes: rows })) });
/**
 * Arms the census through its entry point (the scope file and manifest on disk, the ledger read
 * through the runtime hook) and returns what it armed: targets, gaps and accounted processes.
 */
async function armed(t, manifestJson, scope, ledgerRecords) {
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-arm-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify(manifestJson));
  const scopeFile = path.join(dir, 'scope.json'); fs.writeFileSync(scopeFile, JSON.stringify(scope));
  let args;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  await census.startStopExitCensus(run, dir, dir, scopeFile, { readLedger: () => ledgerRecords, now: () => 0, pause: async () => {},
    launch: a => { args = a; fs.writeFileSync(a[a.indexOf('-ReadyFile') + 1], '{}'); return proc; } });
  const input = JSON.parse(fs.readFileSync(args[args.indexOf('-InputFile') + 1], 'utf8'));
  return { ...input, accountedProcesses: input.accountedProcesses ?? [] };
}

test('S15 / CH9: a console host of a closed ledger child is accounted within the child\'s recorded lifetime, an orphan after it', async t => {
  const host = { pid: 13, name: 'conhost.exe', role: 'console-host', parentPid: 11, creationFileTimeUtc: plus(llamaBirth, 10000) };
  const llama = { ...llamaRow, parentPid: 10 };
  const closedLedger = (fields) => memoryLedger([...startOf('c-first', 'LLAMA_SERVER', 11, llamaBirth), exitOf('c-first', fields)]);
  const verdict = (hostLastMs, fields) => armed(t, { pid: 10, children: [] },
    snapshots([-5000, [llama, host]], [hostLastMs, [host]]), closedLedger(fields));

  const inside = await verdict(census.CONHOST_EXIT_GRACE_MS);
  assert.deepEqual(inside.gaps, []);
  assert.deepEqual(inside.accountedProcesses.map(a => [a.pid, a.role, a.parent]), [[13, 'console-host', `11/${llamaBirth}`]]);
  const late = await verdict(census.CONHOST_EXIT_GRACE_MS + 1);
  assert.deepEqual(late.gaps.map(g => [g.pid, g.reason]), [[13, 'orphan_console_host']]);
  assert.match(late.gaps[0].detail, /seen 2001 ms after its parent 11's recorded exit/);
  // An unknown exit time reads as unknown: the owner's observation of the exit bounds the lifetime.
  const unknown = await verdict(census.CONHOST_EXIT_GRACE_MS + 5, { exitedAt: null });
  assert.deepEqual(unknown.gaps, []);
  const neither = await verdict(0, { exitedAt: null, observedAt: null });
  assert.deepEqual(neither.gaps.map(g => [g.pid, g.reason]), [[13, 'orphan_console_host']]);
  assert.match(neither.gaps[0].detail, /exit time unknown in the ledger/);
  // Born before its supposed parent (a reused pid): not its console host.
  const older = await armed(t, { pid: 10, children: [] },
    snapshots([0, [{ ...host, creationFileTimeUtc: plus(llamaBirth, -10) }]]), closedLedger());
  assert.deepEqual(older.gaps.map(g => [g.pid, g.reason]), [[13, 'orphan_console_host']]);
});

test('S15 / CH9: a console host whose parent is not a ledger child stays an orphan, one row however often it was seen', async t => {
  const stray = { pid: 16, name: 'conhost.exe', role: 'console-host', parentPid: 999, creationFileTimeUtc: plus(llamaBirth, 10000) };
  const other = { pid: 17, name: 'node.exe', role: 'other', parentPid: 10, creationFileTimeUtc: plus(llamaBirth, 20000) };
  const i = await armed(t, { pid: 10, children: [] },
    snapshots([0, [stray, other]], [700, [stray, other]], [1400, [stray, other]]), memoryLedger([]));
  assert.deepEqual(i.gaps.map(g => [g.pid, g.reason]), [[16, 'orphan_console_host'], [17, 'unaccounted_process']]);
  // Rows straight from several snapshots (no seen times) are one process too.
  const flat = await armed(t, { pid: 10, children: [] }, { processes: [stray, stray, other, other] }, memoryLedger([]));
  assert.deepEqual(flat.gaps.map(g => [g.pid, g.reason]), [[16, 'orphan_console_host'], [17, 'unaccounted_process']]);
});

test('S15: a closed ledger child seen alive after its recorded exit is unaccounted', async t => {
  const llama = { ...llamaRow, parentPid: 10 };
  const ledgerRecords = memoryLedger([...startOf('c-first', 'LLAMA_SERVER', 11, llamaBirth), exitOf('c-first')]);
  const within = await armed(t, { pid: 10, children: [] }, snapshots([census.CONHOST_EXIT_GRACE_MS, [llama]]), ledgerRecords);
  assert.deepEqual(within.gaps, []);
  const after = await armed(t, { pid: 10, children: [] }, snapshots([60000, [llama]]), ledgerRecords);
  assert.deepEqual(after.gaps.map(g => [g.pid, g.reason]), [[11, 'unaccounted_process']]);
  assert.match(after.gaps[0].detail, /after its recorded exit/);
});

test('Spec B §2.9 / §5.1: a tier C helper in the Engine\'s job is accounted as a contained helper, its console host with it', async t => {
  const helperBirth = plus(llamaBirth, 50000);
  const probe = { pid: 30, name: 'powershell.exe', role: 'other', parentPid: 10, creationFileTimeUtc: helperBirth };
  const probeHost = { pid: 31, name: 'conhost.exe', role: 'console-host', parentPid: 30, creationFileTimeUtc: plus(helperBirth, 30) };
  const smi = { pid: 32, name: 'nvidia-smi.exe', role: 'other', parentPid: 10, creationFileTimeUtc: plus(helperBirth, 100) };
  const tesseract = { pid: 33, executable: 'C:\\Program Files\\Tesseract-OCR\\tesseract.exe', role: 'other', parentPid: 12,
    creationFileTimeUtc: plus(extractionBirth, 100) };
  const stranger = { pid: 34, name: 'node.exe', role: 'other', parentPid: 10, creationFileTimeUtc: plus(helperBirth, 200) };
  const extraction = { pid: 12, creationFileTimeUtc: extractionBirth, executable: exe, role: 'extraction-child', parentPid: 10 };
  const rows = [probe, probeHost, smi, tesseract, stranger, extraction];
  const ledgerRecords = memoryLedger(startOf('c-x', 'EXTRACTION', 12, extractionBirth));
  const contained = { pid: 10, incarnation: { session: run.runId, n: 1 }, containment: { status: 'CONTAINED' }, children: [] };

  const i = await armed(t, contained, snapshots([0, rows]), ledgerRecords);
  assert.deepEqual(i.accountedProcesses.map(a => [a.pid, a.role, a.parent]), [[30, 'tier-c-helper', `10/${birth}`],
    [32, 'tier-c-helper', `10/${birth}`], [33, 'tier-c-helper', `12/${extractionBirth}`], [31, 'console-host', `30/${helperBirth}`]]);
  assert.deepEqual(i.gaps.map(g => [g.pid, g.reason]), [[34, 'unaccounted_process']], 'not an allowlisted executable');

  // Without the Engine's job (containment unavailable), job membership is not shown: not accounted.
  const uncontained = await armed(t, { ...contained, containment: { status: 'UNAVAILABLE', reason: 'CreateJobObjectW:5' } },
    snapshots([0, rows]), ledgerRecords);
  assert.deepEqual(uncontained.gaps.map(g => [g.pid, g.reason]), [[30, 'unaccounted_process'], [31, 'orphan_console_host'],
    [32, 'unaccounted_process'], [34, 'unaccounted_process']]);
  assert.match(uncontained.gaps[0].detail, /outside the Engine job \(containment UNAVAILABLE\)/);
  // A child started outside the job (`contained: false`) cannot vouch for its helpers either.
  const outside = await armed(t, contained, snapshots([0, rows]),
    memoryLedger(startOf('c-x', 'EXTRACTION', 12, extractionBirth, false)));
  assert.deepEqual(outside.gaps.map(g => [g.pid, g.reason]), [[33, 'unaccounted_process'], [34, 'unaccounted_process']]);
});

test('S15 replay (verifier b56 run2): a quit after a self-test, with Engine helpers, accounts for every observed process once', async t => {
  const fixtureDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures/exit-census-run2');
  const fixture = JSON.parse(fs.readFileSync(path.join(fixtureDir, 'ledger.json'), 'utf8'));
  const observerOutput = JSON.parse(fs.readFileSync(path.join(fixtureDir, 'process-exits.json'), 'utf8').replace(/^\uFEFF/, ''));
  const dir = fs.mkdtempSync(path.join(root, 'tmp/exit-census-run2-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'runtime/manifest.json'), JSON.stringify(fixture.manifest));
  const ledgerDir = ledgerLib.ledgerDirectory(dir);
  const writers = {
    engine: ledgerLib.createLedgerWriter({ ledgerDir, family: 'engine', writer: { role: 'dev-runner', pid: 16, startedAt: Date.UTC(2026, 9, 6) } }),
    children: ledgerLib.createLedgerWriter({ ledgerDir, family: 'children', writer: { role: 'engine', pid: 2596, startedAt: Date.UTC(2026, 9, 6) } }),
  };
  const write = (phase) => {
    for (const family of ['engine', 'children']) for (const [type, fields] of phase[family] ?? []) writers[family].append(type, fields);
  };
  write(fixture.beforeArming);
  let clock = 0, args, stopped = false;
  const proc = new EventEmitter(); proc.exitCode = null; proc.kill = () => {};
  const flag = f => args[args.indexOf(f) + 1];
  const observer = await census.startStopExitCensus(fixture.run, dir, dir, path.join(fixtureDir, 'scope.json'), {
    launch: a => { args = a; fs.writeFileSync(flag('-ReadyFile'), '{}'); return proc; }, now: () => clock,
    pause: async ms => {
      clock += ms;
      if (fs.existsSync(flag('-FinishFile')) && !stopped) {
        stopped = true;
        write(fixture.duringStop);
        fs.writeFileSync(flag('-OutputFile'), JSON.stringify(observerOutput));
      }
    },
  });
  const targets = JSON.parse(fs.readFileSync(flag('-InputFile'))).targets;
  const result = await observer.finish([]);
  assert.deepEqual(targets.map(x => [x.pid, x.role]), [[2596, 'engine'], [31508, 'llama-server'], [8440, 'extraction-child'],
    [8432, 'console-host'], [21544, 'console-host'], [36364, 'console-host']]);
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.exitAccountingComplete, true);
  assert.deepEqual(result.ledgerSummary, { requested: 5, unexpected: 0, ownerDied: 0, unknown: 0, gaps: 0 });
  // The 26 observed processes: 6 targets, 2 closed ledger children, and 18 accounted beside them.
  assert.deepEqual((result.accountedProcesses ?? []).map(a => [a.pid, a.role]).sort((a, b) => a[0] - b[0]), [
    [1468, 'tier-c-helper'], [8176, 'console-host'], [9672, 'tier-c-helper'], [12324, 'tier-c-helper'], [14048, 'console-host'],
    [18024, 'console-host'], [20100, 'console-host'], [22088, 'console-host'], [22216, 'tier-c-helper'], [22284, 'console-host'],
    [26744, 'tier-c-helper'], [26904, 'console-host'], [28652, 'tier-c-helper'], [29476, 'console-host'], [29896, 'console-host'],
    [31044, 'tier-c-helper'], [31404, 'tier-c-helper'], [33912, 'console-host']]);
  assert.deepEqual([29896, 8176].map(pid => result.accountedProcesses.find(a => a.pid === pid).parent),
    ['29312/134358032760569140', '16120/134358032855035510'], 'the closed chat servers\' console hosts');
  assert.deepEqual(result.processExits.filter(e => e.role !== 'console-host').map(e => [e.pid, e.ledgerClass, e.ledgerReason, e.expectedTermination]),
    [[2596, 'REQUESTED', 'QUIT', true], [31508, 'REQUESTED', 'QUIT', true], [8440, 'REQUESTED', 'QUIT', true]]);
});
