import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';

const instant = n => new Date(Date.UTC(2026, 9, 8, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(instant(n));
const incarnation = { session: 'classify', n: 1 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const rec = (type, line) => ({ type, line, writer: { role: 'engine', pid: 100 } });
const run = { runId: 'classify', pids: { backendRootPid: 100 } };
function history(code, extra = [], image = 'tesseract.exe') {
  return { engine: { present: true, records: [rec('engine-start', { incarnation, pid: 100,
    creationFileTime: birth(0), executable: 'C:\\test\\java.exe' }),
  rec('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED', reason: 'QUIT',
    exitedAt: instant(20), observedAt: instant(20) })] }, children: { present: true, records: [
    rec('job-process-start', { witnessId: 'j-1', owner, incarnation, pid: 102,
      creationFileTime: birth(2), executable: `C:\\test\\${image}`, contained: true,
      awaited: false }),
    rec('job-process-exit', { witnessId: 'j-1', owner, incarnation, pid: 102,
      creationFileTime: birth(2), code, exitClass: 'UNEXPECTED', reason: null,
      exitedAt: instant(3), observedAt: instant(3), forced: false }), ...extra] } };
}
const receipt = (code, reason, exitClass = 'REQUESTED') => [
  rec('helper-start', { helperId: 'h-1', owner, incarnation, pid: 102, creationFileTime: birth(2) }),
  rec('helper-exit', { helperId: 'h-1', owner, incarnation, pid: 102, creationFileTime: birth(2),
    code, exitClass, reason, forced: false, exitedAt: instant(3), observedAt: instant(3) }),
];
const window = end => [rec('helper-window-start', { intentId: 'i-1', owner, incarnation,
  image: 'tesseract.exe', reason: 'Tika Tesseract availability', requestedAt: instant(1), deadlineAt: instant(5) }),
rec('helper-window-end', { intentId: 'i-1', owner, incarnation, image: 'tesseract.exe',
  reason: 'Tika Tesseract availability', closedAt: instant(end) })];
const cls = ledger => census.ledgerView(ledger, run.runId).witnesses[0].exit;

test('W3: handled Tika code-1 availability probe is intended only inside a closed window', () => {
  assert.equal(cls(history(1, window(4))).exitClass, 'REQUESTED');
  assert.match(cls(history(1, window(4))).reason, /availability/);
  assert.equal(cls(history(1, window(2))).exitClass, 'UNEXPECTED');
  assert.equal(cls(history(1, window(4).slice(0, 1))).exitClass, 'UNEXPECTED');
  assert.equal(cls(history(1, window(4), 'powershell.exe')).exitClass, 'UNEXPECTED');
});
test('W4: owner interruption receipt wins over the witness', () => {
  assert.equal(cls(history(1, receipt(1, 'disk encryption probe: interrupted'))).exitClass, 'REQUESTED');
});
test('managed child owner record takes precedence for identical birth and code', () => {
  const extra = [rec('child-spawn', { childId: 'c-1', owner, kind: 'EXTRACTION' }),
    rec('child-start', { childId: 'c-1', pid: 102, creationFileTime: birth(2), executable: 'C:\\test\\java.exe' }),
    rec('child-exit', { childId: 'c-1', code: 1246953473, exitClass: 'REQUESTED', reason: 'QUIT', forced: true })];
  assert.equal(cls(history(1246953473, extra)).exitClass, 'REQUESTED');
  extra[1].line.creationFileTime = birth(1);
  assert.equal(cls(history(1246953473, extra)).exitClass, 'UNEXPECTED');
});
test('a crash during intended completion or termination stays unexpected; an unowned zero stays unexpected', () => {
  assert.equal(cls(history(-1073741819, receipt(-1073741819, 'awaited completion'))).exitClass, 'UNEXPECTED');
  assert.equal(cls(history(-1073741819, window(4))).exitClass, 'UNEXPECTED');
  const unowned = history(0);
  Object.assign(unowned.children.records[0].line, { awaited: true, expectedCode: 0, reason: 'HELPER_COMPLETION' });
  Object.assign(unowned.children.records[1].line, { exitClass: 'REQUESTED', reason: 'HELPER_COMPLETION' });
  assert.equal(cls(unowned).exitClass, 'UNEXPECTED', 'a witness cannot manufacture owner intent');
  assert.equal(cls(history(17, receipt(0, 'awaited completion'))).exitClass, 'UNKNOWN');
});
test('a managed parser records its own helpers; a foreign writer cannot impersonate that parser', () => {
  const parser = [rec('child-spawn', { childId: 'parser', owner, kind: 'EXTRACTION' }),
    rec('child-start', { childId: 'parser', pid: 101, creationFileTime: birth(1), executable: 'C:\\test\\java.exe' })];
  const extra = receipt(1, 'parser Tesseract probe: awaited completion').map(r => ({ ...r,
    writer: { role: 'engine', pid: 101, startedAt: Date.parse(instant(1)) },
    line: { ...r.line, owner: { pid: 101, creationFileTime: birth(1), instanceId: 'parser' } } }));
  assert.equal(cls(history(1, [...parser, ...extra])).exitClass, 'REQUESTED');
  extra[1].writer.pid = 999;
  assert.equal(cls(history(1, [...parser, ...extra])).exitClass, 'UNEXPECTED');
});
test('native console client binding accounts an unsampled host only inside the client lifetime', () => {
  const ledger = history(1, receipt(1, 'disk encryption probe: awaited completion'), 'powershell.exe');
  ledger.children.records.push(rec('job-process-start', { witnessId: 'host', owner, incarnation, pid: 103,
    creationFileTime: birth(2), executable: 'C:\\test\\conhost.exe', contained: true,
    parentPid: 102, parentCreationFileTime: birth(2), awaited: false }),
  rec('job-process-exit', { witnessId: 'host', owner, incarnation, pid: 103,
    creationFileTime: birth(2), code: 0, exitClass: 'UNEXPECTED', forced: false,
    exitedAt: instant(4), observedAt: instant(4) }));
  assert.equal(census.ledgerView(ledger, run.runId).witnesses[1].exit.exitClass, 'REQUESTED');
  ledger.children.records.at(-1).line.exitedAt = instant(6);
  assert.equal(census.ledgerView(ledger, run.runId).witnesses[1].exit.exitClass, 'UNEXPECTED');
});
test('a sampled executable lookup failure matches only PID plus birth', () => {
  const ledger = history(1, window(4));
  const input = census.exitTargets(run, null, [{ pid: 102, creationFileTimeUtc: birth(2),
    executable: null, parentPid: 100, lastSeenAtMs: Date.parse(instant(3)) }], ledger);
  assert.deepEqual(input.gaps, []);
  assert.equal(input.accountedProcesses[0].ledgerClass, 'REQUESTED');
});
test('Engine console host uses its owner-recorded client without requiring a self-job witness', () => {
  const ledger = history(1, window(4));
  ledger.engine.records.pop();
  const input = census.exitTargets(run, null, [{ pid: 103, parentPid: 100, name: 'conhost.exe',
    creationFileTimeUtc: birth(1), executable: null, lastSeenAtMs: Date.parse(instant(19)) }], ledger);
  ledger.engine.records.push(rec('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED',
    reason: 'QUIT', exitedAt: instant(20), observedAt: instant(20) }));
  const result = census.exitResult(input, { processExits: input.targets.map(t => ({ pid: t.pid,
    creationFileTimeUtc: t.creationFileTimeUtc, executable: null, exitCode: 0,
    atMs: Date.parse(instant(t.pid === 103 ? 21 : 20)) })) }, 'test', ledger);
  assert.deepEqual(result.exitAccountingGaps, []);
  assert.equal(result.processExits.find(p => p.pid === 103).expectedTermination, true);
});
