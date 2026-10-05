#!/usr/bin/env node
/**
 * run-ui-web-gates.mjs tests (tempdoc 872). The runner parses PROSE, so the risk is a silent
 * shrink: a reworded recipe that parses to fewer commands and still prints "N/N passed".
 * These tests pin (a) the parse of the real register against the names literally present in
 * the recipe, (b) the floor guard, (c) the kernel-gate expansion.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseUiWebGateCommands, EXPECTED_MIN } from './run-ui-web-gates.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const register = JSON.parse(fs.readFileSync(path.join(ROOT, 'governance', 'consult-register.v1.json'), 'utf8'));
const entry = (register.entries ?? register.regions ?? []).find((e) => e.id === 'ui-web-gates');

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

run('real register parses to at least the floor', () => {
  const cmds = parseUiWebGateCommands(register);
  assert.ok(cmds.length >= EXPECTED_MIN, `parsed ${cmds.length} < floor ${EXPECTED_MIN}`);
});

run('every script name literally present in the recipe is in the parsed list (string diff, not count)', () => {
  const parsed = new Set(parseUiWebGateCommands(register).map((c) => c[1]));
  // Only the gate-listing lines; the closing "typecheck + unit tests" line is not a gate list.
  const recipeText = entry.recipe.filter((l) => /scripts\/ci\/<name>\.mjs\)|additionally:/.test(l)).join('\n');
  const named = new Set();
  for (const m of recipeText.matchAll(/\b((?:check|gen|strip)-[a-z0-9-]+)\b/g)) named.add(`scripts/ci/${m[1]}.mjs`);
  for (const m of recipeText.matchAll(/node (scripts\/ci\/[a-z0-9-]+\.test\.mjs)/g)) named.add(m[1]);
  const missing = [...named].filter((n) => !parsed.has(n));
  assert.deepEqual(missing, [], `recipe names not parsed: ${missing.join(', ')}`);
  for (const p of parsed) {
    if (p.startsWith('scripts/ci/')) assert.ok(fs.existsSync(path.join(ROOT, p)), `parsed script does not exist: ${p}`);
  }
});

run('a self-test parenthetical is parsed as a command, not stripped', () => {
  // Synthetic fixture (930 chunk H retired the recipe's one prior real example,
  // check-printable-keybinding-policy's self-test) — the mechanism still needs coverage
  // independent of whether any current recipe line happens to carry one.
  const fake = {
    entries: [
      {
        id: 'ui-web-gates',
        recipe: [
          'Any modules/ui-web/src/** edit — run the ui-web gate set before merge (node scripts/ci/<name>.mjs): check-fixture-gate (with its self-test: node scripts/ci/check-fixture-gate.test.mjs).',
        ],
      },
    ],
  };
  const parsed = parseUiWebGateCommands(fake).map((c) => c.join(' '));
  assert.ok(parsed.includes('node scripts/ci/check-fixture-gate.test.mjs'), parsed.join('\n'));
});

run('a reworded recipe (marker dropped) parses below the floor — the guard has something to catch', () => {
  const reworded = JSON.parse(JSON.stringify(register));
  const e = (reworded.entries ?? reworded.regions).find((x) => x.id === 'ui-web-gates');
  e.recipe = e.recipe.map((l) => l.replace('(node scripts/ci/<name>.mjs)', ''));
  const n = parseUiWebGateCommands(reworded).length;
  assert.ok(n < EXPECTED_MIN, `reworded recipe still parsed ${n} >= ${EXPECTED_MIN}; the floor no longer guards anything`);
});

run('kernel gate list expands to one run.mjs invocation per id', () => {
  const fake = { entries: [{ id: 'ui-web-gates', recipe: ['Plus the kernel gates: node scripts/governance/run.mjs --gate a-b,c-d --mode gate.'] }] };
  const cmds = parseUiWebGateCommands(fake).map((c) => c.join(' '));
  assert.deepEqual(cmds, [
    'node scripts/governance/run.mjs --gate a-b --mode gate',
    'node scripts/governance/run.mjs --gate c-d --mode gate',
  ]);
});

if (failures.length) {
  console.error(`run-ui-web-gates.test: ${failures.length} failure(s)\n  ` + failures.join('\n  '));
  process.exit(1);
}
console.log(`run-ui-web-gates.test: ${passed} passed`);
