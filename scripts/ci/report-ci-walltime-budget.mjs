#!/usr/bin/env node
/**
 * Produce a warn-only wall-clock budget report for a public CI run.
 *
 * The CI checks themselves remain the pass/fail authority. This report only
 * turns wall-clock attribution into advisory budget evidence: it warns when a
 * job's wall-clock exceeds its advisory ceiling. Ceilings are hand-set from
 * measured medians (see ci-walltime-policy.v1.json) — CI wall-clock is a
 * property of the runner environment over time, not of a release, so this
 * mirrors the unit-test-shard-policy advisory-threshold seam rather than the
 * perf-ratchet's release projection. Tempdoc 667.
 */

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const KIND = 'justsearch-ci-walltime-budget.v1';

function repoRootFromCwd() {
  for (let dir = process.cwd(); ; dir = path.dirname(dir)) {
    if (fs.existsSync(path.join(dir, 'settings.gradle.kts'))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return process.cwd();
  }
}

function usage() {
  return [
    'Usage: node scripts/ci/report-ci-walltime-budget.mjs --attribution <file> [options]',
    '',
    'Options:',
    '  --attribution <path>      Wall-clock attribution JSON (required)',
    '  --policy <file>           CI wall-clock policy JSON',
    '  --out-json <path>         Write JSON report',
    '  --out-md <path>           Write Markdown report',
    '  --json                    Print JSON to stdout',
    '  --md                      Print Markdown to stdout',
    '  -h, --help',
  ].join('\n');
}

function parseArgs(argv) {
  const out = {
    attribution: null,
    policy: null,
    outJson: null,
    outMd: null,
    json: false,
    md: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--attribution' && argv[i + 1]) out.attribution = argv[++i];
    else if (arg === '--policy' && argv[i + 1]) out.policy = argv[++i];
    else if (arg === '--out-json' && argv[i + 1]) out.outJson = argv[++i];
    else if (arg === '--out-md' && argv[i + 1]) out.outMd = argv[++i];
    else if (arg === '--json') out.json = true;
    else if (arg === '--md') out.md = true;
    else if (arg === '--help' || arg === '-h') out.help = true;
    else throw new Error(`Unknown or incomplete argument: ${arg}`);
  }
  if (!out.help && !out.attribution) throw new Error('--attribution is required');
  return out;
}

function loadJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function warn(code, message, details = {}) {
  return { code, message, ...details };
}

export function buildWalltimeBudgetReport({ attribution, policy, now = new Date() }) {
  const laneByJob = new Map();
  for (const lane of Array.isArray(policy.lanes) ? policy.lanes : []) {
    if (lane.job) laneByJob.set(lane.job, lane);
  }
  const jobs = Array.isArray(attribution.jobs) ? attribution.jobs : [];
  const warnings = (attribution.requiredCompletion?.missingChecks || []).map((job) => warn('missing-required-check', `${job} is missing or incomplete.`, { job }));

  const reviewByMs = Date.parse(policy.reviewBy || '');
  if (!Number.isFinite(reviewByMs)) {
    warnings.push(warn('invalid-policy-review-date', 'The wall-time policy needs a valid reviewBy date.'));
  } else if (reviewByMs < now.getTime()) {
    warnings.push(warn('stale-policy-evidence', `Wall-time policy evidence expired on ${policy.reviewBy}.`, { reviewBy: policy.reviewBy }));
  }

  const jobNames = new Set(jobs.map((job) => job.name));
  for (const lane of laneByJob.values()) {
    if (lane.requiredCheck && !jobNames.has(lane.job)) {
      warnings.push(warn('missing-required-lane', `${lane.job} is required but absent from this attribution.`, { job: lane.job }));
    }
    const ceiling = lane?.advisoryBudgets?.maxWallSeconds;
    const hardTimeout = lane?.hardTimeoutSeconds;
    if (Number.isFinite(ceiling) && Number.isFinite(hardTimeout) && ceiling > hardTimeout * 0.8) {
      warnings.push(warn('insufficient-timeout-headroom', `${lane.job} advisory ceiling ${ceiling}s leaves less than 20% headroom before its ${hardTimeout}s hard timeout.`, {
        job: lane.job,
        budgetSeconds: ceiling,
        hardTimeoutSeconds: hardTimeout,
      }));
    }
  }

  if (jobs.length === 0) {
    warnings.push(warn('empty-attribution', 'No jobs were found in the wall-clock attribution report.'));
  }

  const evaluated = [];
  for (const job of jobs) {
    const lane = laneByJob.get(job.name);
    const ceiling = lane?.advisoryBudgets?.maxWallSeconds;
    const hasCeiling = Number.isFinite(ceiling);
    if (hasCeiling && job.wallSeconds > ceiling) {
      warnings.push(warn('walltime-over-budget', `${job.name} took ${job.wallSeconds}s, above advisory ceiling ${ceiling}s.`, {
        job: job.name,
        actualSeconds: job.wallSeconds,
        budgetSeconds: ceiling,
      }));
    }
    evaluated.push({
      job: job.name,
      wallSeconds: job.wallSeconds,
      ceilingSeconds: hasCeiling ? ceiling : null,
      hardTimeoutSeconds: Number.isFinite(lane?.hardTimeoutSeconds) ? lane.hardTimeoutSeconds : null,
      overBudget: hasCeiling ? job.wallSeconds > ceiling : false,
    });
  }

  return {
    kind: KIND,
    generatedAt: new Date().toISOString(),
    evidenceMeasuredAt: policy.measuredAt || null,
    evidenceReviewBy: policy.reviewBy || null,
    advisory: true,
    run: attribution.run || {},
    criticalPath: attribution.criticalPath || null,
    requiredCompletion: attribution.requiredCompletion || null,
    lanes: evaluated,
    warningCount: warnings.length,
    warnings,
  };
}

function fmtSeconds(n) {
  if (n === null || n === undefined) return 'n/a';
  if (n >= 60) return `${Math.floor(n / 60)}m ${Math.round(n % 60)}s`;
  return `${n}s`;
}

export function renderMarkdown(report) {
  const lines = [
    '### CI wall-clock budget',
    '',
    `Generated: ${report.generatedAt}`,
    '',
    `Status: warn-only, ${report.warningCount} warning(s)`,
    '',
    '| Job | Wall clock | Advisory ceiling | Hard timeout | Over |',
    '|---|---:|---:|---:|:--:|',
  ];
  for (const lane of report.lanes) {
    lines.push(`| ${lane.job} | ${fmtSeconds(lane.wallSeconds)} | ${lane.ceilingSeconds === null ? 'n/a' : fmtSeconds(lane.ceilingSeconds)} | ${lane.hardTimeoutSeconds === null ? 'n/a' : fmtSeconds(lane.hardTimeoutSeconds)} | ${lane.overBudget ? '⚠️' : ''} |`);
  }
  lines.push('');
  if (report.warnings.length === 0) {
    lines.push('No advisory budget warnings.');
  } else {
    lines.push('| Code | Message |', '|---|---|');
    for (const warning of report.warnings) {
      lines.push(`| ${warning.code} | ${warning.message} |`);
    }
  }
  lines.push('');
  return `${lines.join('\n')}\n`;
}

function writeText(filePath, text) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, text, 'utf8');
}

function main() {
  try {
    const opts = parseArgs(process.argv.slice(2));
    if (opts.help) {
      console.log(usage());
      return;
    }
    const root = repoRootFromCwd();
    const policyPath = path.resolve(opts.policy || path.join(root, 'scripts', 'ci', 'ci-walltime-policy.v1.json'));
    const report = buildWalltimeBudgetReport({
      attribution: loadJson(path.resolve(opts.attribution)),
      policy: loadJson(policyPath),
    });
    const json = `${JSON.stringify(report, null, 2)}\n`;
    const md = renderMarkdown(report);
    if (opts.outJson) writeText(path.resolve(opts.outJson), json);
    if (opts.outMd) writeText(path.resolve(opts.outMd), md);
    if (process.env.GITHUB_STEP_SUMMARY) {
      try {
        fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, md, 'utf8');
      } catch {
        // Best effort only.
      }
    }
    if (opts.json) process.stdout.write(json);
    else if (opts.md || (!opts.outJson && !opts.outMd)) process.stdout.write(md);
  } catch (error) {
    console.error(`report-ci-walltime-budget: ${error.message}`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main();
}
