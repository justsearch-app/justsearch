/**
 * dead-code enforcer - tempdoc 530 sec 2.9 (input contract: tempdoc 742 D1).
 * Wraps Knip. Reads a Knip JSON report (preferably `--reporter json`) from
 * config.reportPath, counts per-file unused-export entries, ratchets the
 * totals down. Baseline file: `<path> <unused_count> <date>`.
 *
 * Report presence is the RUNNER's contract, not this enforcer's:
 * `tmp/knip-report.json` is declared as a `required` input under the gate's
 * config.inputs, so a missing report fails at the runner (kernel/input-missing)
 * before this enforcer is dispatched - produce it with
 * `npm --prefix modules/ui-web run knip:report`. A malformed report here fails
 * closed (dead-code/report-malformed).
 *
 * Known limit (tempdoc 948, 2026-09-07): `modules/ui-web/knip.config.ts` puts
 * `src/**` including `*.test.ts` in `project`, so a test import counts as a
 * consumer and an export-for-testing symbol is invisible here. Deliberate:
 * `__resetForTest`-style seams are a house pattern, and flagging them would
 * ratchet a backlog nobody asked for. A finding here is therefore genuinely
 * unconsumed by anything, tests included.
 *
 * Whole-file findings are normalized to the module's declared export SURFACE
 * before they touch the ratchet (tempdoc 910 item 1) — top-level bindings plus
 * the enum / namespace / class members knip can report separately, since this
 * function sums every array-valued key on a row. See `export-count.mjs` for
 * the measurements behind it and for the one category it cannot bound.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loadChangesets } from '../../lib/changeset-loader.mjs';
import { readPriorBaselineText } from '../../lib/prior-baseline.mjs';
import { loadTypeScript, normalizeWholeFileCount } from './export-count.mjs';
import { verdictForBaselineShift } from './truth-table.mjs';
import { repinFinding, repinRuleDescription } from '../../lib/declared-growth-repin.mjs';

export const DEAD_CODE_CLASSIFICATIONS = new Set([
  'declared-growth', 'merge-import', 'emergency-override', 'unused-export-shrink',
  // `unit-renormalization` (tempdoc 910): the pinned NUMBER changed because the
  // way a finding is counted changed, not because the tree gained or lost dead
  // code. Deliberately NOT in the growth-covering set below — a counting change
  // must never buy a blanket `silent-growth` suppression for the whole run.
  'unit-renormalization',
]);
export const DEAD_CODE_RULE_DESCRIPTIONS = {
  'dead-code/within-baseline': 'Unused-export count at or below baseline',
  'dead-code/silent-growth': 'A file accumulated new unused exports without a declared changeset',
  'dead-code/declared-growth': 'Dead-code growth; classification covers it',
  'dead-code/rebalance-available': 'Unused-export count shrunk; ratchet can be rebalanced',
  'dead-code/rebalanced': 'Baseline auto-updated',
  'dead-code/report-malformed': 'Knip JSON report could not be parsed (fail-closed)',
  'dead-code/whole-file-uncounted': 'A whole-file finding could not be normalized to its export count (fail-closed)',
  'dead-code/silent-baseline-shift': 'Baseline relaxed in this PR without a declared changeset',
  ...repinRuleDescription('dead-code'),
};

/**
 * Classifications that cover a RAISED PIN in `baseline.txt`.
 *
 * Deliberately a different set from the growth-covering one below. `unit-renormalization` says
 * "the number changed because the way we count changed" — which is exactly a baseline shift and
 * exactly NOT a licence for the live count to exceed its pin. Keeping the two sets apart is what
 * makes that classification load-bearing instead of documentation.
 */
const BASELINE_SHIFT_COVERING = ['declared-growth', 'merge-import', 'emergency-override', 'unit-renormalization'];
const GROWTH_COVERING = ['declared-growth', 'merge-import', 'emergency-override'];

