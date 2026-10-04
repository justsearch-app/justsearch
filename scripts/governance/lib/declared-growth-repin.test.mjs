/**
 * Tempdoc 918 item 1 — a declared-growth changeset must re-pin in the same diff.
 *
 * Two layers are tested here:
 *   (1) the shared rule module itself — rule id, direction handling, and the four message shapes
 *       the pin-movement clause can take;
 *   (2) the rule AS THE GATES SEE IT — three real enforcers (`module-deps`, `config-surface`,
 *       `dead-code`) driven through the four cases the rule has to distinguish:
 *         growth declared + pin advanced to the measured value  → pass
 *         growth declared + pin unchanged                       → FAIL, new rule id
 *         growth declared + pin advanced but below measured     → FAIL, new rule id
 *         no growth declared                                    → untouched (still silent-growth)
 *
 * The three enforcers are picked for SHAPE, not convenience: `module-deps` is a per-row count
 * ratchet over a JSON report, `config-surface` a fixed-metric ratchet whose rows are named metrics,
 * `dead-code` a per-file ratchet whose count is summed out of a knip report. The FLOOR direction
 * (`declared-regression`, where the pin must fall rather than rise) is unit-tested here against
 * `test-efficacy`'s shape and driven through the real `npm-audit` and `test-efficacy` enforcers in
 * `repin-fires-per-gate.test.mjs`.
 *
 * Run with: `node scripts/governance/lib/declared-growth-repin.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  GROWTH_LICENSING_CLASSIFICATIONS,
  REPIN_REGRESSION_RULE_SUFFIX,
  REPIN_RULE_SUFFIX,
  repinFinding,
  repinRuleDescription,
  repinRuleId,
} from './declared-growth-repin.mjs';
import { enforceModuleDeps } from '../gates/module-deps/enforcer.mjs';
import { enforceConfigSurface } from '../gates/config-surface/enforcer.mjs';
import { enforceDeadCode } from '../gates/dead-code/enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- (1) the shared rule module ------------------------------------------------------------

await run('rule id is <prefix>/declared-growth-without-repin and is described', () => {
  assert.equal(repinRuleId('dead-code'), 'dead-code/declared-growth-without-repin');
  assert.equal(
    repinRuleId('test-efficacy', REPIN_REGRESSION_RULE_SUFFIX),
    'test-efficacy/declared-regression-without-repin',
  );
  const desc = repinRuleDescription('module-deps');
  assert.deepEqual(Object.keys(desc), ['module-deps/declared-growth-without-repin']);
  assert.match(desc['module-deps/declared-growth-without-repin'], /not advanced to the/);
});

await run('the message names the pin file, the row, the measured value and the remedy', () => {
  const f = repinFinding({
    rulePrefix: 'dead-code', classification: 'declared-growth',
    row: 'modules/ui-web/src/api/schema-types/index.ts', measured: 53, livePin: 51, priorPin: 51,
    baselineFile: 'gates/dead-code/baseline.txt', unit: 'unused exports',
    pinLine: 'modules/ui-web/src/api/schema-types/index.ts 53 <today>',
  });
  assert.equal(f.ruleId, 'dead-code/declared-growth-without-repin');
  assert.equal(f.level, 'error');
  assert.match(f.message, /gates\/dead-code\/baseline\.txt/, 'names the pin file');
  assert.match(f.message, /schema-types\/index\.ts/, 'names the row');
  assert.match(f.message, /Measured 53 unused exports/, 'names the measured value');
  assert.match(f.message, /Remedy, in THIS commit/, 'names the remedy');
  assert.match(f.message, /unchanged in this diff/, 'says the pin did not move');
});

await run('a pin that moved but fell short reads differently from one that never moved', () => {
  const short = repinFinding({
    rulePrefix: 'module-deps', classification: 'declared-growth', row: 'modules/core', measured: 9,
    livePin: 7, priorPin: 5, baselineFile: 'gates/module-deps/baseline.txt',
  });
  assert.match(short.message, /moved 5 → 7 in this diff but still short of the measured value/);
  const unknown = repinFinding({
    rulePrefix: 'module-deps', classification: 'declared-growth', row: 'modules/core', measured: 9,
    livePin: 7, baselineFile: 'gates/module-deps/baseline.txt',
  });
  assert.doesNotMatch(unknown.message, /in this diff/, 'no prior pin ⇒ no claim about movement');
});

await run('a floor gate gets floor wording, not ceiling wording', () => {
  const f = repinFinding({
    rulePrefix: 'test-efficacy', classification: 'strength-regression', row: 'search-fusion',
    measured: 41, livePin: 50, priorPin: 52,
    baselineFile: 'gates/test-efficacy/strength-baseline.v1.json',
    suffix: REPIN_REGRESSION_RULE_SUFFIX, direction: 'regression',
  });
  assert.equal(f.ruleId, 'test-efficacy/declared-regression-without-repin');
  assert.match(f.message, /moved 52 → 50 in this diff but still short/,
    'lowering a floor is movement TOWARDS the measured value');
  const wrongWay = repinFinding({
    rulePrefix: 'test-efficacy', classification: 'strength-regression', row: 'search-fusion',
    measured: 41, livePin: 54, priorPin: 52,
    baselineFile: 'gates/test-efficacy/strength-baseline.v1.json',
    suffix: REPIN_REGRESSION_RULE_SUFFIX, direction: 'regression',
  });
  assert.match(wrongWay.message, /the wrong way/);
});

await run('a caller that cannot supply measured/livePin degrades to "does not carry this row"', () => {
  // Reachable through `makeRatchetGate`, whose `detect()` contract does not oblige a client to
  // return `count`/`base` — an omitted number must produce an honest sentence, not `undefined`.
  const f = repinFinding({
    rulePrefix: 'atom-fork-ratchet', classification: 'declared-growth',
    row: 'modules/ui-web/src/shell-v0/sub/probe.ts',
    baselineFile: 'scripts/ci/atom-fork-ratchet-baseline.v1.json',
    pinLine: '"modules/ui-web/src/shell-v0/sub/probe.ts": 1',
  });
  assert.match(f.message, /does not carry this row/);
  assert.match(f.message, /shell-v0\/sub\/probe\.ts/);
  assert.doesNotMatch(f.message, /undefined|null/, 'an absent number must not leak into the prose');
});

await run('the growth-licensing vocabulary excludes baseline-edit-only classifications', () => {
  for (const shrink of [
    'unit-renormalization', 'unused-export-shrink', 'monotonic-shrink', 'dep-shrink',
    'severity-decrease', 'seam-retraction', 'tier-change', 'rule-retired',
    'new-rule-registered', 'slot-retraction', 'grace-extension', 'intentional-divergence',
    'mirror-retirement', 'guard-downgrade',
  ]) {
    assert.ok(!GROWTH_LICENSING_CLASSIFICATIONS.includes(shrink),
      `'${shrink}' licenses a baseline EDIT, not a live exceedance — it must not demand a re-pin`);
  }
  assert.ok(GROWTH_LICENSING_CLASSIFICATIONS.includes('declared-growth'));
  assert.equal(REPIN_RULE_SUFFIX, 'declared-growth-without-repin');
});

// --- (2) the rule as the gates see it ------------------------------------------------------

/**
 * A fixture repo root. `_baseline/<path>` is how `readPriorBaselineText` supplies the PIN AS IT
 * STOOD AT THE PR BASE in fixtureMode — which is what makes "did the pin move in this diff"
 * testable without a git history.
 */
