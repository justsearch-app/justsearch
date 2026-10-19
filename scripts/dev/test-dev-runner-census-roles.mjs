import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';

// Task b6d536 b101: llama-server and the extraction child keep their own role in every census
// row, including the rows built from the Engine job's witness, never the generic helper role.
const instant = n => new Date(Date.UTC(2026, 9, 14, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(instant(n));
const incarnation = { session: 'roles', n: 1 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
// The Engine writes as itself, naming its start instant: a child's key-only exit proves its start
// only when its writer is the child's owner (b102).
const rec = (type, line) => ({ type, line, writer: { role: 'engine', pid: 100, startedAt: instant(0) } });
const run = { runId: 'roles', pids: { backendRootPid: 100 } };

// The witness line is written the way b599d4d7e wrote it (UNEXPECTED, no reason), so these rows
// check roles alone; the census derives the class from the owner's child-exit either way.
function child(childId, kind, pid, image, at) {
  return [
    rec('child-spawn', { childId, owner, kind }),
    rec('child-start', { childId, pid, creationFileTime: birth(at), executable: `C:\\test\\${image}` }),
    rec('job-process-start', { witnessId: `j-${childId}`, owner, incarnation, pid, creationFileTime: birth(at),
      executable: `C:\\test\\${image}`, contained: true, awaited: false, parentPid: 100,
      parentCreationFileTime: birth(0) }),
    rec('job-process-exit', { witnessId: `j-${childId}`, owner, incarnation, pid, creationFileTime: birth(at),
      code: 1, exitClass: 'UNEXPECTED', reason: null,
      exitedAt: instant(at + 4), observedAt: instant(at + 4), forced: false }),
    rec('child-exit', { childId, code: 1, exitClass: 'REQUESTED', reason: 'QUIT', forced: false,
      exitedAt: instant(at + 4), observedAt: instant(at + 4) }),
  ];
}

const ledger = () => ({
  engine: { present: true, records: [
    rec('engine-start', { incarnation, pid: 100, creationFileTime: birth(0), executable: 'C:\\test\\java.exe' }),
    rec('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED', reason: 'QUIT',
      exitedAt: instant(30), observedAt: instant(30) })] },
  children: { present: true, records: [
    ...child('c-llama', 'LLAMA_SERVER', 201, 'llama-server.exe', 2),
    ...child('c-extract', 'EXTRACTION', 202, 'java.exe', 3)] },
});

test('witness report identities name llama-server and the extraction child by their own roles', () => {
  const roles = Object.fromEntries(census.ledgerView(ledger(), run.runId).reportIdentities
    .map(r => [r.pid, r.role]));
  assert.equal(roles[201], 'llama-server');
  assert.equal(roles[202], 'extraction-child');
});

test('a sampled census row of a witnessed child carries its own role', () => {
  const input = census.exitTargets(run, null, [
    { pid: 201, creationFileTimeUtc: birth(2), executable: 'C:\\test\\llama-server.exe', parentPid: 100,
      lastSeenAtMs: Date.parse(instant(3)) },
    { pid: 202, creationFileTimeUtc: birth(3), executable: 'C:\\test\\java.exe', parentPid: 100,
      lastSeenAtMs: Date.parse(instant(4)) }], ledger());
  const rows = [...input.targets, ...(input.accountedProcesses ?? [])];
  const roleOf = pid => rows.filter(r => r.pid === pid).map(r => r.role);
  assert.ok(roleOf(201).length > 0, JSON.stringify(input));
  assert.deepEqual([...new Set(roleOf(201))], ['llama-server']);
  assert.deepEqual([...new Set(roleOf(202))], ['extraction-child']);
});

test('a witness line carrying its owner intent stays valid; the census still derives the class', () => {
  const data = ledger();
  const line = data.children.records.find(r => r.type === 'job-process-exit' && r.line.pid === 201).line;
  Object.assign(line, { exitClass: 'REQUESTED', reason: 'QUIT', intentBasis: 'owner exit record' });
  const witness = census.ledgerView(data, run.runId).witnesses.find(w => w.pid === 201);
  assert.ok(witness, 'the witness identity is kept');
  assert.equal(witness.exit.exitClass, 'REQUESTED');
  // A witness cannot manufacture intent: with no owner record or window, the class is UNEXPECTED.
  const unowned = ledger();
  unowned.children.records = unowned.children.records.filter(r => !(r.line.childId === 'c-llama'));
  Object.assign(unowned.children.records.find(r => r.type === 'job-process-exit' && r.line.pid === 201).line,
    { exitClass: 'REQUESTED', reason: 'Tesseract OCR during extraction', intentBasis: 'owner window' });
  assert.equal(census.ledgerView(unowned, run.runId).witnesses.find(w => w.pid === 201).exit.exitClass, 'UNEXPECTED');
});
