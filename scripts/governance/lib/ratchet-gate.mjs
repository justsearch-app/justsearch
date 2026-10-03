/**
 * ratchet-gate — a generic 530-kernel enforcer factory for the presentation
 * per-file ratchets (tempdoc 574 §25 Phase 4, Edge 4).
 *
 * The DETECTION logic stays in the standalone `scripts/ci/check-*.mjs` (one
 * authority — the CLI and this kernel enforcer call the SAME `detect()`); this
 * factory only adapts a `{ failures: [{ file, message }] }` result into the
 * kernel's `{ findings, verdict, ruleDescriptions }` contract and wires the
 * shared changeset-justification + rebalance machinery (the run-record /
 * SARIF / declared-growth gains the standalone scripts lacked).
 *
 * A ratchet "regression" (a file exceeding its baseline) is a FAIL unless a
 * declared changeset (`declared-growth` / `merge-import` / `emergency-override`)
 * covers it — the per-file-ratchet template (530 §2.5).
 *
 * Tempdoc 930 chunk F left `atom-fork-ratchet` as the factory's ONE client
 * (`style-literal-ratchet` was retired to the standalone
 * `scripts/ci/check-style-literal-ratchet.mjs`, which the ui-web gate set runs
 * directly). The factory stays because it is where the changeset / rebalance /
 * repin machinery lives, but nothing here may assume a second client's shape.
 */
import { resolve, join } from 'node:path';

import { loadChangesets } from './changeset-loader.mjs';
import { readPriorBaselineText } from './prior-baseline.mjs';
import { repinFinding, repinRuleDescription } from './declared-growth-repin.mjs';

const COVERING = ['declared-growth', 'merge-import', 'emergency-override'];

export function makeRatchetGate({
  detect,
  rebalanceBaseline,
  srcSubdir,
  toolName,
  rulePrefix,
  ruleDescriptions,
  classifications,
  measuredUnit = 'occurrences',
}) {
  return async function enforce(options) {
    const {
      repoRoot,
      gate,
      baselineRef,
      rebalance = false,
      fixtureMode = false,
      fixtureRoot,
    } = options;
    const root = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
    const srcRoot = join(root, srcSubdir).replaceAll('\\', '/');
    const baselinePath = resolve(root, gate.baseline?.path ?? '');

    if (rebalance && !fixtureMode) {
      rebalanceBaseline({ srcRoot, baselinePath });
    }

    const { failures, baseline } = detect({ srcRoot, baselinePath });

    const decls = gate.changesetsDir
      ? loadChangesets({
          repoRoot: root,
          changesetsDir: gate.changesetsDir,
          baselineRef,
          allowedClassifications: classifications,
          classificationField: 'classification',
          requireJustificationFor: new Set(COVERING),
          fixtureMode,
        })
      : [];
    const growthCovered = decls.some((d) => COVERING.includes(d.classification));
    const coveringCls =
      decls.find((d) => COVERING.includes(d.classification))?.classification ??
      'declared-growth';

    const findings = [];
    let verdict = 'pass';
    const priorText = readPriorBaselineText({
      fixtureMode, fixtureRoot, sourceRoot: root, baselineRef,
      baselinePath: gate.baseline.path,
    });
    if (priorText !== null && !growthCovered) {
      const prior = JSON.parse(priorText);
      for (const [file, livePin] of Object.entries(baseline)) {
        const priorPin = prior[file] ?? 0;
        if (livePin <= priorPin) continue;
        verdict = 'fail';
        findings.push({
          ruleId: `${rulePrefix}/silent-growth`,
          level: 'error',
          message: `${file}: baseline pin increased from ${priorPin} to ${livePin} without a declared growth changeset`,
          uri: gate.baseline.path,
        });
      }
    }
    for (const f of failures) {
      if (growthCovered) {
        // Tempdoc 918: the changeset licenses the pin advance, not an unpinned overflow. `detect()`
        // compares each file against the LIVE baseline, so a file still in `failures` is by
        // definition one whose pin was not advanced to its measured value in this diff.
        //
        // `count` and `base` are threaded through so the finding states the numbers. Without them
        // the shared message degrades to "the baseline does not carry this row", which for this
        // gate is usually FALSE — the row exists, it is the number that is stale. `detect()` does
        // not oblige a client to supply them, so the degraded wording is still reachable; that
        // path is covered by declared-growth-repin.test.mjs.
        const baselineFile = gate.baseline?.path ?? '(gate baseline)';
        verdict = 'fail';
        findings.push(repinFinding({
          rulePrefix,
          classification: coveringCls,
          row: f.file,
          measured: f.count,
          livePin: f.base,
          baselineFile,
          unit: measuredUnit,
          pinLine: `"${f.file}": ${f.count} in ${baselineFile}`,
          uri: f.file,
        }));
      } else {
        verdict = 'fail';
        findings.push({
          ruleId: `${rulePrefix}/silent-growth`,
          level: 'error',
          message: f.message,
          uri: f.file,
        });
      }
    }

    return {
      toolName,
      toolVersion: '0.1.0',
      findings,
      verdict,
      ruleDescriptions: { ...ruleDescriptions, ...repinRuleDescription(rulePrefix) },
    };
  };
}
