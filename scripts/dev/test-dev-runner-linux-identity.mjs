import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { test } from 'node:test';
const require = createRequire(import.meta.url);
const { parseLinuxStat, readLinuxProcess, verifyProcessIdentity, readProcessTable } = require('./lib/process-identity.cjs');
const { ownerLiveness } = require('./lib/owner-presence.cjs');
const { lookupProcessIdentities, captureSpawnedIdentity } = require('./lib/engine-actuator.cjs');
const bootId = '333fd9cf-f547-4117-a537-fc8be3c44466';
const stat = (state = 'S', ticks = '9007199254740993') => `42 (name ) with ) spaces) ${state} 1 ${Array(17).fill('0').join(' ')} ${ticks} 0`;

test('Linux stat parses field 22 after the final parenthesis without losing precision', () => {
  assert.deepEqual(parseLinuxStat(stat()), { state: 'S', parentPid: 1, startTicks: '9007199254740993', name: 'name ) with ) spaces' });
});

test('Linux birth reads distinguish gone, zombie, changed and unreadable evidence', () => {
  const files = (file) => file.endsWith('/stat') ? stat() : file.endsWith('/boot_id') ? bootId : 'node\0fixture';
  const options = { readFile: files, readlink: () => '/usr/bin/node' };
  const seen = readLinuxProcess(42, options);
  assert.equal(seen.processIdentity.startTicks, '9007199254740993');
  assert.equal(seen.commandLine, 'node fixture');
  assert.deepEqual(readLinuxProcess(42, { ...options, readFile: () => stat('Z') }), { present: false });
  let reads = 0;
  assert.equal(readLinuxProcess(42, { ...options, readFile: file => file.endsWith('/stat') ? stat('S', String(++reads)) : files(file) }), null);
  for (const code of ['ENOENT', 'EACCES']) {
    assert.equal(readLinuxProcess(42, { ...options, readFile: file => {
      if (file.endsWith('/boot_id')) throw Object.assign(new Error('unavailable'), { code, path: file });
      return files(file);
    } }), null, 'unreadable boot identity is unknown, even if the file is missing');
  }
  assert.deepEqual(readLinuxProcess(42, { ...options, readFile: file => {
    throw Object.assign(new Error('gone'), { code: 'ENOENT', path: file });
  } }), { present: false });
});

test('Linux birth and fingerprint are both mandatory; boot/reuse/unknown never authorise a match', () => {
  const processIdentity = { kind: 'linux-proc-v1', pid: 42, bootId, startTicks: '1234' };
  const record = { pid: 42, processIdentity, cmdlineFingerprint: 'fixture' };
  const row = { ProcessId: 42, processIdentity, CommandLine: 'node fixture' };
  const table = r => ({ ok: true, table: [r], readAt: Date.now() });
  assert.equal(verifyProcessIdentity({ record, table: table(row) }).verdict, 'match');
  assert.equal(verifyProcessIdentity({ record, table: table({ ...row, processIdentity: null }) }).verdict, 'refuse');
  assert.equal(verifyProcessIdentity({ record, table: table({ ...row, processIdentity: { ...processIdentity, startTicks: '1235' } }) }).verdict, 'mismatch');
  assert.equal(verifyProcessIdentity({ record, table: table({ ...row, CommandLine: 'node unrelated' }) }).verdict, 'mismatch');
  assert.equal(verifyProcessIdentity({ record, table: table({ ...row, CommandLine: '' }) }).verdict, 'refuse');
  const owner = { key: 'codex-linux-owner', pid: 42, processIdentity };
  assert.equal(ownerLiveness(owner, { table: table(row) }).state, 'alive');
  assert.equal(ownerLiveness(owner, { table: table({ ...row, processIdentity: null }) }).state, 'unknown');
  assert.equal(ownerLiveness(owner, { table: table({ ...row, processIdentity: { ...processIdentity, bootId: '444fd9cf-f547-4117-a537-fc8be3c44466' } }) }).state, 'ended');
});

test('Linux real process lookup, table and spawn capture use one birth identity', { skip: process.platform !== 'linux' }, async () => {
  const seen = readLinuxProcess(process.pid);
  assert.equal(seen.present, true);
  assert.deepEqual((await lookupProcessIdentities([process.pid])).get(process.pid), seen);
  const captured = captureSpawnedIdentity({ pid: process.pid, exitCode: null, signalCode: null });
  assert.deepEqual(captured.processIdentity, seen.processIdentity);
  const table = readProcessTable();
  assert.equal(table.ok, true);
  assert.deepEqual(table.table.find(r => r.ProcessId === process.pid).processIdentity, seen.processIdentity);
});

