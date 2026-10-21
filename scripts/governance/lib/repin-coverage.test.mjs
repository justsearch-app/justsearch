/**
 * Tempdoc 918 item 1, the "and every ratchet inherits it" half.
 *
 * The repin rule is a library (`declared-growth-repin.mjs`) rather than a runner-level pass,
 * because only the gate can tell its live-exceedance note from its licensed-pin-raise note — see
 * that module's header for the measurement. The cost of that choice is that a gate CAN forget to
 * call it, which is the silent hole this file closes: every registered gate whose changeset
 * vocabulary licenses a live exceedance must reference the rule module, or appear in `EXEMPT` with
 * a reason. A new ratchet gate therefore fails this test on the day it lands, not two lanes later.
 *
 * Three properties, in order: no gate licenses growth without either wiring the rule or an EXEMPT
 * reason; no EXEMPT entry names a gate the registry no longer carries (a stale exemption reads as
 * authority); and the set of gates that DO call the rule is pinned by name, so a gate quietly
 * losing its wiring is a diff, not a smaller number.
 *
 * Run with: `node scripts/governance/lib/repin-coverage.test.mjs`
 */

import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { GROWTH_LICENSING_CLASSIFICATIONS } from './declared-growth-repin.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const RULE_MODULE = 'declared-growth-repin';

/**
 * Gates whose changeset vocabulary contains a growth-licensing word but which have no
 * live-exceedance branch for it to guard. Each entry states WHY, so an exemption is a claim
 * someone can check rather than a hole.
 */
const EXEMPT = {
  'consumer-drift':
    'slot-retraction / grace-extension license a change to the slot register itself; the floor ' +
    'IS the declared artifact.',
  'ssot-catalog-sync':
    'intentional-divergence / mirror-retirement license a mirror pair diverging; there is no pin.',
  'runtime-state':
    'register-row vocabulary (new-rule-registered / tier-change / rule-retired) — it licenses a ' +
    'register EDIT, and there is no measured value held against a numeric pin to advance.',
  'register-guard-resolution':
    'guard-downgrade licenses a register guard string weakening; no numeric ratchet.',
  'adr-coverage':
    'loads changesets for vocabulary validation only — its verdicts come from probe evaluation ' +
    'and review windows, not from a measured value against a pin.',
  wire:
    'protobuf evolution-rule vocabulary in its own parser ' +
    '(gates/wire/protobuf-changeset-parser.mjs); a breaking change is licensed outright, there ' +
    'is no count to pin.',
};

const registry = JSON.parse(readFileSync(resolve(REPO_ROOT, 'governance/registry.v1.json'), 'utf8'));

let passed = 0;
const failures = [];
const run = (label, fn) => {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
};

/** Enforcer source plus the shared factory it may delegate to, and its classifications sibling. */
function gateSources(gate) {
  const enforcerPath = resolve(REPO_ROOT, gate.enforcer);
  const texts = [readFileSync(enforcerPath, 'utf8')];
  for (const sibling of ['classifications.mjs', 'truth-table.mjs']) {
    const p = resolve(dirname(enforcerPath), sibling);
    if (existsSync(p)) texts.push(readFileSync(p, 'utf8'));
  }
  // A gate built on the shared per-file ratchet factory inherits the rule from it.
  if (texts[0].includes('ratchet-gate.mjs')) {
    texts.push(readFileSync(resolve(REPO_ROOT, 'scripts/governance/lib/ratchet-gate.mjs'), 'utf8'));
  }
  return texts;
}

run('every growth-licensing gate wires the repin rule (or is exempt with a reason)', () => {
  const unwired = [];
  for (const gate of registry.gates) {
    if (!gate.changesetsDir) continue;
    const [enforcerText, ...rest] = gateSources(gate);
    const vocabulary = [enforcerText, ...rest].join('\n');
    const licenses = GROWTH_LICENSING_CLASSIFICATIONS.some((c) => vocabulary.includes(`'${c}'`));
    if (!licenses) continue;
    if (gate.id in EXEMPT) {
      assert.ok(EXEMPT[gate.id].length > 30, `exemption for '${gate.id}' needs a real reason`);
      continue;
    }
    const wired = [enforcerText, ...rest].some((t) => t.includes(RULE_MODULE));
    if (!wired) unwired.push(gate.id);
  }
  assert.deepEqual(
    unwired, [],
    `these gates license growth but never call the repin rule — import ${RULE_MODULE}.mjs at the ` +
      'branch where a measured value exceeds its live pin, or add an EXEMPT entry saying why the ' +
      'gate has no such branch (tempdoc 918).',
  );
});

run('no EXEMPT entry names a gate that is not in the registry', () => {
  const ids = new Set(registry.gates.map((g) => g.id));
  const stale = Object.keys(EXEMPT).filter((id) => !ids.has(id));
  assert.deepEqual(stale, [], 'stale exemptions outlive their reason and read as authority');
});

/**
 * The gates that ARE wired, named. A count floor would let a gate drop its wiring and be replaced
 * by an unrelated one without anything noticing, so this is an exact set: adding a ratchet gate
 * means adding it here, and removing one means removing it here — both edits are reviewable.
 * `dead-code` and `config-surface` are the two surviving gates that actually suffered the defect
 * (#614→#613/#615 and #517→854 / #595→885 respectively), so they are asserted by name first.
 */
const WIRED = [
  'atom-fork-ratchet',
  'config-surface',
  'dead-code',
  'npm-audit',
  'test-efficacy',
];

run('the wired set is exactly the ratchet gates that can carry the rule', () => {
  const wired = registry.gates
    .filter((g) => g.changesetsDir && gateSources(g).some((t) => t.includes(RULE_MODULE)))
    .map((g) => g.id);
  for (const id of ['dead-code', 'config-surface']) {
    assert.ok(wired.includes(id), `${id} must be wired — it is a gate the defect actually hit`);
  }
  assert.deepEqual(
    [...wired].sort(), [...WIRED].sort(),
    'the set of gates calling the repin rule changed. If a gate was added, wire it and add it to ' +
      'WIRED. If a gate stopped calling the rule, that is a regression unless the gate itself was ' +
      'retired — in which case remove it here in the same diff (tempdoc 918).',
  );
});

if (failures.length > 0) {
  console.error(`repin-coverage.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`repin-coverage.test: all ${passed} checks passed`);
