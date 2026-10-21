/**
 * Historical `npm-audit` gate id, backed by GitHub Global Security Advisories.
 * The stable gate id preserves changeset and SARIF routing; the evidence and
 * baseline are identity-based so advisory swaps cannot cancel through counts.
 */

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { loadChangesets } from '../../lib/changeset-loader.mjs';
import {
  repinFinding,
  repinRuleDescription,
  REPIN_REGRESSION_RULE_SUFFIX,
} from '../../lib/declared-growth-repin.mjs';
import { readPriorBaselineText } from '../../lib/prior-baseline.mjs';
import {
  GITHUB_ADVISORY_PROVIDER,
  GITHUB_ADVISORY_REPORT_SCHEMA,
  GITHUB_API_VERSION,
  REQUIRED_ADVISORY_TARGETS,
  sha256,
  packageSpecsFromLockfileText,
  unavailableAdvisoryTargetReason,
} from '../../../ci/lib/github-advisory-report.mjs';
import { NPM_AUDIT_CLASSIFICATIONS, aggregateNpmAuditClassifications } from './classifications.mjs';
import { NPM_AUDIT_RULE_DESCRIPTIONS } from './rule-descriptions.mjs';

const BASELINE_SCHEMA = 'github-advisory-baseline.v1';
const SEVERITY_RANK = new Map([['unknown', 0], ['low', 1], ['moderate', 2], ['high', 3], ['critical', 4]]);
const RULE_DESCRIPTIONS = {
  ...NPM_AUDIT_RULE_DESCRIPTIONS,
  ...repinRuleDescription('npm-audit', REPIN_REGRESSION_RULE_SUFFIX),
};

