import assert from 'node:assert/strict';
import test from 'node:test';
import identity from '../dev/lib/process-identity.cjs';
import { killOwned } from './verified-crash.mjs';

function linuxFixture() {
  const processIdentity = { kind: 'linux-proc-v1', pid: 4242,
    bootId: '333fd9cf-f547-4117-a537-fc8be3c44466', startTicks: '12345' };
  const record = { pid: 4242, processIdentity, cmdlineFingerprint: 'java owned-data' };
  const table = { ok: true, readAt: Date.now(), table: [
    { ProcessId: 4242, processIdentity, CommandLine: 'java owned-data' },
  ] };
  return { record, table };
}

test('Linux crash uses the birth-bound signal helper instead of a PID-only kill', t => {
  const { record, table } = linuxFixture();
  const calls = [];
  t.mock.method(identity, 'signalLinuxProcess', (birth, signal) => {
    calls.push({ birth, signal });
    return { ok: true, outcome: 'terminated', status: 0 };
  });
  const result = killOwned(record, 'owned-data', { table, now: () => 100,
    kill: () => assert.fail('a Linux crash must not use a PID-only kill') });
  assert.deepEqual(calls, [{ birth: record.processIdentity, signal: 'SIGKILL' }]);
  assert.deepEqual(result.processIdentity, record.processIdentity);
  assert.equal(result.issuedAtMs, 100);
});

test('Linux birth mismatch at the signal helper refuses the crash', t => {
  const { record, table } = linuxFixture();
  t.mock.method(identity, 'signalLinuxProcess', () => ({ ok: false, outcome: 'mismatch', status: 3 }));
  assert.throws(() => killOwned(record, 'owned-data', { table,
    kill: () => assert.fail('a refused Linux identity must never fall back to PID-only kill') }),
  /identity-bound crash.*mismatch/);
});

test('a reused Linux PID is refused before issuing any signal', t => {
  const { record, table } = linuxFixture();
  table.table[0] = { ...table.table[0],
    processIdentity: { ...record.processIdentity, startTicks: '12346' } };
  t.mock.method(identity, 'signalLinuxProcess', () => assert.fail('a reused PID must not be signalled'));
  assert.throws(() => killOwned(record, 'owned-data', { table,
    kill: () => assert.fail('a reused PID must not be killed') }), /Refusing unverified fault/);
});