test('Linux ledger and stop observations retain typed identity without fabricated FILETIME', () => {
  const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
  const ledger = require('./lib/exit-ledger.cjs');
  const census = require('./lib/stop-exit-census.cjs');
  const compare = require('./lib/ledger-identity.cjs');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'linux-ledger-'));
  const id = { kind: 'linux-proc-v1', pid: 42, bootId, startTicks: '9007199254740993' };
  const incarnation = { session: 'linux-ledger', n: 1 };
  const writer = ledger.createLedgerWriter({ ledgerDir: ledger.ledgerDirectory(dir), family: 'engine', rotates: true,
    writer: { role: 'dev-runner', pid: process.pid, startedAt: Date.now() } });
  try {
    writer.startSession();
    assert.equal(writer.append('engine-start', { incarnation, pid: 42, creationFileTime: null, processIdentity: id,
      processStartedAt: null, executable: process.execPath, cause: 'test', supervisorKind: 'dev-runner' }), true);
    let resolution = census.ledgerResolution(census.ledgerView(census.readRunLedger(dir), incarnation.session));
    assert.deepEqual(resolution.unproven[0].processIdentity, id);
    assert.equal(resolution.unproven[0].creationFileTimeUtc, null);
    assert.equal(compare.birthMs(resolution.unproven[0]), null);
    const absent = census.lookupObservation({ pid: 42, processIdentity: id }, { present: false }, Date.now());
    assert.deepEqual(absent.processIdentity, id);
    assert.equal(census.lookupEndedIdentities([absent]).length, 1);
    const replacement = { ...id, startTicks: '9007199254740994' };
    const reused = census.lookupObservation({ pid: 42, processIdentity: id }, { present: true, processIdentity: replacement }, Date.now());
    assert.deepEqual(reused.observedProcessIdentity, replacement);
    assert.equal(reused.observedCreationFileTimeUtc, null);
    assert.equal(census.lookupEndedIdentities([reused]).length, 1);
    const previousGap = { pid: 42, processIdentity: id, reason: 'exit_unproven' };
    assert.equal(census.stopProves({ lookupEnded: [absent] })(previousGap), true);
    assert.equal(census.stopProves({ lookupEnded: [] })(previousGap), false);
    assert.equal(census.stopProves({ lookupEnded: [absent] })({ ...previousGap, processIdentity: replacement }), false);
    const inventory = census.readRunLedger(dir);
    const targets = census.exitTargets({ runId: incarnation.session, pids: { backendRootPid: 42 },
      identities: { backend: { pid: 42, processIdentity: id } } }, null, [], inventory);
    const result = census.exitResult(targets, { processExits: [] }, 'linux-test', inventory,
      census.ledgerView(inventory, incarnation.session), { lookupEnded: [absent] });
    const rows = result.processExits.filter(row => row.pid === 42);
    assert.ok(rows.length > 0);
    for (const row of rows) {
      assert.deepEqual(row.processIdentity, id, 'final stop projections retain the Linux birth');
      assert.equal(row.accounted, true, 'a confirmed absent birth is accounted, with its exit code unknown');
    }
    assert.equal(writer.append('engine-exit', { incarnation, exitClass: 'REQUESTED', code: 0, codeName: 'OK', observedAt: new Date(), exitedAt: null }), true);
    resolution = census.ledgerResolution(census.ledgerView(census.readRunLedger(dir), incarnation.session));
    assert.equal(resolution.unproven.length, 0);
    assert.deepEqual(resolution.ended[0].processIdentity, id);
    assert.equal(resolution.ended[0].creationFileTimeUtc, null);
    assert.equal(resolution.isEnded({ pid: 42, processIdentity: id }), true);
    assert.equal(resolution.isEnded({ pid: 42, processIdentity: { ...id, startTicks: '9007199254740994' } }), false);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});

test('Linux signals use only the pidfd helper and reject incomplete/mismatched tuples before execution', () => {
  const { signalLinuxProcess } = require('./lib/process-identity.cjs');
  const id = { kind: 'linux-proc-v1', pid: 42, bootId, startTicks: '1234' };
  const calls = [];
  const options = { helperPath: '/fixture/helper with spaces', exec: (...args) => { calls.push(args); return { status: 0 }; } };
  assert.equal(signalLinuxProcess(id, 'SIGTERM', options).ok, true);
  assert.deepEqual(calls[0][1], ['signal', '42', bootId, '1234', 'SIGTERM']);
  calls.length = 0;
  assert.equal(signalLinuxProcess({ pid: 43, processIdentity: id }, 'SIGKILL', options).ok, false);
  assert.equal(signalLinuxProcess({ ...id, startTicks: 1234 }, 'SIGKILL', options).ok, false);
  assert.equal(calls.length, 0);
});


test('Linux canonical identities admit tick zero/u64 and reject malformed tuples; all ended states agree', () => {
  const { linuxIdentity } = require('./lib/process-identity.cjs');
  const identity = { kind: 'linux-proc-v1', pid: 42, bootId, startTicks: '0' };
  for (const ticks of ['0', '18446744073709551615']) {
    assert.deepEqual(linuxIdentity({ ...identity, startTicks: ticks }), { ...identity, startTicks: ticks });
  }
  for (const ticks of ['00', '-1', '18446744073709551616', '999999999999999999999']) {
    assert.equal(linuxIdentity({ ...identity, startTicks: ticks }), null);
  }
  assert.equal(linuxIdentity({ ...identity, pid: 2147483648 }), null);
  assert.equal(linuxIdentity({ ...identity, bootId: bootId.toUpperCase() }), null);
  const options = { readFile: file => file.endsWith('/stat') ? stat('S', '0') : bootId, readlink: () => '/bin/sleep' };
  assert.equal(readLinuxProcess(42, options).processIdentity.startTicks, '0');
  for (const state of ['Z', 'X', 'x']) {
    assert.deepEqual(readLinuxProcess(42, { ...options, readFile: () => stat(state) }), { present: false });
  }
});
