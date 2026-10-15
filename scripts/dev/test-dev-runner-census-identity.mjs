import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';

// Task b6d536 b101: a helper receipt names a witnessed process only on native identity. The
// member's native creator wins over the creator a receipt declares (a conflict is no match), a
// receipt's birth and the witness's, both native, match exactly, and the Engine's end cuts off a
// witnessed member at native 100 ns precision.
const at = n => `2026-10-14T10:00:${String(n).padStart(2, '0')}.000Z`;
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'census-identity', n: 1 };
const engine = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const parser = { pid: 202, creationFileTime: birth(2), instanceId: 'parser' };
const rec = (type, line, pid = 100) => ({ type, line, writer: { role: 'engine', pid } });

/** The extraction child's receipt for Tesseract 303 and the Engine job witness's record of it. */
function receiptLedger({ creator = parser, helperBirth = birth(4), receiptBirth = birth(4), withCreatorFields = true } = {}) {
  const receipt = { helperId: 'helper', owner: parser, incarnation, pid: 303, creationFileTime: receiptBirth };
  if (withCreatorFields) Object.assign(receipt, { creatorPid: parser.pid, creatorCreationFileTime: parser.creationFileTime });
  return {
    engine: { present: true, records: [rec('engine-start', { incarnation, pid: 100, creationFileTime: birth(0), executable: 'C:\\test\\java.exe' })] },
    children: { present: true, records: [
      rec('child-spawn', { childId: 'parser', owner: engine, kind: 'EXTRACTION' }),
      rec('child-start', { childId: 'parser', pid: 202, creationFileTime: birth(2), executable: 'C:\\test\\java.exe' }),
      rec('helper-start', receipt, 202),
      rec('helper-exit', { ...receipt, code: 0, exitClass: 'REQUESTED', reason: 'receipt reason', forced: false,
        exitedAt: at(5), observedAt: at(5) }, 202),
      rec('job-process-start', { witnessId: 'member', owner: engine, incarnation, pid: 303, creationFileTime: helperBirth,
        executable: 'C:\\test\\tesseract.exe', contained: true, parentPid: creator.pid,
        parentCreationFileTime: creator.creationFileTime, awaited: false }),
      rec('job-process-exit', { witnessId: 'member', owner: engine, incarnation, pid: 303, creationFileTime: helperBirth,
        code: 0, exitClass: 'UNEXPECTED', reason: 'no recorded intent', forced: false, exitedAt: at(5), observedAt: at(5) }),
    ] },
  };
}
const member = data => census.ledgerView(data, incarnation.session).witnesses.find(w => w.pid === 303)?.exit;

test('preservation: a receipt with the member\'s exact birth and native creator names it', () => {
  assert.equal(member(receiptLedger())?.exitClass, 'REQUESTED');
});

test('a receipt\'s declared creator does not override a conflicting native creator', () => {
  const exit = member(receiptLedger({ creator: engine }));
  assert.equal(exit?.exitClass, 'UNEXPECTED', JSON.stringify(exit));
});

test('a receipt birth 100 ns from the native birth does not name the member', () => {
  const exit = member(receiptLedger({ receiptBirth: String(BigInt(birth(4)) + 1n) }));
  assert.equal(exit?.exitClass, 'UNEXPECTED', JSON.stringify(exit));
});

test('preservation: a conflicting native creator with no declared creator is refused', () => {
  assert.equal(member(receiptLedger({ creator: engine, withCreatorFields: false }))?.exitClass, 'UNEXPECTED');
});

// The Engine exits at 05.1234567; a witnessed helper counts only if born by then and exited
// within the 2 s grace after it, compared at native 100 ns precision.
const start = '2026-10-14T10:00:00.0000000Z';
const engineEnd = '2026-10-14T10:00:05.1234567Z';
const owner = { pid: 100, creationFileTime: census.fileTimeFromInstant(start), instanceId: 'engine' };
function cutoffLedger(born, exited) {
  const ft = t => census.fileTimeFromInstant(t);
  const r = (type, line) => ({ type, line, writer: { role: 'engine', pid: 100 } });
  const helper = { helperId: 'h', owner, incarnation, pid: 303, creationFileTime: ft(born), creatorPid: 100,
    creatorCreationFileTime: ft(start) };
  return {
    engine: { present: true, records: [
      r('engine-start', { incarnation, pid: 100, creationFileTime: ft(start), executable: 'C:\\test\\java.exe' }),
      r('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED', reason: 'QUIT', exitedAt: engineEnd, observedAt: engineEnd }),
    ] },
    children: { present: true, records: [
      r('helper-start', helper),
      r('helper-exit', { ...helper, code: 0, exitClass: 'REQUESTED', reason: 'awaited completion', exitedAt: exited,
        observedAt: exited, forced: false }),
      r('job-process-start', { witnessId: 'j', owner, incarnation, pid: 303, creationFileTime: ft(born),
        executable: 'C:\\test\\tesseract.exe', contained: true, parentPid: 100, parentCreationFileTime: ft(start), awaited: false }),
      r('job-process-exit', { witnessId: 'j', owner, incarnation, pid: 303, creationFileTime: ft(born), code: 0,
        exitClass: 'UNEXPECTED', reason: 'no recorded intent', exitedAt: exited, observedAt: exited, forced: false }),
    ] },
  };
}
function accounted(born, exited) {
  const ft = census.fileTimeFromInstant(born);
  const result = census.exitTargets({ runId: incarnation.session, startedAt: start, pids: { backendRootPid: 100 } }, null,
    [{ pid: 303, creationFileTimeUtc: ft, executable: 'C:\\test\\tesseract.exe', parentPid: 100, lastSeenAtMs: Date.parse(born) }],
    cutoffLedger(born, exited));
  return result.accountedProcesses.some(p => p.pid === 303 && p.ledgerClass === 'REQUESTED');
}

test('preservation: a member born 100 ns before the Engine\'s end is accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:05.1234566Z', '2026-10-14T10:00:05.1234567Z'), true);
});

test('a member born 100 ns after the Engine\'s end is not accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:05.1234568Z', '2026-10-14T10:00:05.1234569Z'), false);
});

test('preservation: a member exiting 100 ns before the Engine\'s grace cutoff is accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:04.0000000Z', '2026-10-14T10:00:07.1234566Z'), true);
});

test('a member exiting 100 ns after the Engine\'s grace cutoff is not accounted', () => {
  assert.equal(accounted('2026-10-14T10:00:04.0000000Z', '2026-10-14T10:00:07.1234568Z'), false);
});
