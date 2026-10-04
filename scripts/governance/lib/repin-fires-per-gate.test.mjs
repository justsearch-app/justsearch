/**
 * Tempdoc 918 item 1 — the re-pin rule FIRES in every gate that wired it.
 *
 * `repin-coverage.test.mjs` asserts each growth-licensing gate REFERENCES the rule module. That is
 * a static check, and a static check is a hypothesis: an enforcer can import the module and still
 * call it on a branch that never runs (`wrong-gate`). This file is the runtime half — it drives the
 * enforcers, each with a covering changeset AND a live exceedance, and asserts each returns
 * `verdict: 'fail'` carrying its own `…-without-repin` rule id.
 *
 * Six gates wire the rule, across seven call sites. Six of the seven are driven here:
 * `module-deps`, `config-surface`, `npm-audit`, `atom-fork-ratchet` (through the shared
 * `makeRatchetGate` factory) and BOTH of `test-efficacy`'s — its strength FLOOR and its no-coverage
 * CEILING, which are separate branches reached by different metrics. The seventh (`dead-code`) is
 * driven by `declared-growth-repin.test.mjs`, which also covers the four-case truth table per gate.
 * Together: 7 of 7 branches exercised.
 *
 * `npm-audit` and `test-efficacy` are the FLOOR-shaped gates — the direction where the pin must be
 * LOWERED to accommodate the measured value — so their cases are load-bearing for the
 * `declared-regression` half of the rule, not redundant with the ceiling gates.
 *
 * The file also asserts `--explain` answers for all six rule ids: these gates merge the repin
 * description into their enforcer's RETURN VALUE rather than the registry-declared descriptions
 * module, and `--explain` silently could not see those (tempdoc 918 review B3).
 *
 * Run with: `node scripts/governance/lib/repin-fires-per-gate.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { enforceModuleDeps } from '../gates/module-deps/enforcer.mjs';
import { enforceConfigSurface } from '../gates/config-surface/enforcer.mjs';
import { enforceNpmAudit } from '../gates/npm-audit/enforcer.mjs';
import { enforceTestEfficacy } from '../gates/test-efficacy/enforcer.mjs';
import { enforceAtomForkRatchet } from '../gates/atom-fork-ratchet/enforcer.mjs';
import { REQUIRED_ADVISORY_TARGETS, sha256 } from '../../ci/lib/github-advisory-report.mjs';
import { explainRule } from './explain.mjs';

const tmpDirs = [];
let passed = 0;
const failures = [];

function scaffold(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'repin-fires-'));
  tmpDirs.push(root);
  for (const [rel, content] of Object.entries(files)) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }
  return root;
}

const changeset = (cls) =>
  `---\nclassification: ${cls}\ntempdoc: 918\nowner: repin-fires-per-gate.test\nreason: fixture\n---\nbody\n`;
const call = (fn, root, gate) =>
  fn({ repoRoot: root, gate, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root });

/**
 * Each case must fail AND name its own repin rule. Asserting the id — not merely `verdict: 'fail'`
 * — is what distinguishes "passes for the right reason" from a fixture that happens to trip some
 * other rule (three of these did exactly that while this file was being written: two schema
 * mismatches and one report shape, each of which was a `fail` that proved nothing).
 */
