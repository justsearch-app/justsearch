import assert from 'node:assert/strict';
import test from 'node:test';
import { summarizeSemanticAvailability } from './bulk-fault-scenario.mjs';

test('availability before reload does not certify recovery', () => {
  const summary = summarizeSemanticAvailability([
    { at: 1, outcome: 'available' },
    { at: 10, outcome: 'reloading' },
    { at: 20, outcome: 'worker-starting' },
  ], 0, 30, []);
  assert.equal(summary.available, 1);
  assert.equal(summary.reloadingRefusals, 1);
  assert.equal(summary.recoveredAfterRefusal, false);
  assert.equal(summary.refusalWindowMs, 20);
});

test('only a matching vector response after the last refusal certifies recovery', () => {
  const summary = summarizeSemanticAvailability([
    { at: 1, outcome: 'available' },
    { at: 10, outcome: 'reloading' },
    { at: 20, outcome: 'available-unmatched' },
    { at: 30, outcome: 'reloading' },
    { at: 40, outcome: 'available' },
  ], 0, 50, [{ status: 200, body: 'wrong document' }]);
  assert.equal(summary.recoveredAfterRefusal, true);
  assert.equal(summary.refusalWindowMs, 30);
  assert.equal(summary.unexpected, 1);
});
