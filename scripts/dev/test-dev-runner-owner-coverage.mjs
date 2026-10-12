import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import fs from 'node:fs';
import os from 'node:os';
const require = createRequire(import.meta.url);
const census = process.env.CENSUS_TEST_TREE
  ? require(path.join(process.env.CENSUS_TEST_TREE, 'scripts/dev/lib/stop-exit-census.cjs'))
  : require('./lib/stop-exit-census.cjs');
const at = n => new Date(Date.UTC(2026, 9, 8, 10, 0, n)).toISOString();
const birth = n => census.fileTimeFromInstant(at(n));
const incarnation = { session: 'coverage', n: 1 };
const owner = { pid: 100, creationFileTime: birth(0), instanceId: 'engine' };
const rec = (type, line, pid = 100) => ({ type, line, writer: { role: 'engine', pid } });
function fixture() {
  return { engine: { present: true, records: [rec('engine-start', { incarnation, pid: 100,
    creationFileTime: birth(0), executable: 'C:\\test\\java.exe' }), rec('engine-exit', {
    incarnation, code: 0, exitClass: 'REQUESTED', reason: 'QUIT', observedAt: at(10), exitedAt: at(10) })] },
  children: { present: true, records: [rec('job-process-start', { witnessId: 'helper', owner, incarnation,
    pid: 102, creationFileTime: birth(2), executable: 'C:\\test\\tesseract.exe', contained: true,
    parentPid: 104, parentCreationFileTime: birth(1) }), rec('job-process-exit', { witnessId: 'helper',
    owner, incarnation, pid: 102, creationFileTime: birth(2), code: 1, exitClass: 'UNEXPECTED',
    forced: false, exitedAt: at(3), observedAt: at(3) })] } };
}
test('O9: sibling parser windows and receipts require the exact witnessed creator birth', () => {
  const data = fixture();
  for (const pid of [101, 104]) data.children.records.push(rec('child-spawn', {
    childId: `p${pid}`, owner, kind: 'EXTRACTION' }), rec('child-start', {
    childId: `p${pid}`, pid, creationFileTime: birth(1), executable: 'C:\\test\\java.exe' }));
  const parser = { pid: 101, creationFileTime: birth(1), instanceId: 'parser-a' };
  for (const type of ['helper-window-start', 'helper-window-end']) data.children.records.push(rec(type, {
    intentId: 'call', owner: parser, incarnation, image: 'tesseract.exe', reason: 'parser A call',
    requestedAt: at(1), deadlineAt: at(8), closedAt: type.endsWith('end') ? at(4) : null }, 101));
  const cls = () => census.ledgerView(data, incarnation.session).witnesses[0].exit;
  assert.equal(cls().exitClass, 'UNEXPECTED', 'parser A cannot credit parser B');
  data.children.records[0].line.parentPid = 101;
  assert.equal(cls().exitClass, 'REQUESTED', 'same parser identity is sufficient');
  data.children.records[0].line.parentCreationFileTime = birth(0);
  assert.equal(cls().exitClass, 'UNEXPECTED', 'reused parent PID is insufficient');
  delete data.children.records[0].line.parentCreationFileTime;
  assert.equal(cls().exitClass, 'UNEXPECTED', 'missing identity is insufficient');
});
test('every witnessed and sampled identity has a final row; Engine console inherits reason and class', () => {
  const data = fixture(), run = { runId: incarnation.session, pids: { backendRootPid: 100 } };
  data.engine.records.pop();
  const observed = [{ pid: 103, parentPid: 100, name: 'conhost.exe', creationFileTimeUtc: birth(1),
    lastSeenAtMs: Date.parse(at(9)) }, { pid: 999, parentPid: 0, creationFileTimeUtc: birth(1) }];
  const input = census.exitTargets(run, null, observed, data);
  data.engine.records.push(rec('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED',
    reason: 'QUIT', exitedAt: at(10), observedAt: at(10) }));
  const out = census.exitResult(input, { processExits: input.targets.map(t => ({ pid: t.pid,
    creationFileTimeUtc: t.creationFileTimeUtc, exitCode: 0, atMs: Date.parse(at(11)) })) }, 'test', data);
  const host = out.processExits.find(p => p.pid === 103);
  assert.equal(host.ledgerClass, 'REQUESTED'); assert.equal(host.ledgerReason, 'QUIT');
  assert.equal(out.processExits.find(p => p.pid === 102).ledgerClass, 'UNEXPECTED');
  assert.equal(out.processExits.find(p => p.pid === 999).ledgerClass, 'UNKNOWN');
  assert.equal(out.processExits.find(p => p.pid === 999).accounted, false);
});
test('a surviving owner receipt names the Engine crash, keeps exit time unknown and is not intended', () => {
  const data = fixture(); data.children.records.pop();
  data.engine.records[1].line.exitClass = 'UNEXPECTED';
  data.engine.records[1].line.code = 1;
  data.children.records.push({ type: 'job-process-exit', writer: { role: 'dev-runner', pid: 200 }, line: {
    ...data.children.records[0].line, reconciled: true, cause: 'ENGINE_CRASH', osCheck: 'GONE',
    code: null, exitClass: 'OWNER_DIED', reason: 'Engine crash; final exit time unknown',
    exitedAt: null, observedAt: at(11), forced: false } });
  const cls = census.ledgerView(data, incarnation.session).witnesses[0].exit;
  assert.equal(cls?.exitClass, 'OWNER_DIED'); assert.match(cls.reason, /Engine crash/);
  assert.equal(cls.exitedAtMs, null);
  data.children.records.at(-1).line.osCheck = 'IDENTITY_UNKNOWN';
  data.children.records.at(-1).line.exitClass = 'UNKNOWN';
  assert.equal(census.ledgerView(data, incarnation.session).witnesses[0].exit.exitClass, 'UNKNOWN');
});

