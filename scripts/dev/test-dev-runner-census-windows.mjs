import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';

// Task b6d536 b101: a helper window recorded by the extraction child attributes the Tesseract its
// owner launched. Its start is forced; its end is not, so a window without a recorded end (still
// open, or lost in a host crash) covers until its owner's recorded exit or crash, or else its
// deadline. A window never covers a process its owner did not create, nor an exit after the owner died.
const instant = n => new Date(Date.UTC(2026, 9, 14, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(instant(n));
const incarnation = { session: 'windows', n: 1 };
const engine = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const parser = { pid: 202, creationFileTime: birth(2), instanceId: 'parser-windows' };
const rec = (type, line, writerPid = 100) => ({ type, line, writer: { role: 'engine', pid: writerPid } });
const REASON = 'Tesseract OCR during extraction';

/** The extraction child opens a window at 3 s; Tesseract (pid 303) starts at 4 s and exits at `exitAt`. */
function ledger({ ended = false, exitAt = 5, creator = parser, parserExitAt = null, engineExitAt = null } = {}) {
  const window = { intentId: 'i-windows', incarnation, owner: parser, image: 'tesseract.exe', reason: REASON,
    requestedAt: instant(3), deadlineAt: instant(60), ownerContainment: 'KILL_ON_CLOSE' };
  const children = [
    rec('child-spawn', { childId: 'parser', owner: engine, kind: 'EXTRACTION' }),
    rec('child-start', { childId: 'parser', pid: 202, creationFileTime: birth(2), executable: 'C:\\test\\java.exe' }),
    rec('helper-window-start', { ...window, closedAt: null }, 202),
    rec('job-process-start', { witnessId: 'j-tesseract', owner: engine, incarnation, pid: 303,
      creationFileTime: birth(4), executable: 'C:\\test\\tesseract.exe', contained: true,
      parentPid: creator.pid, parentCreationFileTime: creator.creationFileTime, awaited: false }),
    rec('job-process-exit', { witnessId: 'j-tesseract', owner: engine, incarnation, pid: 303,
      creationFileTime: birth(4), code: 0, exitClass: 'UNEXPECTED', reason: 'no recorded intent',
      exitedAt: instant(exitAt), observedAt: instant(exitAt), forced: false }),
  ];
  if (ended) children.push(rec('helper-window-end', { ...window, closedAt: instant(35) }, 202));
  if (parserExitAt != null) {
    children.push(rec('child-exit', { childId: 'parser', owner: engine, pid: 202, creationFileTime: birth(2), code: 1,
      exitClass: 'UNEXPECTED', reason: 'parser crash', exitedAt: instant(parserExitAt),
      observedAt: instant(parserExitAt), forced: false }));
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

test('a window without a recorded end, its owner still running, covers until its deadline', () => {
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