async function firesFor(label, expectedRuleId, build, expectMessage) {
  try {
    const r = await build();
    const ids = r.findings.map((f) => f.ruleId);
    assert.ok(ids.includes(expectedRuleId),
      `expected ${expectedRuleId}, got ${JSON.stringify([...new Set(ids)])}`);
    assert.equal(r.verdict, 'fail', 'the repin finding must flip the verdict, not just be reported');
    const finding = r.findings.find((f) => f.ruleId === expectedRuleId);
    assert.equal(finding.level, 'error');
    // The message must state the NUMBERS. A gate that omits `measured`/`livePin` degrades to the
    // shared "the baseline does not carry this row" sentence, which for a row that DOES exist is
    // simply false (tempdoc 918 review S1). Every gate still wiring the rule holds a numeric pin
    // per row, so this holds unconditionally.
    assert.ok(!finding.message.includes('does not carry this row'),
      `message fell back to the row-absent wording: ${finding.message}`);
    if (expectMessage) assert.match(finding.message, expectMessage);
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

await firesFor('module-deps', 'module-deps/declared-growth-without-repin', () => {
  const root = scaffold({
    'tmp/module-deps.json': JSON.stringify({ modules: [{ name: 'core', productionDeps: ['a', 'b', 'c'] }] }),
    'gates/module-deps/baseline.txt': 'core 1 2026-09-01\n',
    '_baseline/gates/module-deps/baseline.txt': 'core 1 2026-09-01\n',
    'gates/module-deps/.changesets/f.md': changeset('declared-growth'),
  });
  return call(enforceModuleDeps, root, {
    id: 'module-deps',
    baseline: { path: 'gates/module-deps/baseline.txt' },
    changesetsDir: 'gates/module-deps/.changesets',
    config: { reportPath: 'tmp/module-deps.json' },
  });
});

await firesFor('config-surface', 'config-surface/declared-growth-without-repin', () => {
  const pins = 'yaml_keys 10 2026-09-01\nenv_sysprop_pairs 1 2026-09-01\nconfig_keys 1 2026-09-01\napply_scope 2 2026-09-01\n';
  const root = scaffold({
    'tmp/matrix.json': JSON.stringify({ yamlKeyCount: 999, envSyspropPairCount: 1, configKeyCount: 1, applyScopeCount: 2 }),
    'gates/config-surface/baseline.txt': pins,
    '_baseline/gates/config-surface/baseline.txt': pins,
    'gates/config-surface/.changesets/f.md': changeset('declared-growth'),
  });
  return call(enforceConfigSurface, root, {
    id: 'config-surface',
    baseline: { path: 'gates/config-surface/baseline.txt' },
    changesetsDir: 'gates/config-surface/.changesets',
    config: { reportPath: 'tmp/matrix.json' },
  });
});

await firesFor('npm-audit (a FLOOR)', 'npm-audit/declared-regression-without-repin', () => {
  const lockfiles = Object.fromEntries(REQUIRED_ADVISORY_TARGETS.map((target) => [
    target.lockfile,
    target.targetId === 'root'
      ? '{"lockfileVersion":3,"packages":{"node_modules/example":{"version":"1.0.0"}}}\n'
      : target.targetId === 'ui-web'
        ? '{"lockfileVersion":3,"packages":{"node_modules/ui-example":{"version":"1.0.0"}}}\n'
        : '{"lockfileVersion":3,"packages":{"node_modules/example":{"version":"1.0.0"}}}\n',
  ]));
  const baseline = JSON.stringify({
    schema: 'github-advisory-baseline.v1',
    targets: Object.fromEntries(REQUIRED_ADVISORY_TARGETS.map((target) => [target.targetId, { advisories: [] }])),
  });
  const root = scaffold({
    'tmp/github-advisories.json': JSON.stringify({
      schema: 'github-advisory-report.v1',
      source: { provider: 'github-global-security-advisories', api_version: '2026-03-10' },
      targets: REQUIRED_ADVISORY_TARGETS.map((target) => ({
        target_id: target.targetId,
        lockfile: target.lockfile,
        available: true,
        lockfile_sha256: sha256(lockfiles[target.lockfile]),
        package_versions: 1,
        advisories: target.targetId === 'root'
          ? [{ ghsa_id: 'GHSA-35JH-R3H4-6JHM', severity: 'high', html_url: 'https://github.com/advisories/GHSA-35jh-r3h4-6jhm' }]
          : [],
      })),
    }),
    'scripts/ci/github-advisory-baseline.v1.json': baseline,
    '_baseline/scripts/ci/github-advisory-baseline.v1.json': baseline,
    ...lockfiles,
    'gates/npm-audit/.changesets/f.md': changeset('declared-regression'),
  });
  return call(enforceNpmAudit, root, {
    id: 'npm-audit',
    baseline: { path: 'scripts/ci/github-advisory-baseline.v1.json' },
    changesetsDir: 'gates/npm-audit/.changesets',
    config: { reportPath: 'tmp/github-advisories.json', trackedSeverities: ['high', 'critical'] },
  });
});

await firesFor('test-efficacy strength (a FLOOR, not a ceiling)',
  'test-efficacy/declared-regression-without-repin', () => {
    const root = scaffold({
      'tmp/pit.json': JSON.stringify({ schema: 'pit-strength-report.v1', seams: { s1: { strength: 40, noCoverage: 0 } } }),
      'gates/test-efficacy/strength-baseline.v1.json': JSON.stringify({
        schema: 'pit-strength-baseline.v1', seams: { s1: { minStrength: 90, maxNoCoverage: 5 } },
      }),
      'governance/logic-seams.v1.json': JSON.stringify({ seams: [{ id: 's1' }] }),
      'gates/test-efficacy/.changesets/f.md': changeset('strength-regression'),
    });
    return call(enforceTestEfficacy, root, {
      id: 'test-efficacy',
      baseline: { path: 'gates/test-efficacy/strength-baseline.v1.json' },
      changesetsDir: 'gates/test-efficacy/.changesets',
      config: { reportPath: 'tmp/pit.json', registerPath: 'governance/logic-seams.v1.json' },
    });
  }, /Measured 40 test-strength; the pin in gates\/test-efficacy\/strength-baseline\.v1\.json is 90/);

await firesFor('test-efficacy no-coverage (the SECOND call site, a ceiling on the same gate)',
  'test-efficacy/declared-regression-without-repin', () => {
    // Strength is ABOVE its floor here, so only the coverage-erosion branch can produce the
    // finding — which is what makes this case distinguish the two call sites rather than re-run one.
    const root = scaffold({
      'tmp/pit.json': JSON.stringify({ schema: 'pit-strength-report.v1', seams: { s1: { strength: 95, noCoverage: 9 } } }),
      'gates/test-efficacy/strength-baseline.v1.json': JSON.stringify({
        schema: 'pit-strength-baseline.v1', seams: { s1: { minStrength: 90, maxNoCoverage: 5 } },
      }),
      'governance/logic-seams.v1.json': JSON.stringify({ seams: [{ id: 's1' }] }),
      'gates/test-efficacy/.changesets/f.md': changeset('strength-regression'),
    });
    return call(enforceTestEfficacy, root, {
      id: 'test-efficacy',
      baseline: { path: 'gates/test-efficacy/strength-baseline.v1.json' },
      changesetsDir: 'gates/test-efficacy/.changesets',
      config: { reportPath: 'tmp/pit.json', registerPath: 'governance/logic-seams.v1.json' },
    });
  }, /Measured 9 uncovered mutations; the pin in gates\/test-efficacy\/strength-baseline\.v1\.json is 5/);

await firesFor('atom-fork-ratchet (the shared makeRatchetGate factory)',
  'atom-fork-ratchet/declared-growth-without-repin', () => {
    const root = scaffold({
      // `.badge {` is a fork class projected from governance/atom-facets.v1.json; the file must not
      // be an @atom authority, so no `@atom` marker.
      'modules/ui-web/src/shell-v0/sub/probe.ts':
        'export const css = `.badge { color: red; }`;\n',
      'scripts/ci/atom-fork-ratchet-baseline.v1.json': JSON.stringify({}),
      'gates/atom-fork-ratchet/.changesets/f.md': changeset('declared-growth'),
    });
    return call(enforceAtomForkRatchet, root, {
      id: 'atom-fork-ratchet',
      baseline: { path: 'scripts/ci/atom-fork-ratchet-baseline.v1.json' },
      changesetsDir: 'gates/atom-fork-ratchet/.changesets',
    });
  }, /Measured 1 raw atom-class rules; the pin in scripts\/ci\/atom-fork-ratchet-baseline\.v1\.json is 0/);

// --- `--explain` answers for every one of the six ids -------------------------------------------

const ALL_REPIN_RULE_IDS = [
  'dead-code/declared-growth-without-repin',
  'config-surface/declared-growth-without-repin',
  'module-deps/declared-growth-without-repin',
  'atom-fork-ratchet/declared-growth-without-repin',
  'npm-audit/declared-regression-without-repin',
  'test-efficacy/declared-regression-without-repin',
];

{
  const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
  const registry = JSON.parse(
    fs.readFileSync(path.join(REPO_ROOT, 'governance/registry.v1.json'), 'utf8'));
  const capture = async (ruleId) => {
    const lines = [];
    const orig = console.log;
    console.log = (...a) => lines.push(a.join(' '));
    try { await explainRule({ ruleId, gates: registry.gates, repoRoot: REPO_ROOT }); }
    finally { console.log = orig; }
    return lines.join('\n');
  };
  for (const ruleId of ALL_REPIN_RULE_IDS) {
    try {
      const out = await capture(ruleId);
      assert.ok(!out.includes('No description registered'), `--explain ${ruleId} has no description`);
      assert.ok(out.includes(`Rule: ${ruleId}`), `--explain ${ruleId} did not name the rule`);
      assert.ok(out.includes('Remedy: advance the baseline pin'),
        `--explain ${ruleId} must give the re-pin remedy, not the changeset template`);
      passed += 1;
    } catch (e) {
      failures.push(`--explain ${ruleId}: ${e.message}`);
    }
  }
  // The rule must NOT be invented for a gate that is exempt from it. `consumer-drift` is a
  // registered, growth-licensing, EXEMPT gate (see repin-coverage.test.mjs) — its vocabulary
  // licenses a change to the slot register itself, so it has no pin to advance.
  try {
    const out = await capture('consumer-drift/declared-growth-without-repin');
    assert.ok(out.includes('No description registered'),
      'an exempt gate must not get a fabricated repin rule');
    passed += 1;
  } catch (e) {
    failures.push(`--explain exempt-gate negative: ${e.message}`);
  }
}

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`repin-fires-per-gate.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`repin-fires-per-gate.test: all ${passed} checks passed (6 call sites fire, 7 --explain)`);
