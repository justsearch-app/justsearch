import { test } from 'node:test';
import assert from 'node:assert/strict';
import census from './lib/stop-exit-census.cjs';

const b = t => census.fileTimeFromInstant(`2026-10-07T10:00:${String(t).padStart(2, '0')}.000Z`);
const at = t => Date.parse(`2026-10-07T10:00:${String(t).padStart(2, '0')}.000Z`);
// The Engine (pid 100) writes the children records as itself: its PID and its start, to the millisecond.
const rec = (type, line) => ({ type, line, writer: type.startsWith('engine') ? { role: 'dev-runner' }
  : { role: 'engine', pid: 100, startedAt: new Date(at(0)).toISOString() } });
const run = { runId: 'own-census', pids: { backendRootPid: 100 } };
const manifest = { pid: 100, containment: { status: 'CONTAINED' }, incarnation: { session: run.runId } };
const owner = { pid: 100, creationFileTime: b(0) };
const engineStart = rec('engine-start', { incarnation: { session: run.runId, n: 1 },
  pid: 100, creationFileTime: b(0), executable: 'C:\\verify\\java.exe' });
const engineExit = rec('engine-exit', { incarnation: { session: run.runId, n: 1 },
  exitClass: 'REQUESTED', reason: 'QUIT', code: 0, exitedAt: '2026-10-07T10:00:20.000Z' });
const row = (pid, parentPid, t, name = 'tesseract.exe', seen = t) => ({ pid, parentPid,
  creationFileTimeUtc: b(t), name, executable: `C:\\verify\\${name}`, firstSeenAtMs: at(t), lastSeenAtMs: at(seen) });
const ledger = { engine: { present: true, records: [engineStart] }, children: { present: true, records: [
  rec('child-spawn', { childId: 'x', kind: 'EXTRACTION', owner }),
  rec('child-start', { childId: 'x', pid: 101, creationFileTime: b(1), executable: 'C:\\verify\\java.exe', contained: true }),
  rec('child-exit', { childId: 'x', exitClass: 'REQUESTED', reason: 'RECYCLE', code: 0, exitedAt: '2026-10-07T10:00:20.000Z' }),
] } };

test('S15 N4: allowlisted helper and host surviving Engine job close remain gaps', () => {
  const rows = [row(102, 100, 2, 'powershell.exe', 59), row(103, 102, 3, 'conhost.exe', 59)];
  const closed = { ...ledger, engine: { present: true, records: [engineStart, engineExit] },
    engineContainment: [{ ...owner, status: 'CONTAINED', incarnation: { session: run.runId, n: 1 } }] };
  for (const records of [closed, ledger]) {
    const r = census.exitTargets(run, manifest, rows, records);
    assert.deepEqual(r.gaps.map(g => [g.pid, g.reason]), [[102, 'unaccounted_process'], [103, 'orphan_console_host']],
      'neither a closed nor an open Engine job proves this allowlisted descendant belongs to it');
    assert.deepEqual(r.accountedProcesses, []);
  }
});

// O3 verbatim inputs and assertion from verify-census/own-cases.cjs (also used by verify-census2).
test('S15 O3 verbatim: helper host observed past recorded ancestor exit and grace stays a gap', () => {
  const r = census.exitTargets(run, manifest, [row(102, 101, 2), row(103, 102, 3, 'conhost.exe', 25)], ledger);
  assert.ok(r.gaps.some(g => g.pid === 103) && !r.accountedProcesses.some(p => p.pid === 103));
});

test('S15 control: a helper with its own owner record and its host end with the job and stay accounted', () => {
  const records = { ...ledger, engine: { present: true, records: [engineStart, engineExit] } };
  const helper = row(101, 100, 1, 'java.exe', 20), host = row(103, 101, 3, 'conhost.exe', 22);
  const r = census.exitTargets(run, manifest, [helper, host], records);
  assert.deepEqual(r.gaps, []);
  assert.deepEqual(r.accountedProcesses.map(p => [p.pid, p.role]), [[103, 'console-host']]);
  const late = census.exitTargets(run, manifest, [helper, { ...host, lastSeenAtMs: at(25) }], records);
  assert.deepEqual(late.gaps.map(g => [g.pid, g.reason]), [[103, 'orphan_console_host']]);
  // A missing child exit cannot extend the owner's closed job lifetime.
  const missingExit = { ...records, children: { present: true, records: ledger.children.records.slice(0, 2) } };
  const contradicted = census.exitTargets(run, manifest,
    [{ ...helper, lastSeenAtMs: at(25) }, { ...host, lastSeenAtMs: at(25) }], missingExit);
  assert.ok(contradicted.gaps.some(g => g.pid === 101 && /contradicts recorded containment/.test(g.detail)));
  assert.ok(contradicted.gaps.some(g => g.pid === 103 && g.reason === 'orphan_console_host'));
  assert.ok(!contradicted.targets.some(p => p.pid === 103));
});
