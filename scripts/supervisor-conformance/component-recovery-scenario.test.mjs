import assert from 'node:assert/strict';
import test from 'node:test';
import * as scenario from './component-recovery-scenario.mjs';

// The manifest's startedAt is the Engine's ProcessHandle startInstant: the creation FILETIME
// truncated to the millisecond. 2026-10-06T09:00:00.123Z is FILETIME 134357508001230000.
const executable = 'F:\\data\\native-bin\\llama-server\\llama-server.exe';
const child = { pid: 4242, startedAt: '2026-10-06T09:00:00.123Z' };
const row = (birth, pid = 4242) => ({ ProcessId: pid, CreationFileTimeUtc: birth,
  CommandLine: `"${executable}" --port 8081` });

test('Spec F §6.1: the generative scenario names its managed child by the shared birth rule, without a tolerance window', () => {
  assert.equal(typeof scenario.managedChildIs, 'function', 'the scenario exposes its identity rule');
  // The same process: CIM's birth carries sub-millisecond ticks the startInstant truncated.
  assert.equal(scenario.managedChildIs(child, row('134357508001234567'), executable), true);
  // A process born 400 ms later under the same pid is another process; a ±1 s window accepted it.
  assert.equal(scenario.managedChildIs(child, row('134357508005230000'), executable), false);
  assert.equal(scenario.managedChildIs(child, row('134357508001220000'), executable), false);
  assert.equal(scenario.managedChildIs(child, row('134357508001234567', 4243), executable), false);
  assert.equal(scenario.managedChildIs(child, { ...row('134357508001234567'), CommandLine: 'other.exe' }, executable), false);
  assert.equal(scenario.managedChildIs(child, undefined, executable), false);
  assert.equal(scenario.managedChildIs({ ...child, startedAt: 'not-an-instant' }, row('134357508001234567'), executable), false);
});
