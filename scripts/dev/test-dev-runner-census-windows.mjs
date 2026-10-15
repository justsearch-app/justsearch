import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';

// Task b6d536 b101: a helper window recorded by the extraction child attributes the Tesseract its
// owner launched. Its start is forced; its end is not, so a window without a recorded end (still
// open, or lost in a host crash) covers until its owner's recorded exit or crash/owner-died record,
// and while its owner lives; its deadline bounds a lease, not the window. Cutoffs compare native
// 100 ns times. A window never covers a process its owner did not create, nor an exit after the
// owner died.
const instant = n => new Date(Date.UTC(2026, 9, 14, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(instant(n));
const incarnation = { session: 'windows', n: 1 };
const engine = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const parser = { pid: 202, creationFileTime: birth(2), instanceId: 'parser-windows' };
const rec = (type, line, writerPid = 100) => ({ type, line, writer: { role: 'engine', pid: writerPid } });
const REASON = 'Tesseract OCR during extraction';

/**
 * The extraction child opens a window at 3 s (deadline 60 s); Tesseract (pid 303) starts at
 * `launchAt` and exits at `exitAt` (seconds, or an instant).
 */
function ledger({ ended = false, launchAt = 4, exitAt = 5, code = 0, creator = parser, parserExitAt = null,
  ownerDiedAt = null, engineExitAt = null } = {}) {
  const at = t => (typeof t === 'string' ? t : instant(t));
  const window = { intentId: 'i-windows', incarnation, owner: parser, image: 'tesseract.exe', reason: REASON,
    requestedAt: instant(3), deadlineAt: instant(60), ownerContainment: 'KILL_ON_CLOSE' };
  const children = [
    rec('child-spawn', { childId: 'parser', owner: engine, kind: 'EXTRACTION' }),
    rec('child-start', { childId: 'parser', pid: 202, creationFileTime: birth(2), executable: 'C:\\test\\java.exe' }),
    rec('helper-window-start', { ...window, closedAt: null }, 202),
    rec('job-process-start', { witnessId: 'j-tesseract', owner: engine, incarnation, pid: 303,
      creationFileTime: birth(launchAt), executable: 'C:\\test\\tesseract.exe', contained: true,
      parentPid: creator.pid, parentCreationFileTime: creator.creationFileTime, awaited: false }),
    rec('job-process-exit', { witnessId: 'j-tesseract', owner: engine, incarnation, pid: 303,
      creationFileTime: birth(launchAt), code, exitClass: 'UNEXPECTED', reason: 'no recorded intent',
      exitedAt: at(exitAt), observedAt: at(exitAt), forced: false }),
  ];
  if (ended) children.push(rec('helper-window-end', { ...window, closedAt: instant(35) }, 202));
  if (parserExitAt != null) {
    children.push(rec('child-exit', { childId: 'parser', owner: engine, pid: 202, creationFileTime: birth(2), code: 1,
      exitClass: 'UNEXPECTED', reason: 'parser crash', exitedAt: at(parserExitAt),
      observedAt: at(parserExitAt), forced: false }));
  }
  if (ownerDiedAt != null) {
    children.push(rec('owner-died', { childId: 'parser', previousOwner: engine, pid: 202, creationFileTime: birth(2),
      code: null, exitClass: 'OWNER_DIED', reason: 'owner gone', observedAt: instant(ownerDiedAt), forced: false }));
  }
  const engineRecords = [rec('engine-start', { incarnation, pid: 100, creationFileTime: birth(0),
    executable: 'C:\\test\\java.exe' })];
  if (engineExitAt != null) {
    engineRecords.push(rec('engine-exit', { incarnation, code: 1, exitClass: 'UNEXPECTED', reason: 'host crash',
      exitedAt: instant(engineExitAt), observedAt: instant(engineExitAt) }));
  }
  return { engine: { present: true, records: engineRecords }, children: { present: true, records: children } };
}

const tesseract = data => census.ledgerView(data, incarnation.session).witnesses.find(w => w.pid === 303)?.exit;

test('preservation: a closed child window attributes the Tesseract its owner launched in it', () => {
  const exit = tesseract(ledger({ ended: true }));
  assert.equal(exit?.exitClass, 'REQUESTED', JSON.stringify(exit));
  assert.equal(exit.reason, REASON);
});

test('a window without a recorded end, its owner still running, covers before its deadline', () => {
  const exit = tesseract(ledger({ ended: false }));
  assert.equal(exit?.exitClass, 'REQUESTED', JSON.stringify(exit));
  assert.equal(exit.reason, REASON);
});

test('a window end lost in a host crash covers until the owner crash and the Engine crash', () => {
  const exit = tesseract(ledger({ ended: false, exitAt: 5, parserExitAt: 9, engineExitAt: 9 }));
  assert.equal(exit?.exitClass, 'REQUESTED', JSON.stringify(exit));
  assert.equal(exit.reason, REASON);
});

test('a window without a recorded end does not cover an exit after its owner died', () => {
  const exit = tesseract(ledger({ ended: false, exitAt: 12, parserExitAt: 9 }));
  assert.equal(exit?.exitClass, 'UNEXPECTED', JSON.stringify(exit));
});

test('a window covers only processes its owner created', () => {
  const exit = tesseract(ledger({ ended: false, creator: engine }));
  assert.equal(exit?.exitClass, 'UNEXPECTED', JSON.stringify(exit));
});

test('a window without a recorded end covers past its deadline while its owner has no proven exit', () => {
  const exit = tesseract(ledger({ launchAt: 61, exitAt: 62 }));
  assert.equal(exit?.exitClass, 'REQUESTED', JSON.stringify(exit));
  assert.equal(exit.reason, REASON);
});

test('a window without a recorded end covers past its deadline until its owner\'s later proven exit', () => {
  const exit = tesseract(ledger({ launchAt: 61, exitAt: 62, parserExitAt: 70 }));
  assert.equal(exit?.exitClass, 'REQUESTED', JSON.stringify(exit));
  assert.equal(exit.reason, REASON);
});

test('a window without a recorded end ends at its owner-died record', () => {
  assert.equal(tesseract(ledger({ launchAt: 12, exitAt: 13, ownerDiedAt: 9 }))?.exitClass, 'UNEXPECTED');
  assert.equal(tesseract(ledger({ ownerDiedAt: 9 }))?.exitClass, 'REQUESTED');
});

test('an owner cutoff compares native 100 ns times: an exit earlier in the owner exit\'s millisecond is covered', () => {
  for (const code of [0, 1]) {
    const exit = tesseract(ledger({ code, exitAt: '2026-10-14T10:00:05.1234500Z', parserExitAt: '2026-10-14T10:00:05.1234567Z' }));
    assert.equal(exit?.exitClass, 'REQUESTED', `code ${code}: ${JSON.stringify(exit)}`);
    assert.equal(exit.reason, REASON);
  }
  const later = tesseract(ledger({ exitAt: '2026-10-14T10:00:05.1234568Z', parserExitAt: '2026-10-14T10:00:05.1234567Z' }));
  assert.notEqual(later?.reason, REASON, JSON.stringify(later));
});

test('an uncleared FORCE_FAILED ledger gap is a named durability gap until a later record clears it', () => {
  const writer = { role: 'engine', pid: 100 };
  const gap = (extra) => ({ type: 'ledger-gap', writer,
    line: { type: 'ledger-gap', at: instant(20), reason: 'FORCE_FAILED', count: 1, since: instant(20), ...extra } });
  const run = { runId: incarnation.session, startedAt: instant(1) };
  const open = { engine: { present: true, records: [] }, children: { present: true, records: [gap({})] } };
  const gaps = census.openDurabilityGaps(open, run);
  assert.deepEqual(gaps.map(g => [g.reason, g.ledgerReason, g.family, g.since]),
    [['durability_gap', 'FORCE_FAILED', 'children', instant(20)]]);
  const cleared = { engine: { present: true, records: [] },
    children: { present: true, records: [gap({}), gap({ count: 2, at: instant(22), clearedAt: instant(22) })] } };
  assert.deepEqual(census.openDurabilityGaps(cleared, run), []);
  const { gaps: targetGaps } = census.exitTargets(run, null, [], open);
  assert.ok(targetGaps.some(g => g.reason === 'durability_gap' && g.ledgerReason === 'FORCE_FAILED'),
    JSON.stringify(targetGaps));
});
