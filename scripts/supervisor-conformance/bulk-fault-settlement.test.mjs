import assert from 'node:assert/strict';
import test from 'node:test';
import { settledPromotedSnapshot } from './bulk-fault-scenario.mjs';

const target = 'g-01a0f535-6cda-7498-8160-8830e9651154';
const settled = () => ({
  state: { format_version: 2, active_generation: target,
    migration_state: 'IDLE', migration_paused: false, updated_at_ms: 1790820342112 },
  operation: { state: 'COMPLETE', phase: 'settled' },
  generationManifest: { generation_id: target },
});

test('terminal success and IDLE do not settle a promoted predecessor', () => {
  const early = settled();
  early.state.previous_generation = 'g-20261001-020516';
  early.state.updated_at_ms = 1790820342082;
  assert.equal(settledPromotedSnapshot(early, target), null);
  const retired = settled();
  assert.strictEqual(settledPromotedSnapshot(retired, target), retired);
});

test('only the exact target with no candidate and no predecessor is settled', () => {
  for (const patch of [
    { active_generation: 'g-other' },
    { migration_state: 'SWITCHING' },
    { building_generation: target },
    { previous_generation: target },
  ]) {
    const observed = settled();
    Object.assign(observed.state, patch);
    assert.equal(settledPromotedSnapshot(observed, target), null, JSON.stringify(patch));
  }
  assert.equal(settledPromotedSnapshot(null, target), null);
  assert.equal(settledPromotedSnapshot({}, target), null);
});

test('settlement preserves every field for strict same-key replay comparison', () => {
  const before = settled();
  const original = structuredClone(before);
  assert.strictEqual(settledPromotedSnapshot(before, target), before);
  assert.deepEqual(before, original);
  const after = structuredClone(before);
  after.state.updated_at_ms += 1;
  assert.notDeepEqual(settledPromotedSnapshot(after, target), before);
  before.state.previous_generation = null;
  before.state.building_generation = null;
  assert.strictEqual(settledPromotedSnapshot(before, target), before);
  assert.ok(Object.hasOwn(before.state, 'previous_generation'));
});