function scaffold(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'repin-'));
  tmpDirs.push(root);
  for (const [rel, content] of Object.entries(files)) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }
  return root;
}

const CHANGESET = '---\nclassification: declared-growth\ntempdoc: 918\n---\nDeclared for the test.\n';

const ids = (r) => r.findings.map((f) => f.ruleId);
/** Only the ERROR findings decide the verdict; the note-level ones are commentary. */
const errorIds = (r) => r.findings.filter((f) => f.level === 'error').map((f) => f.ruleId);

// -- module-deps: `<module> <count> <date>` per-row counts read out of a JSON report.

const DEPS_GATE = {
  id: 'module-deps',
  baseline: { path: 'gates/module-deps/baseline.txt' },
  changesetsDir: 'gates/module-deps/.changesets',
  config: { reportPath: 'tmp/module-deps.json' },
};
const MODULE = 'modules/core';
/** Three production deps, so the measured count is 3. */
const THREE_DEPS = JSON.stringify({
  modules: [{ name: MODULE, productionDeps: ['a', 'b', 'c'] }],
});

async function depsOn(files) {
  const root = scaffold(files);
  return enforceModuleDeps({
    repoRoot: root, gate: DEPS_GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

await run('module-deps: growth declared + pin advanced to the measured value → pass', async () => {
  const r = await depsOn({
    'tmp/module-deps.json': THREE_DEPS,
    'gates/module-deps/baseline.txt': `${MODULE} 3 2026-09-03\n`,
    '_baseline/gates/module-deps/baseline.txt': `${MODULE} 1 2026-09-01\n`,
    'gates/module-deps/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass', `expected pass, got ${r.verdict}: ${ids(r).join(', ')}`);
  assert.ok(!ids(r).includes('module-deps/declared-growth-without-repin'));
});

await run('module-deps: growth declared + pin unchanged → FAIL with the new rule id', async () => {
  const r = await depsOn({
    'tmp/module-deps.json': THREE_DEPS,
    'gates/module-deps/baseline.txt': `${MODULE} 1 2026-09-01\n`,
    '_baseline/gates/module-deps/baseline.txt': `${MODULE} 1 2026-09-01\n`,
    'gates/module-deps/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'fail');
  assert.deepEqual(errorIds(r), ['module-deps/declared-growth-without-repin']);
  const m = r.findings[0].message;
  assert.match(m, /Measured 3 cross-module deps/);
  assert.match(m, /the pin in gates\/module-deps\/baseline\.txt is 1, unchanged in this diff/);
});

await run('module-deps: growth declared + pin advanced but below measured → FAIL', async () => {
  const r = await depsOn({
    'tmp/module-deps.json': THREE_DEPS,
    'gates/module-deps/baseline.txt': `${MODULE} 2 2026-09-03\n`,
    '_baseline/gates/module-deps/baseline.txt': `${MODULE} 1 2026-09-01\n`,
    'gates/module-deps/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'fail');
  assert.deepEqual(errorIds(r), ['module-deps/declared-growth-without-repin']);
  assert.match(r.findings[0].message, /moved 1 → 2 in this diff but still short/);
});

await run('module-deps: no growth declared → untouched, still silent-growth', async () => {
  const r = await depsOn({
    'tmp/module-deps.json': THREE_DEPS,
    'gates/module-deps/baseline.txt': `${MODULE} 1 2026-09-01\n`,
    '_baseline/gates/module-deps/baseline.txt': `${MODULE} 1 2026-09-01\n`,
  });
  assert.equal(r.verdict, 'fail');
  assert.deepEqual(ids(r), ['module-deps/silent-growth'],
    'without a changeset the pre-existing rule is what must fire, not the new one');
});

await run('module-deps: a shrink under a declared changeset still rebalances, not fails', async () => {
  const r = await depsOn({
    'tmp/module-deps.json': THREE_DEPS,
    'gates/module-deps/baseline.txt': `${MODULE} 5 2026-09-03\n`,
    '_baseline/gates/module-deps/baseline.txt': `${MODULE} 5 2026-09-01\n`,
    'gates/module-deps/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass');
  assert.deepEqual(ids(r), ['module-deps/rebalance-available']);
});

// -- config-surface: the same four cases through a truth-table-driven, fixed-metric gate.

const CONFIG_GATE = {
  id: 'config-surface',
  baseline: { path: 'gates/config-surface/baseline.txt' },
  changesetsDir: 'gates/config-surface/.changesets',
  config: { reportPath: 'tmp/matrix.json' },
};
/** Twelve declared yaml keys, so the measured value for the `yaml_keys` row is 12. */
const MATRIX = JSON.stringify({ yamlKeyCount: 12, envSyspropPairCount: 1, configKeyCount: 1, applyScopeCount: 2 });
const pins = (yamlKeys, date) =>
  `yaml_keys ${yamlKeys} ${date}\nenv_sysprop_pairs 1 ${date}\nconfig_keys 1 ${date}\napply_scope 2 ${date}\n`;

async function configOn(files) {
  const root = scaffold(files);
  return enforceConfigSurface({
    repoRoot: root, gate: CONFIG_GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

await run('config-surface: apply scope is independently ratcheted and cannot disappear', async () => {
  const files = {
    'tmp/matrix.json': JSON.stringify({ yamlKeyCount: 12, envSyspropPairCount: 1, configKeyCount: 1, applyScopeCount: 3 }),
    'gates/config-surface/baseline.txt': pins(12, '2026-09-22'),
    '_baseline/gates/config-surface/baseline.txt': pins(12, '2026-09-22'),
  };
  const silent = await configOn(files);
  assert.equal(silent.verdict, 'fail');
  assert.ok(silent.findings.some((f) => f.ruleId === 'config-surface/silent-growth' && f.message.includes('apply_scope')));
  const covered = await configOn({ ...files, 'gates/config-surface/.changesets/918.md': CHANGESET });
  assert.equal(covered.verdict, 'fail');
  assert.ok(covered.findings.some((f) => f.ruleId === 'config-surface/declared-growth-without-repin'
    && f.message.includes('Measured 3 configuration apply entries')));
  const repinned = await configOn({ ...files,
    'gates/config-surface/baseline.txt': pins(12, '2026-09-22').replace('apply_scope 2 ', 'apply_scope 3 '),
    'gates/config-surface/.changesets/918.md': CHANGESET });
  assert.equal(repinned.verdict, 'pass', ids(repinned).join(', '));
  for (const bad of [undefined, null, '2', -1, 1.5]) {
    const malformed = await configOn({ ...files,
      'tmp/matrix.json': JSON.stringify({ yamlKeyCount: 12, envSyspropPairCount: 1, configKeyCount: 1, applyScopeCount: bad }) });
    assert.equal(malformed.verdict, 'fail');
    assert.ok(malformed.findings.some((f) => f.ruleId === 'config-surface/report-malformed'
      && f.message.includes('applyScopeCount')));
  }
  for (const pin of ['', 'apply_scope -1 2026-09-22\n', 'apply_scope 1.5 2026-09-22\n']) {
    const missingPin = await configOn({ ...files,
      'gates/config-surface/baseline.txt': pins(12, '2026-09-22').replace('apply_scope 2 2026-09-22\n', pin) });
    assert.equal(missingPin.verdict, 'fail');
    assert.ok(missingPin.findings.some((f) => f.ruleId === 'config-surface/baseline-malformed'
      && f.message.includes('apply_scope')));
  }
});

await run('config-surface: pin advanced → pass; pin unchanged → the new rule id', async () => {
  const advanced = await configOn({
    'tmp/matrix.json': MATRIX,
    'gates/config-surface/baseline.txt': pins(12, '2026-09-03'),
    '_baseline/gates/config-surface/baseline.txt': pins(5, '2026-09-01'),
    'gates/config-surface/.changesets/918.md': CHANGESET,
  });
  assert.equal(advanced.verdict, 'pass', ids(advanced).join(', '));

  const stalled = await configOn({
    'tmp/matrix.json': MATRIX,
    'gates/config-surface/baseline.txt': pins(5, '2026-09-01'),
    '_baseline/gates/config-surface/baseline.txt': pins(5, '2026-09-01'),
    'gates/config-surface/.changesets/918.md': CHANGESET,
  });
  assert.equal(stalled.verdict, 'fail');
  assert.ok(ids(stalled).includes('config-surface/declared-growth-without-repin'),
    ids(stalled).join(', '));
  assert.match(stalled.findings[0].message, /Measured 12 application\.yaml keys/);
});

await run('config-surface: a pin raised under a changeset with the live count AT it still passes', async () => {
  // This is the case the rule must NOT break: the author declared the growth AND advanced the pin,
  // so the baseline-shift rule sees a covered raise and the count rule sees no exceedance.
  const r = await configOn({
    'tmp/matrix.json': MATRIX,
    'gates/config-surface/baseline.txt': pins(12, '2026-09-03'),
    '_baseline/gates/config-surface/baseline.txt': pins(5, '2026-09-01'),
    'gates/config-surface/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass');
  assert.ok(!ids(r).some((i) => i.endsWith('silent-baseline-shift')),
    'the changeset is what licenses the pin advance — that half must keep working');
});

// -- dead-code: per-export rows only (no whole-file row ⇒ no TypeScript needed).

const DEAD_GATE = {
  id: 'dead-code',
  baseline: { path: 'gates/dead-code/baseline.txt' },
  changesetsDir: 'gates/dead-code/.changesets',
  config: { reportPath: 'tmp/knip-report.json', projectRoot: 'modules/ui-web' },
};
const DEAD_ROW = 'modules/ui-web/src/api/schema-types/index.ts';
const knipReport = (n) => JSON.stringify({
  issues: [{ file: DEAD_ROW, exports: Array.from({ length: n }, (_, i) => ({ name: `e${i}` })) }],
});

async function deadOn(files) {
  const root = scaffold(files);
  return enforceDeadCode({
    repoRoot: root, gate: DEAD_GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

await run('dead-code: reproduces the #614 shape — changeset, no re-pin → FAIL', async () => {
  const r = await deadOn({
    'tmp/knip-report.json': knipReport(53),
    'gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
    '_baseline/gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
    'gates/dead-code/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'fail');
  assert.ok(ids(r).includes('dead-code/declared-growth-without-repin'), ids(r).join(', '));
  assert.match(r.findings[0].message, /Measured 53 unused exports/);
  assert.match(r.findings[0].message, /#614→#613\/#615/, 'cites the incident it prevents');
});

await run('dead-code: the same PR with the pin advanced → pass', async () => {
  const r = await deadOn({
    'tmp/knip-report.json': knipReport(53),
    'gates/dead-code/baseline.txt': `${DEAD_ROW} 53 2026-09-03\n`,
    '_baseline/gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
    'gates/dead-code/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass', ids(r).join(', '));
});

await run('dead-code: pin advanced WITHOUT a changeset is still a silent baseline shift', async () => {
  const r = await deadOn({
    'tmp/knip-report.json': knipReport(53),
    'gates/dead-code/baseline.txt': `${DEAD_ROW} 53 2026-09-03\n`,
    '_baseline/gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
  });
  assert.equal(r.verdict, 'fail');
  assert.ok(ids(r).includes('dead-code/silent-baseline-shift'), ids(r).join(', '));
});

// --- cleanup + report ----------------------------------------------------------------------

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`declared-growth-repin.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`declared-growth-repin.test: all ${passed} checks passed`);
