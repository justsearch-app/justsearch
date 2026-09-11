/**
 * lib/transcript-cost.test.mjs — unit tests for `providerOf` and the
 * `provider` column added to every `PRICING` row (tempdoc 886 §12 PR 5a).
 *
 * `findPricing`'s existing behaviour is exercised extensively by
 * baseline-economics.test.mjs against individual fields (`.input`,
 * `.output`, etc); this file adds coverage for the new `provider` field
 * without duplicating that suite, plus dedicated `providerOf` cases.
 *
 * Run with: `node scripts/agent-analytics/lib/transcript-cost.test.mjs`
 */

import assert from 'node:assert/strict';
import { findPricing, providerOf, isKnownModel, isNonBillableModel, PRICING } from './transcript-cost.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- provider column on PRICING ------------------------------------------

run('every PRICING row carries provider: "anthropic"', () => {
  for (const [model, entry] of Object.entries(PRICING)) {
    assert.equal(entry.provider, 'anthropic', `${model} missing/wrong provider`);
  }
});

run('findPricing still returns byte-identical rate fields alongside the new provider column', () => {
  const p = findPricing('claude-opus-5');
  assert.equal(p.input, 5.0);
  assert.equal(p.output, 25.0);
  assert.equal(p.cache_write_5m, 6.25);
  assert.equal(p.cache_write_1h, 10.0);
  assert.equal(p.cache_read, 0.50);
  assert.equal(p.provider, 'anthropic');
});

run('findPricing on a fast-priced Opus row also carries provider', () => {
  const p = findPricing('claude-opus-5', null, 'fast');
  assert.equal(p.input, 10.0);
  assert.equal(p.provider, 'anthropic');
});

// --- providerOf ------------------------------------------------------------

run('providerOf resolves every known model to anthropic', () => {
  for (const model of Object.keys(PRICING)) {
    assert.equal(providerOf(model), 'anthropic', `${model}`);
  }
});

run('providerOf resolves a suffixed id via the same longest-prefix match findPricing uses', () => {
  assert.equal(providerOf('claude-opus-4-8[1m]'), 'anthropic');
  assert.equal(providerOf('claude-opus-5[1m]'), 'anthropic');
});

run('providerOf FAILS CLOSED: null for an unrecognized model, not a guess', () => {
  assert.equal(providerOf('gpt-4o'), null);
  assert.equal(providerOf('claude-made-up-9'), null);
  // No OpenAI/Codex row exists in PRICING (documented decision, module comment
  // above OPUS_CURRENT) — Codex CLI runs on subscription, not metered tokens,
  // so pricing it would be a modelled number, not a verified one.
  assert.equal(providerOf('gpt-5-codex'), null);
});

run('providerOf(null/undefined) is null, matching findPricing/isKnownModel\'s absent-model handling', () => {
  assert.equal(providerOf(null), null);
  assert.equal(providerOf(undefined), null);
});

run('providerOf and isKnownModel agree on the known/unknown boundary for every case above', () => {
  assert.equal(isKnownModel('claude-opus-5'), true);
  assert.equal(providerOf('claude-opus-5') !== null, true);
  assert.equal(isKnownModel('gpt-4o'), false);
  assert.equal(providerOf('gpt-4o'), null);
});

// --- isNonBillableModel (tempdoc 908 §4.5) ------------------------------------

run('isNonBillableModel: `<synthetic>` is known-non-billable', () => {
  assert.equal(isNonBillableModel('<synthetic>'), true);
});

run('isNonBillableModel: a genuinely unknown model is NOT non-billable (still an alarm)', () => {
  assert.equal(isNonBillableModel('gpt-99-totally-unknown'), false);
});

run('isNonBillableModel: a real priced model is not non-billable either', () => {
  assert.equal(isNonBillableModel('claude-opus-5'), false);
});

run('isNonBillableModel(null/undefined) is false, not a Set-lookup throw', () => {
  assert.equal(isNonBillableModel(null), false);
  assert.equal(isNonBillableModel(undefined), false);
});

// --- report ------------------------------------------------------------------

if (failures.length) {
  console.error(`transcript-cost.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`transcript-cost.test: ${passed} passed`);
