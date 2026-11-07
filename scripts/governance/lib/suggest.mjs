/**
 * --suggest-changeset — Layer 3 §3.2 of tempdoc 530.
 *
 * Runs every gate in warn mode against current live state. For each
 * fail-shaped finding, writes a stub changeset under the owning gate's
 * .changesets/ directory with a default classification + tempdoc/adr
 * placeholder. The agent fills in the body.
 *
 * Saves the trial-and-error loop of "run gate → see failure → look up
 * format → write changeset → re-run."
 */

import { existsSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import { isShallowRepository, resolveBaselineRef } from './git-utils.mjs';

const NAMESPACED_FAIL_PATTERNS = [
  /\/silent-/,
  /\/untagged-/,
  /\/exceeded$/,
  /\/hard-cap-exceeded/,
  /\/unresolved/,
  /\/missing-/,
  /\/orphan-/,
  /\/silent-baseline-shift/,
  /\/silent-pin-bump/,
];

function defaultClassificationForGate(gateId) {
  // Pragmatic defaults based on each gate's classifications.mjs.
  switch (gateId) {
    case 'npm-audit':
      return 'declared-regression';
    case 'test-efficacy':
      return 'strength-regression';
    default:
      return 'declared-growth';
  }
}

export async function suggestChangesets({ gates, repoRoot }) {
  if (isShallowRepository(repoRoot)) {
    console.error('shallow clone detected; --suggest-changeset needs full history.');
    process.exit(2);
  }

  const stubsWritten = [];
  const skipped = [];

  for (const gate of gates) {
    // Load enforcer.
    const enforcerPath = resolve(repoRoot, gate.enforcer);
    if (!existsSync(enforcerPath)) {
      skipped.push({ gate: gate.id, reason: 'enforcer not found' });
      continue;
    }
    const mod = await import(pathToFileURL(enforcerPath).href);
    const enforceFn = mod.default ?? mod.enforce ?? Object.values(mod).find(v => typeof v === 'function' && v.name.startsWith('enforce'));
    if (!enforceFn) {
      skipped.push({ gate: gate.id, reason: 'no exported enforcer' });
      continue;
    }

    let baselineRef = null;
    if (gate.baseline?.kind === 'git' || (gate.baseline?.kind === 'ratchet-file' && gate.baseline?.diffStrategy === 'git-base')) {
      try {
        const spec = gate.baseline.kind === 'git'
          ? gate.baseline
          : { strategy: gate.baseline.diffStrategy, fallback: gate.baseline.diffFallback };
        baselineRef = resolveBaselineRef(spec, repoRoot).ref;
      } catch { baselineRef = null; }
    }

    let result;
    try {
      result = await enforceFn({ repoRoot, gate, baselineRef, mode: 'warn', rebalance: false, fixtureMode: false });
    } catch (err) {
      skipped.push({ gate: gate.id, reason: `enforce threw: ${err.message}` });
      continue;
    }

    const fails = (result.findings ?? []).filter(f => f.level === 'error' && NAMESPACED_FAIL_PATTERNS.some(re => re.test(f.ruleId)));
    if (fails.length === 0) continue;

    // Write one stub changeset per gate (not per finding) — the body explains all findings.
    const stubName = `auto-suggest-${Date.now()}.md`;
    const changesetsDir = gate.changesetsDir
      ? resolve(repoRoot, gate.changesetsDir)
      : null;
    if (!changesetsDir || !existsSync(changesetsDir)) {
      skipped.push({ gate: gate.id, reason: 'no changesets directory' });
      continue;
    }
    const stubPath = resolve(changesetsDir, stubName);
    if (existsSync(stubPath)) continue; // don't overwrite

    const classification = defaultClassificationForGate(gate.id);
    const findingLines = fails.map(f => `  - ${f.ruleId}: ${f.message.slice(0, 120)}`);
    const body = [
      `---`,
      `classification: ${classification}`,
      `tempdoc: TODO  # replace with tempdoc number, or change to 'adr: NNNN'`,
      `---`,
      ``,
      `Auto-generated stub for ${fails.length} ${gate.id} fail-shaped finding(s).`,
      `Fill in the rationale before merging. Findings:`,
      ``,
      ...findingLines,
      ``,
      `Justify why these are acceptable, or address them and delete this changeset.`,
      ``,
    ].join('\n');

    writeFileSync(stubPath, body);
    stubsWritten.push({ gate: gate.id, path: stubPath, findingCount: fails.length });
  }

  if (stubsWritten.length === 0) {
    console.log('No fail-shaped findings detected; no stubs written.');
  } else {
    console.log(`Wrote ${stubsWritten.length} stub changeset${stubsWritten.length === 1 ? '' : 's'}:`);
    for (const s of stubsWritten) {
      console.log(`  ${s.gate}: ${s.path} (${s.findingCount} finding${s.findingCount === 1 ? '' : 's'})`);
    }
    console.log('');
    console.log('Edit each stub to fill in the tempdoc/adr reference + rationale, then re-run the gate.');
  }
  if (skipped.length > 0) {
    console.log(`Skipped ${skipped.length} gate${skipped.length === 1 ? '' : 's'}: ${skipped.map(s => `${s.gate} (${s.reason})`).join(', ')}`);
  }
}
