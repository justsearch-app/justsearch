import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
const require = createRequire(import.meta.url);
const census = require(process.env.CENSUS_TEST_TREE
  ? path.join(process.env.CENSUS_TEST_TREE, 'scripts/dev/lib/stop-exit-census.cjs')
  : './lib/stop-exit-census.cjs');
const at = n => new Date(Date.UTC(2026, 9, 8, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'delayed-crash', n: 1 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'crashed-engine' };
const run = { runId: incarnation.session, pids: { backendRootPid: 100 } };
// Each writer as the ledger records it: its role, PID and start instant (its birth to the millisecond).
const rec = (type, line, role = 'engine', pid = 100) => ({ type, line, writer: { role, pid, startedAt: at(0) } });
function fixture(delay = 10) {
  const data = { engine: { present: true, records: [rec('engine-start', {
    incarnation, pid: 100, creationFileTime: birth(0), executable: 'C:\\test\\java.exe' }),
  rec('engine-exit', { incarnation, code: 1, exitClass: 'UNEXPECTED',
    reason: 'Engine crash', exitedAt: at(5), observedAt: at(5) })] },
  children: { present: true, records: [] } };
  for (const [pid, image, parentPid, parentBirth] of [
    [102, 'ping.exe', 100, birth(0)], [103, 'conhost.exe', 102, birth(2)],
  ]) {
    const start = { owner, incarnation, witnessId: `w-${pid}`, pid, creationFileTime: birth(2),
      executable: `C:\\test\\${image}`, contained: true, parentPid, parentCreationFileTime: parentBirth };
    data.children.records.push(rec('job-process-start', start), rec('job-process-exit', {
      ...start, reconciled: true, cause: 'ENGINE_CRASH', osCheck: 'GONE', code: null,
      exitClass: 'OWNER_DIED', reason: 'Engine crash; contained helper gone; final exit time unknown',
      exitedAt: null, observedAt: at(delay), forced: false }, 'dev-runner', 200));
  }
  return data;
}
const observed = seen => [102, 103].map(pid => ({ pid, creationFileTimeUtc: birth(2),
  parentPid: pid === 102 ? 100 : 102, name: pid === 102 ? 'ping.exe' : 'conhost.exe',
  lastSeenAtMs: Date.parse(at(seen)) }));
function finish(data, rows = []) {
  return census.exitResult(census.exitTargets(run, null, rows, data), { processExits: [] }, 'test', data);
}
function assertCasualties(result) {
  for (const pid of [102, 103]) {
    const row = result.processExits.find(p => p.pid === pid);
    assert.equal(row?.ledgerClass, 'OWNER_DIED', 'observation delay cannot prove survival after job closure');
    assert.match(row.ledgerReason, /Engine crash/);
    assert.equal(row.atMs, null);
    assert.equal(row.exitTimeSource, 'unknown');
    assert.equal(row.expectedTermination, false);
    assert.ok(!result.exitAccountingGaps.some(g => g.pid === pid), JSON.stringify(result.exitAccountingGaps));
  }
}
test('S15 delayed crash receipts survive final census with unsampled helpers and console hosts', () => {
  assertCasualties(finish(fixture()));
});
test('S15 delayed crash receipts survive final census with sampled helpers and console hosts', () => {
  assertCasualties(finish(fixture(), observed(4)));
});
test('S15 fast crash receipt observation remains accounted with unknown exit time', () => {
  assertCasualties(finish(fixture(6)));
});
test('S15 a sampled Engine console host keeps its crash receipt when Engine exit time is unknown', () => {
  const data = fixture(); data.engine.records[1].line.exitedAt = null;
  data.children.records[2].line.parentPid = owner.pid;
  data.children.records[2].line.parentCreationFileTime = owner.creationFileTime;
  const rows = observed(4); rows[1].parentPid = owner.pid;
  assertCasualties(finish(data, rows));
});
test('S18 a crash receipt cannot name a different helper birth or a foreign writer', () => {
  for (const change of [r => { r.line.creationFileTime = (BigInt(birth(2)) + 1n).toString(); },
    r => { r.writer = { role: 'shell', pid: 999 }; }, r => { r.writer.pid = owner.pid; r.writer.role = 'engine'; }]) {
    const data = fixture(6); change(data.children.records[1]);
    const result = finish(data);
    assert.equal(result.processExits.find(p => p.pid === 102).ledgerClass, 'UNKNOWN');
    assert.ok(result.exitAccountingGaps.some(g => g.pid === 102));
  }
});
test('S18 a helper witnessed alive after job closure remains a gap despite a crash receipt', () => {
  for (const known of [true, false]) {
    const data = fixture(6);
    if (!known) data.engine.records[1].line.exitedAt = null;
    const result = finish(data, observed(8));
    assert.ok(result.exitAccountingGaps.some(g => g.pid === 102));
    assert.equal(result.processExits.find(p => p.pid === 102).ledgerClass, 'UNKNOWN');
  }
});
test('S15 actual helper crash during intended stop remains unexpected', () => {
  const data = fixture(6), r = data.children.records[1];
  r.writer = { role: 'engine', pid: 100 }; r.line.reconciled = false;
  r.line.code = 0xc0000005; r.line.exitClass = 'REQUESTED'; r.line.reason = 'QUIT';
  r.line.exitedAt = at(4);
  const row = finish(data).processExits.find(p => p.pid === 102);
  assert.equal(row.ledgerClass, 'UNEXPECTED'); assert.equal(row.expectedTermination, false);
});
test('S17 identity lookup failure stays unknown in final census', () => {
  const data = fixture(6), r = data.children.records[1];
  r.line.osCheck = 'IDENTITY_UNKNOWN'; r.line.exitClass = 'UNKNOWN';
  r.line.reason = 'Engine crash; helper identity lookup failed; exit and time unknown';
  const result = finish(data);
  assert.equal(result.processExits.find(p => p.pid === 102).ledgerClass, 'UNKNOWN');
  assert.ok(result.exitAccountingGaps.some(g => g.pid === 102));
});
test('S15 a delayed ordinary helper receipt never substitutes observation for exit time', () => {
  for (const timestamp of ['observation', 'write', 'unavailable']) {
    const data = fixture(), r = data.children.records[1];
    r.writer = { role: 'engine', pid: 100 }; r.line.reconciled = false;
    r.line.code = 17; r.line.exitClass = 'UNEXPECTED'; r.line.reason = 'no owner intent';
    if (timestamp !== 'observation') delete r.line.observedAt;
    if (timestamp === 'write') r.line.at = at(10);
    const result = finish(data, observed(4));
    const row = result.processExits.find(p => p.pid === 102);
    assert.equal(row.ledgerClass, 'UNEXPECTED', timestamp); assert.equal(row.atMs, null);
    assert.ok(!result.exitAccountingGaps.some(g => g.pid === 102), timestamp);
  }
});
test('S15 an intended helper and its console host retain intent with unknown exit times and delayed observations', () => {
  const data = fixture(); data.engine.records[1].line.exitClass = 'REQUESTED';
  data.engine.records[1].line.code = 0; data.engine.records[1].line.reason = 'QUIT';
  for (const r of data.children.records.filter(r => r.type === 'job-process-exit')) {
    r.writer = { role: 'engine', pid: 100 }; r.line.reconciled = false;
    r.line.code = 0; r.line.exitClass = 'UNEXPECTED';
  }
  for (const type of ['helper-start', 'helper-exit']) data.children.records.push(rec(type, {
    helperId: 'probe', owner, incarnation, pid: 102, creationFileTime: birth(2), code: 0,
    exitClass: 'REQUESTED', reason: 'probe completed', exitedAt: null, observedAt: at(6), forced: false }));
  const result = finish(data);
  for (const pid of [102, 103]) {
    const row = result.processExits.find(p => p.pid === pid);
    assert.equal(row.ledgerClass, 'REQUESTED'); assert.equal(row.ledgerReason, 'probe completed');
    assert.equal(row.atMs, null); assert.equal(row.exitTimeSource, 'unknown');
    assert.ok(!result.exitAccountingGaps.some(g => g.pid === pid));
  }
});