export async function enforceNpmAudit(options) {
  const { repoRoot, gate, baselineRef, rebalance = false, fixtureMode = false, fixtureRoot } = options;
  const sourceRoot = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
  const reportRel = gate.config?.reportPath ?? 'tmp/github-advisory-report.json';
  const reportPath = resolve(sourceRoot, reportRel);
  const baselinePath = resolve(sourceRoot, gate.baseline.path);
  const trackedSeverities = new Set(gate.config?.trackedSeverities ?? ['high', 'critical']);

  if (!existsSync(baselinePath)) {
    return result('fail', [{
      ruleId: 'npm-audit/baseline-missing', level: 'error',
      message: `Advisory baseline not found at ${baselinePath}.`, uri: gate.baseline.path,
    }]);
  }

  let report;
  let baseline;
  try {
    report = JSON.parse(readFileSync(reportPath, 'utf8'));
    baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
  } catch (error) {
    return result('fail', [{
      ruleId: 'npm-audit/schema-mismatch', level: 'error',
      message: `Unable to parse advisory evidence: ${error instanceof Error ? error.message : String(error)}`,
      uri: reportRel,
    }]);
  }
  if (report?.schema !== GITHUB_ADVISORY_REPORT_SCHEMA ||
      report?.source?.provider !== GITHUB_ADVISORY_PROVIDER ||
      report?.source?.api_version !== GITHUB_API_VERSION) {
    return result('fail', [{
      ruleId: 'npm-audit/schema-mismatch', level: 'error',
      message: `Expected ${GITHUB_ADVISORY_REPORT_SCHEMA} evidence from ${GITHUB_ADVISORY_PROVIDER} API ${GITHUB_API_VERSION}.`, uri: reportRel,
    }]);
  }
  if (baseline?.schema !== BASELINE_SCHEMA) {
    return result('fail', [{
      ruleId: 'npm-audit/schema-mismatch', level: 'error',
      message: `Expected ${BASELINE_SCHEMA} baseline.`, uri: gate.baseline.path,
    }]);
  }

  const requiredTargets = REQUIRED_ADVISORY_TARGETS.map((target) => target.targetId);
  const availabilityFindings = validateAdvisoryReportAvailability(
    report, REQUIRED_ADVISORY_TARGETS, reportRel, sourceRoot,
  );
  if (availabilityFindings.length > 0) return result('fail', availabilityFindings);

  let current;
  let liveBaseline;
  try {
    current = normalizeReport(report);
    liveBaseline = normalizeBaseline(baseline, trackedSeverities);
    assertExactTargetSet(liveBaseline, requiredTargets, 'baseline');
  } catch (error) {
    return result('fail', [{
      ruleId: 'npm-audit/schema-mismatch', level: 'error',
      message: error instanceof Error ? error.message : String(error), uri: gate.baseline.path,
    }]);
  }

  const prMode = !fixtureMode && (options.eventName ?? process.env.GITHUB_EVENT_NAME) === 'pull_request';
  const baseSpecs = new Map();
  if (prMode) {
    try {
      const base = options.prBaseSha ?? process.env.PR_BASE_SHA;
      if (!/^[a-f0-9]{40}$/.test(base ?? '')) throw new Error('PR base commit is missing or invalid');
      for (const target of REQUIRED_ADVISORY_TARGETS) {
        let text;
        try {
          text = execFileSync('git', ['show', `${base}:${target.lockfile}`], { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
        } catch (error) {
          throw new Error(`${target.lockfile} at PR base ${base} cannot be read: ${String(error.stderr || error.message).trim()}`);
        }
        baseSpecs.set(target.targetId, new Set(packageSpecsFromLockfileText(text)));
      }
    } catch (error) {
      return result('fail', [{ ruleId: 'npm-audit/base-unavailable', level: 'error', uri: reportRel,
        message: `PR advisory comparison failed: ${error.message}` }]);
    }
  }

  const declarations = gate.changesetsDir
    ? loadChangesets({
        repoRoot: sourceRoot,
        changesetsDir: gate.changesetsDir,
        baselineRef,
        allowedClassifications: NPM_AUDIT_CLASSIFICATIONS,
        classificationField: 'classification',
        requireJustificationFor: new Set(['declared-regression', 'lockfile-import', 'emergency-override']),
        fixtureMode,
      })
    : [];
  const aggregated = aggregateNpmAuditClassifications(declarations);
  const classification = classifyRegression(aggregated);
  const findings = [];
  let verdict = 'pass';
  let hasRegression = false;
  let hasBaselineExpansion = false;

  for (const target of requiredTargets.sort()) {
    const currentMap = current.get(target) ?? new Map();
    const baselineMap = liveBaseline.get(target) ?? new Map();
    for (const [id, advisory] of currentMap) {
      if (!trackedSeverities.has(advisory.severity)) continue;
      if (prMode && advisory.affected.every((entry) => baseSpecs.get(target).has(`${entry.package}@${entry.version}`))) continue;
      const accepted = baselineMap.get(id);
      if (!prMode && accepted && severityRank(accepted.severity) >= severityRank(advisory.severity)) continue;
      hasRegression = true;
      verdict = 'fail';
      if (classification === 'silent-regression') {
        findings.push({
          ruleId: 'npm-audit/silent-regression', level: 'error', uri: gate.baseline.path,
          message: accepted
            ? `${target} ${id} severity increased ${accepted.severity} → ${advisory.severity} without a declared changeset.`
            : `${target} has new ${advisory.severity} advisory ${id} without a declared changeset.`,
        });
      } else {
        findings.push({
          ...repinFinding({
            rulePrefix: 'npm-audit', classification, row: `${target}/${id}`,
            measured: severityRank(advisory.severity), livePin: accepted ? severityRank(accepted.severity) : 0,
            baselineFile: gate.baseline.path, unit: 'severity rank', suffix: REPIN_REGRESSION_RULE_SUFFIX,
            pinLine: `${id} = ${advisory.severity}`, uri: gate.baseline.path,
          }),
          message: `${target} ${id} is classified as '${classification}' but the baseline does not accept ${advisory.severity}; repin that identity in the same change.`,
        });
      }
    }
    for (const [id, accepted] of baselineMap) {
      const advisory = currentMap.get(id);
      if (!advisory || !trackedSeverities.has(advisory.severity) || severityRank(advisory.severity) < severityRank(accepted.severity)) {
        findings.push({
          ruleId: rebalance ? 'npm-audit/rebalanced' : 'npm-audit/rebalance-available', level: 'note', uri: gate.baseline.path,
          message: advisory
            ? `${target} ${id} improved ${accepted.severity} → ${advisory.severity}.`
            : `${target} ${id} is no longer a tracked advisory.`,
        });
      }
    }
  }

  const prior = readPriorBaseline({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselinePath: gate.baseline.path });
  if (prior || liveBaseline.size > 0) {
    let priorTargets = new Map();
    try {
      if (prior) priorTargets = normalizeBaseline(prior, trackedSeverities);
    } catch {
      priorTargets = new Map();
    }
    for (const [target, liveMap] of liveBaseline) {
      const priorMap = priorTargets.get(target) ?? new Map();
      const currentMap = current.get(target) ?? new Map();
      for (const [id, accepted] of liveMap) {
        const old = priorMap.get(id);
        if (old && severityRank(old.severity) >= severityRank(accepted.severity)) continue;
        hasBaselineExpansion = true;
        const observed = currentMap.get(id);
        if (!observed || !trackedSeverities.has(observed.severity) || severityRank(accepted.severity) > severityRank(observed.severity)) {
          verdict = 'fail';
          findings.push({
            ruleId: 'npm-audit/baseline-entry-not-current', level: 'error', uri: gate.baseline.path,
            message: `${target} baseline adds ${id}@${accepted.severity}, but current evidence does not contain that tracked advisory at the accepted severity.`,
          });
        } else if (classification === 'silent-regression') {
          verdict = 'fail';
          findings.push({
            ruleId: 'npm-audit/silent-baseline-shift', level: 'error', uri: gate.baseline.path,
            message: `${target} baseline added or raised ${id}@${accepted.severity} without a declared changeset.`,
          });
        } else {
          findings.push({
            ruleId: 'npm-audit/declared-baseline-shift', level: 'note', uri: gate.baseline.path,
            message: `${target} baseline accepts ${id}@${accepted.severity}; classification '${classification}' covers it.`,
          });
        }
      }
    }
  }

  if (declarations.length > 0 && !hasRegression && !hasBaselineExpansion &&
      !aggregated.classifications.every((entry) => entry === 'severity-decrease')) {
    findings.push({
      ruleId: 'npm-audit/changeset-mismatch', level: 'warning',
      message: `${declarations.length} npm-audit changeset(s) declared but no advisory regression or baseline expansion was observed.`,
    });
  }

  let rebalanceWrites = [];
  if (rebalance) {
    const next = buildRebalancedBaseline(liveBaseline, current, requiredTargets, trackedSeverities, reportRel);
    if (JSON.stringify(next.targets) !== JSON.stringify(baseline.targets ?? {})) {
      writeFileSync(baselinePath, `${JSON.stringify(next, null, 2)}\n`, 'utf8');
      rebalanceWrites = [{ file: gate.baseline.path, before: '', after: '' }];
    }
  }
  return result(verdict, findings, rebalanceWrites);
}

export function validateAdvisoryReportAvailability(
  report,
  expectedTargets = REQUIRED_ADVISORY_TARGETS,
  reportPath = 'tmp/github-advisory-report.json',
  sourceRoot = null,
) {
  const rows = new Map();
  for (const row of Array.isArray(report?.targets) ? report.targets : []) {
    const id = String(row?.target_id ?? '').trim();
    if (!id) continue;
    if (rows.has(id)) {
      return [{
        ruleId: 'npm-audit/report-unavailable', level: 'error', uri: reportPath,
        message: `${id} advisory evidence is unavailable: duplicate target row`,
      }];
    }
    rows.set(id, row);
  }
  const findings = [];
  const expectedIds = new Set(expectedTargets.map((target) => target.targetId));
  for (const id of rows.keys()) {
    if (!expectedIds.has(id)) findings.push({
      ruleId: 'npm-audit/report-unavailable', level: 'error', uri: reportPath,
      message: `${id} advisory evidence is unavailable: unexpected target row`,
    });
  }
  for (const target of expectedTargets) {
    const row = rows.get(target.targetId);
    let detail = unavailableAdvisoryTargetReason(row);
    if (detail === null && row.lockfile !== target.lockfile) {
      detail = `lockfile path '${row.lockfile}' does not match '${target.lockfile}'`;
    }
    if (detail === null && sourceRoot) {
      try {
        const actualDigest = sha256(readFileSync(resolve(sourceRoot, target.lockfile), 'utf8'));
        if (row.lockfile_sha256 !== actualDigest) detail = 'lockfile digest does not match the current checkout';
      } catch (error) {
        detail = `current lockfile cannot be read: ${error instanceof Error ? error.message : String(error)}`;
      }
    }
    if (detail === null) continue;
    findings.push({
      ruleId: 'npm-audit/report-unavailable', level: 'error', uri: reportPath,
      message: `${target.targetId} advisory evidence is unavailable: ${detail}`,
    });
  }
  return findings;
}

function normalizeReport(report) {
  const targets = new Map();
  for (const row of report.targets) {
    const advisories = new Map();
    for (const advisory of row.advisories) advisories.set(advisory.ghsa_id, advisory);
    targets.set(row.target_id, advisories);
  }
  return targets;
}

function normalizeBaseline(baseline, trackedSeverities) {
  const targets = new Map();
  for (const [target, row] of Object.entries(baseline.targets ?? {})) {
    if (!row || !Array.isArray(row.advisories)) throw new Error(`Baseline target '${target}' must contain an advisories array.`);
    const advisories = new Map();
    for (const advisory of row.advisories) {
      const id = String(advisory?.ghsa_id ?? '').toUpperCase();
      const severity = String(advisory?.severity ?? '').toLowerCase();
      if (!/^GHSA-[23456789CFGHJMPQRVWX]{4}-[23456789CFGHJMPQRVWX]{4}-[23456789CFGHJMPQRVWX]{4}$/.test(id) ||
          !trackedSeverities.has(severity)) {
        throw new Error(`Baseline target '${target}' contains an invalid or untracked advisory entry.`);
      }
      if (advisories.has(id)) throw new Error(`Baseline target '${target}' contains duplicate advisory ${id}.`);
      advisories.set(id, { ghsa_id: id, severity });
    }
    targets.set(target, advisories);
  }
  return targets;
}

function buildRebalancedBaseline(liveBaseline, current, requiredTargets, trackedSeverities, sourceReport) {
  const targets = {};
  for (const target of requiredTargets.sort()) {
    const accepted = liveBaseline.get(target) ?? new Map();
    const observed = current.get(target) ?? new Map();
    targets[target] = {
      advisories: [...accepted.values()]
        .flatMap((entry) => {
          const advisory = observed.get(entry.ghsa_id);
          if (!advisory || !trackedSeverities.has(advisory.severity)) return [];
          const severity = severityRank(advisory.severity) < severityRank(entry.severity)
            ? advisory.severity : entry.severity;
          return [{ ghsa_id: entry.ghsa_id, severity }];
        })
        .sort((a, b) => a.ghsa_id.localeCompare(b.ghsa_id)),
    };
  }
  return { schema: BASELINE_SCHEMA, generated_at: new Date().toISOString(), source_report: sourceReport, targets };
}

function assertExactTargetSet(targets, expected, label) {
  const actual = [...targets.keys()].sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(`${label} targets must be exactly ${wanted.join(', ')}; found ${actual.join(', ') || 'none'}.`);
  }
}

function severityRank(severity) { return SEVERITY_RANK.get(severity) ?? -1; }

function classifyRegression(aggregated) {
  for (const value of ['declared-regression', 'lockfile-import', 'emergency-override']) {
    if (aggregated.classifications.includes(value)) return value;
  }
  return 'silent-regression';
}

function readPriorBaseline({ fixtureMode, fixtureRoot, repoRoot, baselineRef, baselinePath }) {
  let text = null;
  if (fixtureMode) {
    if (!fixtureRoot) return null;
    const file = resolve(fixtureRoot, '_baseline', baselinePath);
    if (!existsSync(file)) return null;
    text = readFileSync(file, 'utf8');
  } else {
    text = readPriorBaselineText({ sourceRoot: repoRoot, baselineRef, baselinePath });
  }
  if (text === null) return null;
  try { return JSON.parse(text); } catch { return null; }
}

function result(verdict, findings, rebalanceWrites = []) {
  return {
    toolName: 'justsearch-npm-audit', toolVersion: '0.2.0', findings, verdict,
    ruleDescriptions: RULE_DESCRIPTIONS, rebalanceWrites,
  };
}