test('an unsampled helper console host inherits its client receipt and every sampled nvidia identity stays visible', () => {
  const data = fixture(), run = { runId: incarnation.session, pids: { backendRootPid: 100 } };
  data.engine.records.pop();
  data.children.records[0].line.parentPid = 100;
  data.children.records[0].line.parentCreationFileTime = birth(0);
  for (const type of ['helper-start', 'helper-exit']) data.children.records.push(rec(type, {
    helperId: 'owner-helper', owner, incarnation, pid: 102, creationFileTime: birth(2),
    code: 1, exitClass: 'REQUESTED', reason: 'probe completed', exitedAt: at(3), observedAt: at(3), forced: false }));
  data.children.records.push(rec('job-process-start', { witnessId: 'helper-host', owner, incarnation,
    pid: 103, creationFileTime: birth(2), executable: 'C:\\test\\conhost.exe', contained: true,
    parentPid: 102, parentCreationFileTime: birth(2) }), rec('job-process-exit', {
    witnessId: 'helper-host', owner, incarnation, pid: 103, creationFileTime: birth(2),
    code: 0, exitClass: 'UNEXPECTED', exitedAt: at(4), observedAt: at(4), forced: false }));
  const input = census.exitTargets(run, null, [{ pid: 105, creationFileTimeUtc: birth(1),
    name: 'nvidia-smi.exe', executable: 'C:\\test\\nvidia-smi.exe', parentPid: 100 }], data);
  data.engine.records.push(rec('engine-exit', { incarnation, code: 0, exitClass: 'REQUESTED',
    reason: 'QUIT', exitedAt: at(10), observedAt: at(10) }));
  const out = census.exitResult(input, { processExits: input.targets.map(t => ({ pid: t.pid,
    creationFileTimeUtc: t.creationFileTimeUtc, exitCode: 0, atMs: Date.parse(at(10)) })) }, 'test', data);
  const host = out.processExits.find(p => p.pid === 103);
  assert.ok(host, 'every unsampled console witness must have a stop-report row');
  assert.equal(host.ledgerClass, 'REQUESTED'); assert.equal(host.ledgerReason, 'probe completed');
  const sampled = out.processExits.find(p => p.pid === 105);
  assert.ok(sampled, 'a sampled nvidia identity must not disappear from the report');
  assert.equal(sampled.ledgerClass, 'UNKNOWN'); assert.ok(sampled.ledgerReason);
  assert.ok(out.exitAccountingGaps.some(g => g.pid === 105));
});

test('crash reconciliation distinguishes an identity lookup failure from disappearance and retries job closure', async () => {
  assert.equal(typeof census.reconcileCrashedHelpers, 'function', 'the surviving owner must reconcile helper receipts');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'helper-owner-receipts-'));
  try {
    const data = fixture(); data.children.records.pop();
    data.engine.records[1].line.exitClass = 'UNEXPECTED';
    const ledgerDir = path.join(dir, 'runtime/exit-ledger'); fs.mkdirSync(ledgerDir, { recursive: true });
    for (const [family, records] of Object.entries(data)) fs.writeFileSync(path.join(ledgerDir, `${family}.v1.jsonl`),
      records.records.map((r, i) => JSON.stringify({ schemaVersion: 1, type: r.type, seq: i + 1,
        at: at(i), writer: { ...r.writer, startedAt: at(0) }, ...r.line })).join('\n') + '\n');
    const written = [], append = (type, fields) => { written.push({ type, ...fields }); return true; };
    await census.reconcileCrashedHelpers(dir, incarnation.session, async () => { throw new Error('access denied'); }, append);
    assert.equal(written[0].exitClass, 'UNKNOWN'); assert.equal(written[0].osCheck, 'IDENTITY_UNKNOWN');
    assert.match(written[0].reason, /lookup failed/); assert.equal(written[0].exitedAt, null);
    written.length = 0; let queries = 0;
    await census.reconcileCrashedHelpers(dir, incarnation.session, async () => new Map([[102,
      ++queries === 1 ? { present: true, creationFileTime: birth(2) } : { present: false }]]), append);
    assert.ok(queries > 1, 'the helper can remain alive briefly while the crashed Engine job closes');
    assert.equal(written[0].exitClass, 'OWNER_DIED'); assert.equal(written[0].cause, 'ENGINE_CRASH');
    assert.equal(written[0].code, null); assert.equal(written[0].exitedAt, null);
  } finally { fs.rmSync(dir, { recursive: true }); }
});
