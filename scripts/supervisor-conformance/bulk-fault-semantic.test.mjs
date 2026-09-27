import assert from 'node:assert/strict';
import test from 'node:test';
import { inPlaceSemanticViolations, summarizeSemanticAvailability } from './bulk-fault-scenario.mjs';

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

const inPlaceRun = () => [
  { at: 0, outcome: 'available', hybrid: 'available', encoders: 'READY' },
  { at: 10, outcome: 'worker-starting', hybrid: 'worker-starting', encoders: null },
  { at: 20, outcome: 'transport', hybrid: 'transport', encoders: null },
  { at: 30, outcome: 'available', hybrid: 'available', encoders: 'READY' },
  { at: 40, outcome: 'reloading', hybrid: 'available', encoders: 'RELOADING' },
  { at: 50, outcome: 'reloading', hybrid: 'available', encoders: 'RELOADING' },
  { at: 60, outcome: 'reloading', hybrid: 'available', encoders: 'READY' },
  { at: 70, outcome: 'available', hybrid: 'available', encoders: 'READY' },
];

test('a clean in-place transition reports the restart outage separately and passes D1-14', () => {
  const summary = summarizeSemanticAvailability(inPlaceRun(), 0, 80, []);
  assert.equal(summary.apiOutageWindowMs, 10);
  assert.equal(summary.apiOutageSamples, 2);
  assert.equal(summary.refusalWindowMs, 30);
  assert.equal(summary.refusalOutsideReloading, 0, 'one neighbouring sample of slack');
  assert.equal(summary.reloadingIntervalMs, 20);
  assert.equal(summary.hybridBreaksInRefusalWindow, 0);
  assert.deepEqual(inPlaceSemanticViolations(summary), []);
});

test('a vector refusal away from RELOADING violates the structural window', () => {
  const run = inPlaceRun();
  run[1] = { at: 10, outcome: 'reloading', hybrid: 'available', encoders: 'READY' };
  const summary = summarizeSemanticAvailability(run, 0, 80, []);
  assert.equal(summary.refusalOutsideReloading, 1);
  assert.ok(inPlaceSemanticViolations(summary).includes('vector refused outside RELOADING'));
});

test('hybrid failing inside the refusal window violates keyword continuity', () => {
  const run = inPlaceRun();
  run[5] = { ...run[5], hybrid: 'unexpected-500' };
  const summary = summarizeSemanticAvailability(run, 0, 80, []);
  assert.equal(summary.hybridBreaksInRefusalWindow, 1);
  assert.ok(inPlaceSemanticViolations(summary)
    .includes('hybrid search failed inside the refusal window'));
});

test('samples without a hybrid probe cannot certify keyword continuity', () => {
  const run = inPlaceRun().map(({ hybrid, ...sample }) => sample);
  const summary = summarizeSemanticAvailability(run, 0, 80, []);
  assert.ok(inPlaceSemanticViolations(summary).includes('hybrid search was not sampled'));
});