function parseBaseline(text) {
  const m = new Map();
  for (const raw of (text ?? '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2) continue;
    const c = Number(parts[1]);
    if (Number.isFinite(c)) m.set(parts[0], c);
  }
  return m;
}

export async function enforceDeadCode(options) {
  const { repoRoot, gate, baselineRef, rebalance=false, fixtureMode=false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportPath = resolve(sourceRoot, gate.config?.reportPath ?? 'tmp/knip-report.json');
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const baseline = existsSync(baselinePath) ? parseBaseline(readFileSync(baselinePath, 'utf8')) : new Map();

  const findings = [];
  let verdict = 'pass';

  let report;
  try {
    report = JSON.parse(readFileSync(reportPath, 'utf8'));
  } catch {
    findings.push({ ruleId: 'dead-code/report-malformed', level: 'error', message: `malformed Knip JSON at ${reportPath}`, uri: gate.config?.reportPath });
    return { toolName: 'justsearch-dead-code', toolVersion: '0.1.0', findings, verdict: 'fail', ruleDescriptions: DEAD_CODE_RULE_DESCRIPTIONS };
  }

  // One supported shape: `{ issues: [ ... ] }` (knip ^6, verified against
  // node_modules/knip/dist/reporters/json.js - `{ issues: Object.values(json) }`, one row per file
  // with per-category array fields). The tolerant multi-shape walk this comment used to describe
  // was retired with the branches themselves; see the shape check below.
  const counts = new Map();
  const collect = (filePath, n) => counts.set(filePath, (counts.get(filePath) ?? 0) + n);

  // Whole-file normalization (tempdoc 910 item 1). Loaded lazily: a report with
  // no whole-file finding must not need a TypeScript install to be gated.
  const projectRoot = gate.config?.projectRoot ?? null;
  let tsHandle = null;
  const normalizeWholeFile = (reportedPath) => {
    if (!tsHandle) tsHandle = loadTypeScript({ repoRoot: sourceRoot, projectRoot });
    const { count, reason } = normalizeWholeFileCount({
      ts: tsHandle.ts, tsError: tsHandle.error, repoRoot: sourceRoot, projectRoot, reportedPath,
    });
    if (count === null) {
      verdict = 'fail';
      findings.push({
        ruleId: 'dead-code/whole-file-uncounted', level: 'error', uri: reportedPath,
        message: `${reportedPath}: whole-file finding could not be normalized to an export count (${reason}). `
          + 'The ratchet stores one number per path, so a whole-file finding must be expressed in the same '
          + 'unit as a per-export finding; counting it as 1 is what makes a later one-symbol import look like '
          + 'growth. Fix the cause (run `npm ci --prefix modules/ui-web`, or regenerate the report so it '
          + 'matches the working tree) rather than re-pinning the row.',
      });
    }
    return count;
  };

  // ONE supported shape, deliberately (tempdoc 910). This used to also walk a `files[]` top-level
  // array and a legacy `issues{category:{file:[…]}}` object "tolerantly". Neither had a test, and
  // the legacy branch was actively harmful after whole-file normalization landed: `Object.entries`
  // over a legacy `issues.files` ARRAY yields the indices "0","1",… as paths, which the normalizer
  // then fails closed on — a confident, wrong failure for a knip version this repo does not use.
  // `modules/ui-web/package.json:71` pins `knip: ^6.20.0`, whose reporter emits `{issues: [...]}`.
  // An unrecognised shape is now `report-malformed`, which is what a gate should say when it
  // cannot read its input, instead of guessing at a count.
  if (!Array.isArray(report.issues)) {
    findings.push({
      ruleId: 'dead-code/report-malformed',
      level: 'error',
      uri: gate.config?.reportPath,
      message: `${reportPath}: expected a knip JSON report shaped \`{ issues: [ { file, exports, types, files, … } ] }\` `
        + '(knip ^6). Regenerate it with `npm --prefix modules/ui-web run knip:report`. If knip\'s '
        + 'reporter shape has genuinely changed, update this enforcer and its tests together — do '
        + 'not add an untested tolerance branch, which is what this replaced.',
    });
    return { toolName: 'justsearch-dead-code', toolVersion: '0.1.0', findings, verdict: 'fail', ruleDescriptions: DEAD_CODE_RULE_DESCRIPTIONS };
  }

  {
    for (const row of report.issues) {
      const p = row.file ?? row.filePath ?? row.path;
      if (!p) continue;
      // knip marks an entirely-unused module with a `files[]` entry naming
      // itself, instead of listing its exports. Normalize, don't count as 1.
      if (Array.isArray(row.files) && row.files.length > 0) {
        const normalized = normalizeWholeFile(p);
        if (normalized !== null) collect(p, normalized);
        continue;
      }
      let n = 0;
      for (const [key, val] of Object.entries(row)) {
        if (key === 'file' || key === 'filePath' || key === 'path' || key === 'owners') continue;
        if (Array.isArray(val)) n += val.length;
      }
      if (n > 0) collect(p, n);
    }
  }

  const decls = gate.changesetsDir ? loadChangesets({
    repoRoot: sourceRoot, changesetsDir: gate.changesetsDir, baselineRef,
    allowedClassifications: DEAD_CODE_CLASSIFICATIONS, classificationField: 'classification',
    requireJustificationFor: new Set(['declared-growth','merge-import','emergency-override','unit-renormalization']),
    fixtureMode,
  }) : [];
  const growthCovered = decls.some(d => GROWTH_COVERING.includes(d.classification));
  const growthCls = decls.find(d => GROWTH_COVERING.includes(d.classification))?.classification ?? 'declared-growth';

  // Read the prior baseline ONCE, before the count loop: the repin rule (tempdoc 918) needs the
  // pin's pre-PR value to say whether it moved, and the baseline-shift block below needs the same
  // text. null means "no prior state" — a new baseline, or a ref without the file.
  const priorText = readPriorBaselineText({
    fixtureMode, fixtureRoot, sourceRoot, baselineRef, baselinePath: gate.baseline.path,
  });
  const priorBaseline = priorText === null ? null : parseBaseline(priorText);

  const rebalanceWrites = new Map();
  for (const [p, cur] of counts) {
    const pinned = baseline.get(p) ?? 0;
    if (cur > pinned) {
      if (!growthCovered) {
        verdict = 'fail';
        findings.push({ ruleId: 'dead-code/silent-growth', level: 'error', message: `${p}: ${pinned} → ${cur} unused exports without declared changeset`, uri: p });
      } else {
        // Tempdoc 918: the changeset licenses the pin advance, not an unpinned overflow.
        verdict = 'fail';
        findings.push(repinFinding({
          rulePrefix: 'dead-code', classification: growthCls, row: p, measured: cur,
          livePin: pinned, priorPin: priorBaseline?.get(p), baselineFile: gate.baseline.path,
          unit: 'unused exports', pinLine: `${p} ${cur} <today>`,
        }));
      }
    } else if (cur < pinned) {
      findings.push({ ruleId: rebalance ? 'dead-code/rebalanced' : 'dead-code/rebalance-available', level: 'note', message: `${p}: ${cur} < pinned ${pinned}`, uri: p });
      if (rebalance) rebalanceWrites.set(p, cur);
    }
  }

  // Baseline-shift detection (tempdoc 910). Without it the ratchet is only half a ratchet: the
  // live count is held to the pin, but the PIN ITSELF could be edited upward and the gate would
  // report `rebalance-available` — a pass. Mirrors module-deps/enforcer.mjs, the closest
  // sibling (same `<path> <count> <date>` shape, same per-file dynamic key set).
  // null means "no prior state" (a new baseline, or a ref without the file) — skip, never "all grew".
  if (priorBaseline !== null) {
    const covering = decls.find(d => BASELINE_SHIFT_COVERING.includes(d.classification));
    const cls = covering ? covering.classification : 'silent-growth';
    for (const [p, livePin] of baseline.entries()) {
      const priorPin = priorBaseline.get(p);
      if (priorPin === undefined) continue;
      const v = verdictForBaselineShift({ path: p, priorPin, livePin, classification: cls });
      if (v.status === 'fail') {
        verdict = 'fail';
        findings.push({ ruleId: v.ruleId, level: 'error', message: v.reason, uri: gate.baseline.path });
      } else if (v.status === 'info') {
        findings.push({ ruleId: v.ruleId, level: 'note', message: v.reason, uri: gate.baseline.path });
      }
    }
  }

  if (rebalance && rebalanceWrites.size > 0) {
    const date = new Date().toISOString().slice(0,10);
    const out = [`# dead-code ratchet — tempdoc 530 §2.9. <path> <count> <date>`];
    for (const [p, c] of [...baseline.entries()].sort()) {
      const nc = rebalanceWrites.has(p) ? rebalanceWrites.get(p) : c;
      if (nc > 0) out.push(`${p} ${nc} ${date}`);
    }
    writeFileSync(baselinePath, out.join('\n') + '\n');
  }

  return { toolName: 'justsearch-dead-code', toolVersion: '0.1.0', findings, verdict, ruleDescriptions: DEAD_CODE_RULE_DESCRIPTIONS };
}
